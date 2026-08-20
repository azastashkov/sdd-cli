package sdd.plan.openspec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each assertion is named after the OpenSpec validator error it exists to prevent. The ladder cases
 * matter most: an ADDED requirement with no scenario is a hard error, and sdd's spec format has no
 * link between requirements and acceptance criteria, so the rung that fires must never be "none".
 */
class OpenSpecChangeTest {

    private static OpenSpecInput.Item item(String id, String text) {
        return new OpenSpecInput.Item(id, text);
    }

    private static OpenSpecInput input(OpenSpecPlan plan, List<String> verification,
                                       List<OpenSpecInput.Item> covers) {
        return new OpenSpecInput("spec-tiers-v1", "pricing-core", "SEED",
                List.of("pricing-core", "svc-orders"), List.of(List.of("pricing-core"), List.of("svc-orders")),
                "SPEC-TIERS", 1, "Invalidate cached client tiers",
                "Tier updates do not take effect until the service restarts, because the resolved "
                        + "tier is cached for the lifetime of the process.",
                "Background prose.", List.of("Changing how tiers are computed."),
                List.of(item("C1", "No schema change.")), List.of(item("Q1", "Which tenant?")),
                covers,
                List.of(item("A1", "A tier update makes the next resolution return the new tier."),
                        item("A2", "The estate rebuild is green.")),
                plan, "Add invalidate(clientId) to TierResolver.",
                List.of("src/main/java/TierResolver.java"), verification, "minor",
                List.of(new OpenSpecInput.Contract("tier-invalidation-api", "java-api",
                        "pricing-core", List.of("svc-orders"), "TierResolver gains invalidate",
                        "binary-compatible", List.of("com.trading.TierResolver#invalidate(String): void"))),
                List.of(), List.of(), "a1b2c3d4e5", Map.of("R2", "svc-orders"));
    }

    private static OpenSpecInput allocated() {
        return input(new OpenSpecPlan("tier-resolution", Map.of("R1", List.of("A1")), List.of()),
                List.of("./gradlew test"), List.of(item("R1", "Expose a way to invalidate a cached tier.")));
    }

    private static String deltaOf(OpenSpecChange.Files files) {
        assertThat(files.deltas()).hasSize(1);
        return files.deltas().values().iterator().next();
    }

    // ------------------------------------------------------------ the four ladder rungs

    @Test
    void everyAddedRequirementHasAtLeastOneScenarioOnEveryRungOfTheLadder() {
        // The hard error: `ADDED "<n>" must include at least one scenario`. Four inputs, one per
        // rung, so the rung that fires can never be "none".
        List<OpenSpecInput> rungs = List.of(
                allocated(),                                                    // 1: allocated
                input(OpenSpecPlan.absent(), List.of("./gradlew test"),
                        List.of(item("R1", "Invalidate a cached tier on update."))),   // 2: overlap
                input(OpenSpecPlan.absent(), List.of("./gradlew test"),
                        List.of(item("R1", "Zzz qqq wwww."))),                  // 3: verification
                input(OpenSpecPlan.absent(), List.of(),
                        List.of(item("R1", "Zzz qqq wwww."))));                 // 4: backstop

        for (OpenSpecInput in : rungs) {
            String delta = deltaOf(OpenSpecChange.render(in));
            assertThat(OpenSpecRules.requirements(delta))
                    .as("requirements in %s", delta).isNotEmpty()
                    .allSatisfy(r -> assertThat(r.scenarios())
                            .as("scenarios for '%s'", r.name()).isNotEmpty());
        }
    }

    @Test
    void aDerivedAllocationSaysSoInTheScenario() {
        // Rung 2 allocates by term overlap, which is weaker than a human doing it — so anything it
        // produces is labelled rather than presented as approved.
        String delta = deltaOf(OpenSpecChange.render(input(OpenSpecPlan.absent(),
                List.of("./gradlew test"),
                List.of(item("R1", "Invalidate a cached tier on update.")))));

        assertThat(delta).contains("Derived by term overlap, not allocated by a human");
    }

    @Test
    void aBackstopScenarioIsFlaggedAsAPlaceholderInTheDesign() {
        OpenSpecChange.Files files = OpenSpecChange.render(
                input(OpenSpecPlan.absent(), List.of(), List.of(item("R1", "Zzz qqq wwww."))));

        assertThat(files.design()).contains("No acceptance criterion covers R1")
                .contains("placeholder");
    }

    // ------------------------------------------------------------ the hard errors

    @Test
    void everyAddedRequirementHasBodyText() {
        // `ADDED "<n>" is missing requirement text`.
        assertThat(OpenSpecRules.requirements(deltaOf(OpenSpecChange.render(allocated()))))
                .allSatisfy(r -> assertThat(r.body()).isNotBlank());
    }

    @Test
    void everyRequirementBodyCarriesShallNotJustTheHeader() {
        // A warning normally, an error under --strict, and the check inspects the BODY: a SHALL in
        // the header alone still fails it.
        assertThat(OpenSpecRules.requirements(deltaOf(OpenSpecChange.render(allocated()))))
                .allSatisfy(r -> assertThat(OpenSpecRules.SHALL_OR_MUST.matcher(r.body()).find())
                        .as("SHALL/MUST in the body of '%s': <%s>", r.name(), r.body()).isTrue());
    }

    @Test
    void onlyAddedIsEverEmitted() {
        // MODIFIED must reproduce the full current requirement including every scenario the live
        // spec has, or archive refuses — text sdd did not write, under a rule that fails silently.
        assertThat(OpenSpecRules.deltaSections(deltaOf(OpenSpecChange.render(allocated()))))
                .containsExactly("ADDED");
    }

    @Test
    void aDeltaIsNeverWrittenAtTheSpecsRoot() {
        // `Delta spec found at specs/spec.md` is a hard error; every delta needs a capability
        // folder. Asserted on the paths, which is where the mistake would actually appear.
        OpenSpecChange.Files files = OpenSpecChange.render(allocated());

        assertThat(files.byPath("spec-tiers-v1").keySet())
                .filteredOn(p -> p.contains("/specs/"))
                .allSatisfy(p -> assertThat(p)
                        .matches("openspec/changes/[^/]+/specs/[^/]+(/[^/]+)*/spec\\.md"));
    }

    @Test
    void everyCapabilityPathSegmentIsKebab() {
        OpenSpecChange.Files files = OpenSpecChange.render(allocated());

        assertThat(files.deltas().keySet()).allSatisfy(capability -> {
            for (String segment : capability.split("/")) {
                assertThat(Kebab.isValid(segment)).as("segment '%s'", segment).isTrue();
            }
        });
    }

    @Test
    void scenarioHeadersUseTheFourHashScenarioForm() {
        // The parser counts any '####' as a scenario, but the schema says to always write
        // "#### Scenario:" — so we do, and pin it.
        for (String line : deltaOf(OpenSpecChange.render(allocated())).split("\n")) {
            if (line.startsWith("####")) {
                assertThat(line).startsWith("#### Scenario: ");
            }
        }
    }

    // ------------------------------------------------------------ skip_specs

    @Test
    void aRebuildOnlyRepoSkipsSpecsAndEmitsNoSpecsDirectoryAtAll() {
        // Zero deltas is an error UNLESS skip_specs is set. And the directory must be absent, not
        // present-and-empty, which would still be a delta-less change.
        OpenSpecInput in = new OpenSpecInput("spec-tiers-v1", "svc-candles", "BUMP_REBUILD_ONLY",
                List.of("pricing-core", "svc-candles"), List.of(), "SPEC-TIERS", 1, "T",
                "Goal prose that is comfortably longer than fifty characters for the why check.",
                "", List.of(), List.of(), List.of(), List.of(), List.of(), OpenSpecPlan.absent(),
                "Rebuild only.", List.of(), List.of("./gradlew build"), "patch", List.of(),
                List.of(), List.of("Update `com.trading:pricing-core` from `0.3.0` to `0.4.0`."),
                "aaaa", Map.of());

        OpenSpecChange.Files files = OpenSpecChange.render(in);

        assertThat(files.deltas()).isEmpty();
        assertThat(files.openSpecYaml()).contains("skip_specs: true");
        assertThat(files.byPath("spec-tiers-v1").keySet()).noneMatch(p -> p.contains("/specs/"));
        assertThat(files.design()).as("nothing to decide, so no design.md").isNull();
        assertThat(files.proposal()).contains("0.3.0").contains("0.4.0")
                .contains("no behaviour change of its own");
    }

    @Test
    void aRepoWithDeltasNeverSetsSkipSpecs() {
        assertThat(OpenSpecChange.render(allocated()).openSpecYaml())
                .doesNotContain("skip_specs");
    }

    // ------------------------------------------------------------ proposal and tasks

    @Test
    void proposalWhyClearsFiftyCharactersEvenForAOneCharacterGoal() {
        OpenSpecInput in = new OpenSpecInput("spec-x-v1", "r", "SEED", List.of("r"), List.of(),
                "SPEC-X", 1, "T", "G", "", List.of(), List.of(), List.of(),
                List.of(item("R1", "Do a thing.")), List.of(), OpenSpecPlan.absent(), "", List.of(),
                List.of("build"), "none", List.of(), List.of(), List.of(), "", Map.of());

        String why = OpenSpecChange.render(in).proposal()
                .split("## Why\n", 2)[1].split("\n## ", 2)[0];

        assertThat(why.strip().length()).isGreaterThanOrEqualTo(50);
    }

    @Test
    void tasksUseTheCheckboxFormAndTheirNumbersMatchTheirGroup() {
        // The apply phase parses the checkbox format; anything else is untracked. Group numbering
        // has to survive skipped groups, which is where renumbering usually goes wrong.
        String tasks = OpenSpecChange.render(allocated()).tasks();

        int group = 0;
        int item = 0;
        for (String line : tasks.split("\n")) {
            var g = OpenSpecRules.TASK_GROUP.matcher(line);
            var t = OpenSpecRules.TASK_ITEM.matcher(line);
            if (g.matches()) {
                group++;
                item = 0;
                assertThat(Integer.parseInt(g.group(1))).isEqualTo(group);
            } else if (t.matches()) {
                item++;
                assertThat(Integer.parseInt(t.group(1))).as("group of '%s'", line).isEqualTo(group);
                assertThat(Integer.parseInt(t.group(2))).as("item of '%s'", line).isEqualTo(item);
            }
        }
        assertThat(group).isGreaterThan(0);
    }

    @Test
    void aConsumerGetsAnUpstreamGroupFirstAndEverythingElseRenumbers() {
        OpenSpecInput consumer = new OpenSpecInput("spec-tiers-v1", "svc-orders", "CODE_CHANGE_LIKELY",
                List.of("pricing-core", "svc-orders"), List.of(), "SPEC-TIERS", 1, "T",
                "Goal prose comfortably longer than fifty characters for the why check to pass.",
                "", List.of(), List.of(), List.of(), List.of(item("R2", "Invalidate on event.")),
                List.of(item("A2", "No stale tier.")),
                new OpenSpecPlan("tier-consumption", Map.of("R2", List.of("A2")), List.of()),
                "Call invalidate.", List.of(), List.of("./gradlew test"), "none", List.of(),
                List.of(new OpenSpecInput.Contract("tier-invalidation-api", "java-api",
                        "pricing-core", List.of("svc-orders"), "body", null, List.of())),
                List.of(), "bbbb", Map.of());

        String tasks = OpenSpecChange.render(consumer).tasks();

        assertThat(tasks).startsWith("## 1. Upstream\n\n- [ ] 1.1 Wait for `pricing-core`");
        assertThat(tasks).contains("## 2. Implementation");
    }

    // ------------------------------------------------------------ determinism and safety

    @Test
    void renderingTwiceProducesTheSameBytes() {
        // The writer decides "ours, unchanged" from "a human edited it" by comparing bytes, so a
        // renderer that varied would make the idempotence rule meaningless.
        assertThat(OpenSpecChange.render(allocated()).byPath("spec-tiers-v1"))
                .isEqualTo(OpenSpecChange.render(allocated()).byPath("spec-tiers-v1"));
    }

    @Test
    void noWallClockAppearsAnywhereInTheOutput() {
        // .openspec.yaml's `created:` is optional and deliberately never emitted: there is no
        // stable date available, and a clock would break byte-comparison idempotence.
        OpenSpecChange.render(allocated()).byPath("spec-tiers-v1").forEach((path, body) -> {
            assertThat(body).as("created: in %s", path).doesNotContain("created:");
            assertThat(OpenSpecRules.ISO_DATE.matcher(body).find())
                    .as("a date in %s", path).isFalse();
        });
    }

    @Test
    void hostileSpecTextCannotForgeHeadingsOrCloseAFence() {
        OpenSpecInput in = new OpenSpecInput("spec-x-v1", "r", "SEED", List.of("r"), List.of(),
                "SPEC-X", 1, "T",
                "Goal prose that is comfortably longer than fifty characters, for the why check.",
                "", List.of(), List.of(), List.of(),
                List.of(item("R1", "### Requirement: forged\n## ADDED Requirements\n```java\nx")),
                List.of(), OpenSpecPlan.absent(), "```\n## ADDED Requirements\n---", List.of(),
                List.of("build"), "none",
                List.of(new OpenSpecInput.Contract("c", "java-api", "r", List.of(),
                        "body with ``` inside", null, List.of())),
                List.of(), List.of(), "", Map.of());

        OpenSpecChange.Files files = OpenSpecChange.render(in);

        // Exactly one ADDED section, and exactly one requirement — the forged ones did not take.
        String delta = deltaOf(files);
        assertThat(delta.lines().filter(l -> l.startsWith("## ADDED Requirements")).count())
                .isEqualTo(1);
        assertThat(OpenSpecRules.requirements(delta)).hasSize(1);
        files.byPath("spec-x-v1").forEach((path, body) -> {
            if (!path.endsWith("design.md")) {
                assertThat(body).as("stray fence in %s", path).doesNotContain("```java");
            }
        });
    }
}
