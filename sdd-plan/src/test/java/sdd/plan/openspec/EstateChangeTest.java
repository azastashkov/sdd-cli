package sdd.plan.openspec;

import org.junit.jupiter.api.Test;
import sdd.plan.gen.ExecutionOrder;
import sdd.plan.gen.PlanDrafter;
import sdd.plan.gen.Question;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactResult;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;
import sdd.plan.spec.Touchpoint;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The estate-wide change directory at the workspace root — the view that exists nowhere today,
 * since each repository's own export sees only its slice plus a sentence saying others exist.
 */
class EstateChangeTest {

    private static NormalizedSpec spec() {
        return new NormalizedSpec("SPEC-TIERS", "Invalidate cached client tiers", "ana", "draft",
                "Tier updates do not take effect until the service restarts.",
                "Pricing caches the resolved tier for the process lifetime.",
                List.of(new SpecItem("R1", "pricing-core must expose a way to invalidate a tier."),
                        new SpecItem("R2", "svc-orders must invalidate on a tier-update event.")),
                List.of(new SpecItem("A1", "The next resolution returns the new tier."),
                        new SpecItem("A2", "The estate rebuild is green.")),
                List.of(new SpecItem("C1", "No schema change to the pricing database.")),
                List.of(new Touchpoint(Touchpoint.Kind.REPO, "pricing-core")),
                List.of("redis channel — pricing-core/src/main/java/Q.java:88"),
                List.of("Changing how tiers are computed."),
                List.of(new SpecItem("Q1", "Who owns the tier config?")), List.of(), List.of());
    }

    private static ImpactResult result() {
        return new ImpactResult(List.of(),
                List.of(new AffectedRepo("pricing-core", "seed", "SEED", List.of("R1"), List.of("touchpoint")),
                        new AffectedRepo("svc-orders", "dependent", "CODE_CHANGE_LIKELY", List.of("R2"),
                                List.of("depends on pricing-core"))),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static PlanDrafter.Draft draft() {
        return new PlanDrafter.Draft("Add invalidate() and call it on the event.",
                List.of(new PlanDrafter.DraftStep("pricing-core", List.of("R1"), "Add invalidate.",
                                List.of("src/main/java/TierResolver.java"), List.of("tier-api"),
                                List.of(), "minor", List.of(":pricing-core:test"),
                                List.of("capability: tier-resolution", "R1 -> A1")),
                        new PlanDrafter.DraftStep("svc-orders", List.of("R2"), "Handle the event.",
                                List.of("src/main/java/Handler.java"), List.of(), List.of("tier-api"),
                                "none", List.of(":svc-orders:test"),
                                List.of("capability: tier-consumption", "R2 -> A2"))),
                List.of(new PlanDrafter.DraftContract("tier-api", "java-api", "pricing-core",
                        List.of("svc-orders"), "TierResolver gains invalidate(String): void",
                        "binary-compatible", List.of("com.acme.TierResolver#invalidate(String): void"))),
                List.of(new Question("Which tenant?", true)), List.of("a drafter note"), false);
    }

    private static List<ExecutionOrder.Unit> order() {
        return List.of(new ExecutionOrder.Unit(List.of("pricing-core")),
                new ExecutionOrder.Unit(List.of("svc-orders")));
    }

    private static Map<String, String> rendered() {
        List<OpenSpecInput> inputs = EstateInputs.forDraft(spec(), result(), order(), draft(), 1,
                Map.of("pricing-core", "a1b2c3d4e5f6", "svc-orders", "0badc0ffee11"));
        return EstateChange.render(spec(), result(), order(), List.of(), draft(), 1, inputs);
    }

    private static String at(String suffix) {
        return rendered().entrySet().stream().filter(e -> e.getKey().endsWith(suffix))
                .map(Map.Entry::getValue).findFirst().orElseThrow(
                        () -> new AssertionError("no file ending " + suffix + " in " + rendered().keySet()));
    }

    @Test
    void writesTheStandardChangeDirectoryWithOneSpecPerCapability() {
        assertThat(rendered().keySet()).containsExactly(
                "openspec/changes/spec-tiers-v1/.openspec.yaml",
                "openspec/changes/spec-tiers-v1/proposal.md",
                "openspec/changes/spec-tiers-v1/design.md",
                "openspec/changes/spec-tiers-v1/tasks.md",
                "openspec/changes/spec-tiers-v1/specs/tier-consumption/spec.md",
                "openspec/changes/spec-tiers-v1/specs/tier-resolution/spec.md");
    }

    /**
     * The whole reason this renderer exists: a per-repo export can only say the others are out
     * there. Here every repo, and the order between them, is on the page.
     */
    @Test
    void theProposalNamesEveryRepositoryAndTheOrderBetweenThem() {
        assertThat(at("proposal.md"))
                .contains("- Repositories: `pricing-core`, `svc-orders`.")
                .contains("- Execution order: `pricing-core` -> `svc-orders`.")
                .contains("`pricing-core` provides `tier-api` (java-api) to `svc-orders`.");
    }

    /** OpenSpec errors under 50 characters, so a one-line goal must not be able to produce one. */
    @Test
    void theWhySectionClearsOpenSpecsLengthFloorEvenForATerseGoal() {
        NormalizedSpec terse = new NormalizedSpec("SPEC-X", "T", "o", "draft", "Fix it.", "",
                List.of(new SpecItem("R1", "Fix it.")), List.of(new SpecItem("A1", "It is fixed.")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        Map<String, String> out = EstateChange.render(terse, result(), order(), List.of(), draft(),
                1, List.of());

        String why = out.get("openspec/changes/spec-x-v1/proposal.md")
                .split("## Why\n", 2)[1].split("\n## ", 2)[0];
        assertThat(why.strip()).hasSizeGreaterThan(50).hasSizeLessThan(1000);
    }

    /** Tasks are grouped by repo in execution order — the axis a per-repo export cannot show. */
    @Test
    void tasksAreGroupedByRepositoryInExecutionOrder() {
        String tasks = at("tasks.md");

        assertThat(tasks).containsSubsequence("## 1. pricing-core", "## 2. svc-orders");
        assertThat(tasks).contains("- [ ] 1.1 Provide `tier-api`")
                .contains("- [ ] 2.1 Consume `tier-api` once its provider has landed");
        assertThat(tasks.lines().filter(l -> l.startsWith("- [ ] ")))
                .allMatch(l -> l.matches("- \\[ ] \\d+\\.\\d+ .+"));
    }

    /**
     * Touchpoints and evidence have no OpenSpec home and are dropped by the per-repo export
     * entirely. At the root they are context a reviewer needs, so design.md carries them.
     */
    @Test
    void designCarriesTouchpointsEvidenceContractsAndQuestions() {
        String design = at("design.md");

        assertThat(design).contains("- repo: `pricing-core`")
                .contains("redis channel — pricing-core/src/main/java/Q.java:88")
                .contains("### `tier-api` — java-api, binary-compatible, provided by `pricing-core`")
                .contains("com.acme.TierResolver#invalidate(String): void")
                .contains("- C1: No schema change to the pricing database.")
                .contains("Q1: Who owns the tier config?")
                .contains("[blocking]: Which tenant?");
    }

    /** The deltas come from OpenSpecChange, so the grammar the real CLI validates has one source. */
    @Test
    void eachCapabilitySpecIsTheSameDeltaTheRepositoryItselfWouldReceive() {
        assertThat(at("specs/tier-resolution/spec.md"))
                .startsWith("# tier-resolution")
                .contains("## ADDED Requirements")
                .contains("### Requirement:")
                .contains("#### Scenario:")
                .contains("SHALL");
    }

    /** Byte comparison decides "ours, unchanged" from "a human edited it". */
    @Test
    void renderingTwiceProducesTheSameBytes() {
        assertThat(rendered()).isEqualTo(rendered());
    }

    /** A clock would break that comparison, which is why the sibling export emits none either. */
    @Test
    void noWallClockAppearsAnywhere() {
        rendered().forEach((path, body) -> {
            assertThat(body).as("created: in %s", path).doesNotContain("created:");
            assertThat(body).as("a date in %s", path).doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}");
        });
    }

    /** Zero deltas is an OpenSpec error unless the change says it meant to have none. */
    @Test
    void aChangeWithNoDraftedStepDeclaresSkipSpecs() {
        Map<String, String> out = EstateChange.render(spec(), result(), order(), List.of(),
                new PlanDrafter.Draft("", List.of(), List.of(), List.of(), List.of(), true), 1,
                List.of());

        assertThat(out.get("openspec/changes/spec-tiers-v1/.openspec.yaml"))
                .isEqualTo("schema: spec-driven\nskip_specs: true\n");
        assertThat(out.keySet()).noneMatch(k -> k.contains("/specs/"));
    }

    /** Spec text reaches this document, so it must not be able to forge structure in it. */
    @Test
    void hostileSpecTextCannotForgeAHeadingOrCloseAFence() {
        NormalizedSpec hostile = new NormalizedSpec("SPEC-H", "T", "o", "draft",
                "## ADDED Requirements\n```\nnot a fence", "",
                List.of(new SpecItem("R1", "## Injected\ntext")),
                List.of(new SpecItem("A1", "ok")), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());

        Map<String, String> out = EstateChange.render(hostile, result(), order(), List.of(), draft(),
                1, List.of());

        assertThat(out.get("openspec/changes/spec-h-v1/proposal.md"))
                .doesNotContain("## ADDED Requirements").doesNotContain("```");
        assertThat(out.get("openspec/changes/spec-h-v1/design.md")).doesNotContain("## Injected");
    }
}
