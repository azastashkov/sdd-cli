# Phase 3C-2 — plan approve Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Gate 1 closes: `sdd plan approve <plan.md>` strictly parses the (human-edited) plan, validates it (blocking-question resolutions, requirement coverage, contract closure, topo legality naming violated edges, base-SHA staleness), chooses per-edge propagation mechanisms via a seamed `--include-build` smoke runner, and compiles the SHA-256-pinned `plan.json`; `sdd plan revise` regenerates with Q&A folded in and a version bump; every plan-artifact write gains a backup-on-overwrite guard.

**Architecture:** New package `sdd.plan.approve` (`PlanDocument` records, `PlanMdParser` pinning the 3C-1 renderer's exact grammar plus the human-only `- resolution:` extension, `PlanValidator`, `LiveGit` staleness helper, `SmokeRunner` seam + `GradleSmokeRunner` ProcessBuilder impl — the codebase's first subprocess, `PlanJson` compiler) plus `SafeWrite` in `sdd.plan.gen` and two picocli subcommands nested under `PlanCommand` (bytecode-verified: picocli 4.7.6 matches subcommand names before positionals; a root-dispatch e2e pins it). Approve is fully deterministic — ZERO model calls; revise reuses the 3C-1 drafting path with prior Q&A appended. Design authority: `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md` Component 2 "plan approve" bullet (critique M5) + amendments; entry checklist: bottom of `docs/superpowers/plans/2026-08-12-phase3c1-plan-md-generation.md`.

**Tech Stack:** Java 21, Jdbi 3, Jackson (plan.json), JGit (NEW `implementation` dep of sdd-plan — already in the catalog), ProcessBuilder (smoke runner), picocli nested subcommands. NO new third-party libraries.

## Global Constraints

- Java 21; NO new third-party libraries (JGit moves scopes only: catalog entry exists; sdd-plan gains `implementation(libs.jgit)`); sdd-plan depends only on sdd-core.
- `sdd plan approve` is FULLY deterministic — no model calls, no config-file requirement (it needs only the workspace KB, git, and Gradle wrappers). `sdd plan revise` loads config for the planner exactly like `sdd plan`.
- Parser law: `PlanMdParser.parse` accepts EXACTLY what `PlanMdRenderer.render` emits (front matter `spec:`/`plan_version:`; 8 sections in fixed order; `- none` / `- none (drafting unavailable)` sentinels; question lines `- Q<n> [blocking]: <text>` / `- Q<n>: <text>`; affected rows `- <repo> — <role>/<annotation> — covers: <csv|-> — why: <text>`; order lines `<n>. <a>[ + <b> ...][ (co-scheduled)]`; contract heads `### <id> (<kind>) — <provider> -> <consumers csv>` + fenced ```yaml body; step blocks `### <repo>` with covers/version_action/provides/consumes scalars, optional `- files:`/`- verification:` 2-space sublists, then prose) PLUS one human-only extension: an optional `  - resolution: <text>` line immediately under a question line. Structural violations throw `PlanParseException` with messages starting `line <N>: ` (SpecParseException precedent).
- Validator taxonomy: PROBLEMS block approval (`problem: <text>`, exit 1): unresolved blocking questions, uncovered requirements, undefined/duplicate contract ids, provider without a providing step, step repos or order repos not matching the Affected set, illegal topo order (message NAMES the violated edge), stale/dirty repos, spec-id mismatch. WARNINGS don't block (`warn: <text>`): contract-vs-constraint token overlap, MAVEN_LOCAL fallback per edge, an affected contract consumer whose step exists but does not consume the id, an affected contract consumer with NO step (rebuild-only dependents are legitimate). PROBLEMS additionally include: a contract consumer that is not in Affected Repos at all, and a duplicate step for the same repo.
- Staleness law (design M5/M8 boundary): approve verifies for EVERY plan repo that the KB `head_commit` equals the live git HEAD and the working tree is clean; any mismatch is a problem telling the user to re-index and regenerate — recovery flows are Phase 4.
- Smoke law (design M5): for every internal edge of the plan subgraph with mode ≠ COMPOSITE, the mechanism is chosen by a LIVE probe through the `SmokeRunner` seam — `INCLUDE_BUILD` on success, `MAVEN_LOCAL` + warning on failure; COMPOSITE edges get mechanism `NONE`. Tests script the seam; the real `GradleSmokeRunner` runs `./gradlew help --include-build <providerPath> --no-configuration-cache` in the consumer repo (first ProcessBuilder in the codebase: 120 s timeout, `destroyForcibly` on expiry, output tail in the failure detail, missing wrapper → failure).
- plan.json is deterministic: Jackson `writerWithDefaultPrettyPrinter`, record components declared in snake_case emission order, lists in document/KB order; pins `spec_sha256` and `plan_sha256` (SHA-256 hex of the exact file bytes, `RepoCardGenerator.sha256` precedent) and per-repo `base_sha` from the KB.
- Approve resolves the spec as the plan's sibling: `<base>.plan.md` → `<base>.md`; the spec must parse, pass `SpecValidator`, and carry the front-matter `spec:` id.
- Overwrite guard (entry checklist #2): `SafeWrite.writeWithBackup` — an existing target moves to `<name>.bak` (replacing any older `.bak`) before the write; call sites print `previous version backed up: <path>`. Applied to plan.md (validate path), .spec.md (normalize path), and revise output. plan.json is machine-owned and overwrites plainly.
- CLI taxonomy unchanged: exceptions → `error: <msg>` stderr exit 1; validation findings → `problem: <text>` exit 1; content/warnings → stdout exit 0 unless problems exist. All printf `Locale.ROOT`.
- Never read or print `.env` or any `api_key`; test yaml uses unreachable endpoints.
- Never push. Full `./gradlew build` before any commit touching more than one module.

---

## File Structure

**Task 1:** `sdd-plan/src/main/java/sdd/plan/gen/SafeWrite.java` + test; `sdd-cli/.../PlanCommand.java` (both write paths); `sdd-plan/.../gen/PlanMdRenderer.java` (empty-consumers arrow); `sdd.yml.example`
**Task 2:** `sdd-plan/src/main/java/sdd/plan/approve/{PlanParseException,PlanDocument}.java` + test
**Task 3:** `sdd-plan/src/main/java/sdd/plan/approve/PlanMdParser.java` (sections A: front matter, Summary, Open Questions + resolutions, Generation Notes) + test
**Task 4:** `PlanMdParser.java` (sections B: Affected/Excluded/Order/Contracts/Steps) + renderer round-trip test
**Task 5:** `sdd-plan/build.gradle.kts` (+jgit); `sdd-plan/src/main/java/sdd/plan/approve/{LiveGit,Hashes}.java`; `sdd-plan/.../gen/ExecutionOrder.java` (edges → public) + tests
**Task 6:** `sdd-plan/src/main/java/sdd/plan/approve/PlanValidator.java` + test
**Task 7:** `sdd-plan/src/main/java/sdd/plan/approve/{SmokeRunner,GradleSmokeRunner}.java` + test (stub gradlew scripts)
**Task 8:** `sdd-plan/src/main/java/sdd/plan/approve/PlanJson.java` + test
**Task 9:** `sdd-cli/src/main/java/sdd/cli/ApproveCommand.java`; `PlanCommand.java` (subcommands attr) + e2e incl. root dispatch
**Task 10:** `sdd-cli/src/main/java/sdd/cli/ReviseCommand.java`; `sdd-plan/.../gen/PlanDrafter.java` (priorQa overload) + `PlanMdRenderer.java` (planVersion overload) + e2e

---

### Task 1: SafeWrite + write-path guards + renderer arrow fix + example config

**Files:**
- Create: `sdd-plan/src/main/java/sdd/plan/gen/SafeWrite.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/PlanCommand.java` (normalize ~:98-109, validate ~:146-155)
- Modify: `sdd-plan/src/main/java/sdd/plan/gen/PlanMdRenderer.java` (contract heading)
- Modify: `sdd.yml.example` (planner `max_tokens: 32768` + comment)
- Test: `sdd-plan/src/test/java/sdd/plan/gen/SafeWriteTest.java`, `sdd-plan/src/test/java/sdd/plan/gen/PlanMdRendererTest.java`, `sdd-cli/src/test/java/sdd/cli/PlanCommandTest.java`

**Interfaces:**
- Produces: `public final class SafeWrite { public static Path writeWithBackup(Path target, String content) }` — returns the backup path when a previous file existed, else null; backup is `<fileName>.bak` in the same directory, replacing any older `.bak`. Renderer: an empty consumers list renders `### <id> (<kind>) — <provider>` with NO trailing `-> `.

- [ ] **Step 1: Write the failing SafeWrite test:**

```java
package sdd.plan.gen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SafeWriteTest {
    @TempDir Path dir;

    @Test
    void firstWriteHasNoBackupSecondWriteBacksUpThirdReplacesTheBackup() throws Exception {
        Path target = dir.resolve("x.plan.md");

        assertThat(SafeWrite.writeWithBackup(target, "v1")).isNull();
        assertThat(Files.readString(target)).isEqualTo("v1");

        Path backup = SafeWrite.writeWithBackup(target, "v2");
        assertThat(backup).isEqualTo(dir.resolve("x.plan.md.bak"));
        assertThat(Files.readString(backup)).isEqualTo("v1");
        assertThat(Files.readString(target)).isEqualTo("v2");

        assertThat(SafeWrite.writeWithBackup(target, "v3")).isEqualTo(backup);
        assertThat(Files.readString(backup)).isEqualTo("v2");
        assertThat(Files.readString(target)).isEqualTo("v3");
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement `SafeWrite.java`:

```java
package sdd.plan.gen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Overwrite guard for human-edited gate artifacts (Phase 3C-2 entry checklist #2): an
 * existing target is preserved as <name>.bak before the new content lands, so an accidental
 * regeneration never silently destroys review edits.
 */
public final class SafeWrite {

    private SafeWrite() {
    }

    public static Path writeWithBackup(Path target, String content) {
        try {
            Path backup = null;
            if (Files.exists(target)) {
                backup = target.resolveSibling(target.getFileName() + ".bak");
                Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(target, content);
            return backup;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

- [ ] **Step 3: Wire PlanCommand.** In `normalize(...)` replace the `Files.writeString(target, rendered)` try/catch AND the immediately-following `outWriter.println("normalized spec written: " + target);` line — one unit, so the write line is not printed twice — with:

```java
Path backup = SafeWrite.writeWithBackup(target, rendered);
outWriter.println("normalized spec written: " + target);
if (backup != null) {
    outWriter.println("previous version backed up: " + backup);
}
```

In `validate(...)` replace the `Files.writeString(planPath, planMd)` try/catch AND the immediately-following `outWriter.println("plan written: " + planPath);` line — same one-unit rule — with:

```java
Path backup = SafeWrite.writeWithBackup(planPath, planMd);
outWriter.println("plan written: " + planPath);
if (backup != null) {
    outWriter.println("previous version backed up: " + backup);
}
```

ALSO in `validate(...)`: the follow-up hint still reads `"review and edit the plan, then run: sdd plan approve (Phase 3C-2)"` — this plan ships the real command, so change it to `"review and edit the plan, then run: sdd plan approve"` and update the pinning assertion in `PlanCommandTest.validSpecWritesGate1PlanMd` (drop the `" (Phase 3C-2)"` suffix) in the same commit.

(Remove the now-unused `Files`/`IOException`/`UncheckedIOException` imports ONLY if nothing else in the file uses them — `Files.exists` is still used by the KB check, so keep `Files`.) Add a CLI test asserting the backup line (append to `PlanCommandTest`, reusing `validSpecWritesGate1PlanMd`'s KB/spec/model setup shape — run the command twice with two fresh `PlanCommand` instances, each scripting TWO responses; assert the second run's output contains `previous version backed up: ` and that `loyalty.plan.md.bak` exists).

- [ ] **Step 4: Renderer arrow fix.** In `PlanMdRendererTest` add:

```java
@Test
void contractWithNoConsumersRendersHeadingWithoutArrow() {
    PlanDrafter.Draft draft = new PlanDrafter.Draft("S.",
            List.of(),
            List.of(new PlanDrafter.DraftContract("C-1", "java-api", "lib-core", List.of(), "body")),
            List.of(), List.of(), false);

    String md = PlanMdRenderer.render(spec(), impact(),
            List.of(new ExecutionOrder.Unit(List.of("lib-core"))), List.of(), draft);

    assertThat(md).contains("### C-1 (java-api) — lib-core\n```yaml");
    assertThat(md).doesNotContain("-> \n");
}
```

Run RED, then change the contract-heading emission to:

```java
md.append("\n### ").append(inline(contract.id())).append(" (").append(contract.kind())
        .append(") — ").append(contract.provider());
if (!contract.consumers().isEmpty()) {
    md.append(" -> ").append(String.join(", ", contract.consumers()));
}
md.append('\n');
```

- [ ] **Step 5: sdd.yml.example.** Read the file; set the planner `max_tokens:` value to `32768` and put this comment on the line above it: `# drafting a Gate-1 plan needs ~2x the seeding budget; 16384 truncates on medium estates`.

- [ ] **Step 6: Full build, then commit**

```bash
./gradlew build
git add sdd-plan/src sdd-cli/src sdd.yml.example
git commit -m "feat: backup-on-overwrite guard for gate artifacts and drafting-budget example"
```

---

### Task 2: PlanDocument records + PlanParseException

**Files:**
- Create: `sdd-plan/src/main/java/sdd/plan/approve/PlanParseException.java`, `PlanDocument.java`
- Test: `sdd-plan/src/test/java/sdd/plan/approve/PlanDocumentTest.java`

**Interfaces (Tasks 3-10 depend on these EXACT shapes):**
- `public class PlanParseException extends RuntimeException` — ctor `(int line, String message)`, message `"line " + line + ": " + message`, accessor `int line()` (SpecParseException precedent verbatim).
- `public record PlanDocument(String specId, int planVersion, String summary, List<PlanQuestion> questions, List<PlanRepo> affected, List<PlanExcluded> excluded, List<List<String>> order, List<PlanContract> contracts, List<PlanStep> steps, List<String> notes)` with nested records:
  - `public record PlanQuestion(int number, boolean blocking, String text, String resolution)` — resolution null when absent.
  - `public record PlanRepo(String repo, String role, String annotation, List<String> covers, String why)`
  - `public record PlanExcluded(String repo, String detail)`
  - `public record PlanContract(String id, String kind, String provider, List<String> consumers, String body)`
  - `public record PlanStep(String repo, List<String> covers, String versionAction, List<String> provides, List<String> consumes, List<String> files, List<String> verification, String subSpec)`
  - All: `Objects.requireNonNull` on non-nullable strings, `List.copyOf` on every list (including the nested lists inside `order`).

- [ ] **Step 1: Write the failing test:**

```java
package sdd.plan.approve;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanDocumentTest {

    @Test
    void recordsAreDefensiveNullHostileAndResolutionIsNullable() {
        java.util.List<List<String>> mutableOrder = new java.util.ArrayList<>(
                List.of(List.of("a")));
        PlanDocument doc = new PlanDocument("SPEC-1", 1, "S.",
                List.of(new PlanDocument.PlanQuestion(1, true, "q", null)),
                List.of(new PlanDocument.PlanRepo("a", "seed", "SEED", List.of("R1"), "why")),
                List.of(), mutableOrder, List.of(), List.of(), List.of());
        mutableOrder.clear();
        assertThat(doc.order()).hasSize(1);
        assertThat(doc.questions().get(0).resolution()).isNull();

        assertThatThrownBy(() -> new PlanDocument.PlanQuestion(1, true, null, "r"))
                .isInstanceOf(NullPointerException.class);

        PlanParseException e = new PlanParseException(7, "boom");
        assertThat(e.getMessage()).isEqualTo("line 7: boom");
        assertThat(e.line()).isEqualTo(7);
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement `PlanParseException.java` (copy `SpecParseException` verbatim with the class name changed and Javadoc "Structural plan.md error, pinned to a 1-based line number.") and `PlanDocument.java`:

```java
package sdd.plan.approve;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** The parsed Gate-1 plan.md — exactly what the human approved, structure-checked only. */
public record PlanDocument(String specId, int planVersion, String summary,
                           List<PlanQuestion> questions, List<PlanRepo> affected,
                           List<PlanExcluded> excluded, List<List<String>> order,
                           List<PlanContract> contracts, List<PlanStep> steps,
                           List<String> notes) {
    public PlanDocument {
        Objects.requireNonNull(specId);
        Objects.requireNonNull(summary);
        questions = List.copyOf(questions);
        affected = List.copyOf(affected);
        excluded = List.copyOf(excluded);
        List<List<String>> copiedOrder = new ArrayList<>();
        for (List<String> unit : order) {
            copiedOrder.add(List.copyOf(unit));
        }
        order = List.copyOf(copiedOrder);
        contracts = List.copyOf(contracts);
        steps = List.copyOf(steps);
        notes = List.copyOf(notes);
    }

    /** resolution is null when the human has not written one. */
    public record PlanQuestion(int number, boolean blocking, String text, String resolution) {
        public PlanQuestion {
            Objects.requireNonNull(text);
        }
    }

    public record PlanRepo(String repo, String role, String annotation, List<String> covers,
                           String why) {
        public PlanRepo {
            Objects.requireNonNull(repo);
            Objects.requireNonNull(role);
            Objects.requireNonNull(annotation);
            covers = List.copyOf(covers);
            Objects.requireNonNull(why);
        }
    }

    public record PlanExcluded(String repo, String detail) {
        public PlanExcluded {
            Objects.requireNonNull(repo);
            Objects.requireNonNull(detail);
        }
    }

    public record PlanContract(String id, String kind, String provider, List<String> consumers,
                               String body) {
        public PlanContract {
            Objects.requireNonNull(id);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(provider);
            consumers = List.copyOf(consumers);
            Objects.requireNonNull(body);
        }
    }

    public record PlanStep(String repo, List<String> covers, String versionAction,
                           List<String> provides, List<String> consumes, List<String> files,
                           List<String> verification, String subSpec) {
        public PlanStep {
            Objects.requireNonNull(repo);
            covers = List.copyOf(covers);
            Objects.requireNonNull(versionAction);
            provides = List.copyOf(provides);
            consumes = List.copyOf(consumes);
            files = List.copyOf(files);
            verification = List.copyOf(verification);
            Objects.requireNonNull(subSpec);
        }
    }
}
```

- [ ] **Step 3: Run — expect PASS, then commit**

```bash
./gradlew :sdd-plan:test
git add sdd-plan/src
git commit -m "feat: parsed-plan document model"
```

---

### Task 3: PlanMdParser — sections A

**Files:**
- Create: `sdd-plan/src/main/java/sdd/plan/approve/PlanMdParser.java`
- Test: `sdd-plan/src/test/java/sdd/plan/approve/PlanMdParserTest.java`

**Interfaces:**
- Produces: `public static PlanDocument parse(String markdown)` on `public final class PlanMdParser`. This task lands the skeleton + Summary/Open Questions/Generation Notes; Task 4 fills the remaining section handlers (until then they collect raw lines and produce empty lists — the TASK 3 TESTS ONLY EXERCISE SECTIONS A).
- Grammar (sections A): front matter must be exactly lines 1-4: `---`, `spec: <id>`, `plan_version: <int>`, `---` (violation → `PlanParseException` at the offending line). Sections must appear in the renderer's exact order; unknown `## ` heading → error; `### ` allowed only inside Interface Contracts and Repo Steps (Task 4). Summary = prose lines joined/stripped. Open Questions: `- none` OR lines matching `- Q(\d+)( \[blocking\])?: (.+)`, each optionally followed by `  - resolution: (.+)`; malformed bullet → error with line. Duplicate/missing sections → error. Generation Notes: `- none` or `- <text>` bullets.

- [ ] **Step 1: Write the failing tests:**

```java
package sdd.plan.approve;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanMdParserTest {

    static final String MINIMAL = """
            ---
            spec: SPEC-9
            plan_version: 2
            ---

            ## Summary
            Do the thing.

            ## Open Questions
            - Q1 [blocking]: Which method?
              - resolution: Use tierFor(String).
            - Q2: Optional nicety?

            ## Affected Repos
            - lib-core — seed/SEED — covers: R1 — why: touchpoint class:LoyaltyTier

            ## Excluded Candidates
            - none

            ## Execution Order
            1. lib-core

            ## Interface Contracts
            - none

            ## Repo Steps
            - none

            ## Generation Notes
            - drafter note
            """;

    @Test
    void parsesFrontMatterSummaryQuestionsWithResolutionsAndNotes() {
        PlanDocument doc = PlanMdParser.parse(MINIMAL);

        assertThat(doc.specId()).isEqualTo("SPEC-9");
        assertThat(doc.planVersion()).isEqualTo(2);
        assertThat(doc.summary()).isEqualTo("Do the thing.");
        assertThat(doc.questions()).containsExactly(
                new PlanDocument.PlanQuestion(1, true, "Which method?", "Use tierFor(String)."),
                new PlanDocument.PlanQuestion(2, false, "Optional nicety?", null));
        assertThat(doc.notes()).containsExactly("drafter note");
    }

    @Test
    void noneSentinelsYieldEmptyLists() {
        PlanDocument doc = PlanMdParser.parse(MINIMAL.replace("""
                - Q1 [blocking]: Which method?
                  - resolution: Use tierFor(String).
                - Q2: Optional nicety?""", "- none"));

        assertThat(doc.questions()).isEmpty();
    }

    @Test
    void resolutionOnANonBlockingQuestionIsKept() {
        PlanDocument doc = PlanMdParser.parse(MINIMAL.replace("- Q2: Optional nicety?",
                "- Q2: Optional nicety?\n  - resolution: sure."));

        assertThat(doc.questions().get(1)).isEqualTo(
                new PlanDocument.PlanQuestion(2, false, "Optional nicety?", "sure."));
    }

    @Test
    void frontMatterAndStructureViolationsFailWithLineNumbers() {
        assertThatThrownBy(() -> PlanMdParser.parse("## Summary\nX\n"))
                .isInstanceOf(PlanParseException.class)
                .hasMessageStartingWith("line 1: plan must start with '---' front matter");
        assertThatThrownBy(() -> PlanMdParser.parse(MINIMAL.replace("plan_version: 2", "version: 2")))
                .isInstanceOf(PlanParseException.class)
                .hasMessageStartingWith("line 3: expected 'plan_version: <n>'");
        assertThatThrownBy(() -> PlanMdParser.parse(MINIMAL.replace("- Q2: Optional nicety?", "* Q2 bad")))
                .isInstanceOf(PlanParseException.class)
                .hasMessageContaining("Open Questions items must look like");
        assertThatThrownBy(() -> PlanMdParser.parse(MINIMAL.replace("## Generation Notes\n- drafter note\n", "")))
                .isInstanceOf(PlanParseException.class)
                .hasMessageContaining("missing required section '## Generation Notes'");
        assertThatThrownBy(() -> PlanMdParser.parse(MINIMAL.replace("## Excluded Candidates", "## Excluded")))
                .isInstanceOf(PlanParseException.class)
                .hasMessageContaining("unknown section");
    }

    @Test
    void resolutionWithoutAQuestionFails() {
        assertThatThrownBy(() -> PlanMdParser.parse(MINIMAL.replace(
                "- Q1 [blocking]: Which method?\n  - resolution: Use tierFor(String).",
                "  - resolution: orphan")))
                .isInstanceOf(PlanParseException.class)
                .hasMessageContaining("resolution without a question");
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement `PlanMdParser.java` (complete skeleton; Task 4 replaces the four `rawSection` collectors):

```java
package sdd.plan.approve;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict parser for the Gate-1 plan.md — pins PlanMdRenderer's exact layout plus the one
 * human-only extension: "  - resolution: <text>" under a question. Structure errors carry
 * 1-based line numbers; semantics belong to PlanValidator.
 */
public final class PlanMdParser {
    static final List<String> SECTIONS = List.of("Summary", "Open Questions", "Affected Repos",
            "Excluded Candidates", "Execution Order", "Interface Contracts", "Repo Steps",
            "Generation Notes");
    private static final Pattern QUESTION = Pattern.compile("- Q(\\d+)( \\[blocking])?: (.+)");
    private static final Pattern RESOLUTION = Pattern.compile("  - resolution: (.+)");
    private static final Pattern PLAIN = Pattern.compile("- (.+)");

    private PlanMdParser() {
    }

    public static PlanDocument parse(String markdown) {
        List<String> lines = markdown.lines().toList();
        if (lines.size() < 4 || !lines.get(0).equals("---")) {
            throw new PlanParseException(1, "plan must start with '---' front matter");
        }
        if (!lines.get(1).startsWith("spec: ") || lines.get(1).length() <= 6) {
            throw new PlanParseException(2, "expected 'spec: <id>'");
        }
        String specId = lines.get(1).substring(6).strip();
        Matcher version = Pattern.compile("plan_version: (\\d+)").matcher(lines.get(2));
        if (!version.matches()) {
            throw new PlanParseException(3, "expected 'plan_version: <n>'");
        }
        int planVersion = Integer.parseInt(version.group(1));
        if (!lines.get(3).equals("---")) {
            throw new PlanParseException(4, "front matter must close with '---'");
        }

        // split into sections, enforcing renderer order
        Builder b = new Builder(specId, planVersion);
        String section = null;
        int sectionIdx = -1;
        List<String> body = new ArrayList<>();
        int bodyStart = 5;
        for (int i = 4; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNo = i + 1;
            if (line.startsWith("## ")) {
                dispatch(b, section, body, bodyStart);
                String name = line.substring(3);
                int idx = SECTIONS.indexOf(name);
                if (idx < 0) {
                    throw new PlanParseException(lineNo, "unknown section '## " + name + "'");
                }
                if (idx <= sectionIdx) {
                    throw new PlanParseException(lineNo,
                            "section '" + name + "' is duplicated or out of order");
                }
                sectionIdx = idx;
                section = name;
                body = new ArrayList<>();
                bodyStart = lineNo + 1;
                b.seen.add(name);
            } else if (section == null) {
                if (!line.isBlank()) {
                    throw new PlanParseException(lineNo, "content before the first section");
                }
            } else {
                body.add(line);
            }
        }
        dispatch(b, section, body, bodyStart);
        for (String required : SECTIONS) {
            if (!b.seen.contains(required)) {
                throw new PlanParseException(lines.size(),
                        "missing required section '## " + required + "'");
            }
        }
        return new PlanDocument(b.specId, b.planVersion, b.summary, b.questions, b.affected,
                b.excluded, b.order, b.contracts, b.steps, b.notes);
    }

    private static void dispatch(Builder b, String section, List<String> body, int startLine) {
        if (section == null) {
            return;
        }
        switch (section) {
            case "Summary" -> b.summary = String.join("\n", body).strip();
            case "Open Questions" -> questions(b, body, startLine);
            case "Generation Notes" -> b.notes = plainBullets(body, startLine, "Generation Notes");
            case "Affected Repos" -> Sections.affected(b, body, startLine);
            case "Excluded Candidates" -> Sections.excluded(b, body, startLine);
            case "Execution Order" -> Sections.order(b, body, startLine);
            case "Interface Contracts" -> Sections.contracts(b, body, startLine);
            case "Repo Steps" -> Sections.steps(b, body, startLine);
            default -> throw new IllegalStateException(section);
        }
    }

    private static void questions(Builder b, List<String> body, int startLine) {
        PlanDocument.PlanQuestion pending = null;
        for (int i = 0; i < body.size(); i++) {
            String line = body.get(i);
            int lineNo = startLine + i;
            if (line.isBlank() || line.equals("- none")) {
                continue;
            }
            Matcher resolution = RESOLUTION.matcher(line);
            if (resolution.matches()) {
                if (pending == null) {
                    throw new PlanParseException(lineNo, "resolution without a question");
                }
                b.questions.add(new PlanDocument.PlanQuestion(pending.number(),
                        pending.blocking(), pending.text(), resolution.group(1).strip()));
                pending = null;
                continue;
            }
            if (pending != null) {
                b.questions.add(pending);
                pending = null;
            }
            Matcher question = QUESTION.matcher(line);
            if (!question.matches()) {
                throw new PlanParseException(lineNo,
                        "Open Questions items must look like '- Q1 [blocking]: <text>'");
            }
            pending = new PlanDocument.PlanQuestion(Integer.parseInt(question.group(1)),
                    question.group(2) != null, question.group(3).strip(), null);
        }
        if (pending != null) {
            b.questions.add(pending);
        }
    }

    static List<String> plainBullets(List<String> body, int startLine, String section) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < body.size(); i++) {
            String line = body.get(i);
            if (line.isBlank() || line.equals("- none")) {
                continue;
            }
            Matcher m = PLAIN.matcher(line);
            if (!m.matches()) {
                throw new PlanParseException(startLine + i,
                        section + " items must look like '- <text>'");
            }
            values.add(m.group(1));
        }
        return values;
    }

    static final class Builder {
        final String specId;
        final int planVersion;
        final java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        String summary = "";
        final List<PlanDocument.PlanQuestion> questions = new ArrayList<>();
        List<PlanDocument.PlanRepo> affected = new ArrayList<>();
        List<PlanDocument.PlanExcluded> excluded = new ArrayList<>();
        List<List<String>> order = new ArrayList<>();
        List<PlanDocument.PlanContract> contracts = new ArrayList<>();
        List<PlanDocument.PlanStep> steps = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        Builder(String specId, int planVersion) {
            this.specId = specId;
            this.planVersion = planVersion;
        }
    }
}
```

Create the Task-4 placeholder holder `Sections.java` in the same package NOW so Task 3 compiles:

```java
package sdd.plan.approve;

import java.util.List;

/** Section B handlers — bodies land in Task 4; Task 3 keeps them permissive no-ops. */
final class Sections {

    private Sections() {
    }

    static void affected(PlanMdParser.Builder b, List<String> body, int startLine) {
    }

    static void excluded(PlanMdParser.Builder b, List<String> body, int startLine) {
    }

    static void order(PlanMdParser.Builder b, List<String> body, int startLine) {
    }

    static void contracts(PlanMdParser.Builder b, List<String> body, int startLine) {
    }

    static void steps(PlanMdParser.Builder b, List<String> body, int startLine) {
    }
}
```

- [ ] **Step 3: Run — expect PASS** (Task 3 tests only assert sections A; the MINIMAL fixture's Affected/Order rows are silently skipped by the no-op handlers until Task 4).
Run: `./gradlew :sdd-plan:test --tests 'sdd.plan.approve.PlanMdParserTest'`

- [ ] **Step 4: Commit**

```bash
git add sdd-plan/src
git commit -m "feat: plan.md parser skeleton with questions and resolutions"
```

---

### Task 4: PlanMdParser — sections B + renderer round trip

**Files:**
- Modify: `sdd-plan/src/main/java/sdd/plan/approve/Sections.java` (real handlers)
- Test: `sdd-plan/src/test/java/sdd/plan/approve/PlanMdParserSectionsTest.java`

**Interfaces:**
- Grammar (sections B), all errors `PlanParseException` with line numbers:
  - Affected: `- <repo> — <role>/<annotation> — covers: <csv|-> — why: <text>` (split on `" — "` into exactly 4 parts; part 2 splits on the FIRST `/`; covers `-` → empty list else comma-split).
  - Excluded: `- none` or `- <repo> — <detail>` (split on first `" — "`).
  - Execution Order: `<n>. <a>[ + <b> ...][ (co-scheduled)]` — n must equal position (1-based); strip the ` (co-scheduled)` suffix; members split on `" + "`.
  - Interface Contracts: `- none`/`- none (drafting unavailable)` or repeated blocks: `### <id> (<kind>) — <provider>[ -> <consumers csv>]` then a fenced block opened by a line exactly ```` ```yaml ```` and closed by a line exactly ```` ``` ```` — body lines verbatim between them; missing fence → error.
  - Repo Steps: `- none`/`- none (drafting unavailable)` or repeated blocks: `### <repo>`, then in EXACT order `- covers: <csv|->`, `- version_action: <v>`, `- provides: <csv|->`, `- consumes: <csv|->`, then optional `- files:` with `  - <path>` lines, optional `- verification:` with `  - <text>` lines, then everything until the next `### `/end is sub-spec prose (joined, stripped).
- Round-trip law: parsing a real `PlanMdRenderer.render` output reproduces every field the renderer serialized.

- [ ] **Step 1: Write the failing tests** — `PlanMdParserSectionsTest.java`:

```java
package sdd.plan.approve;

import org.junit.jupiter.api.Test;
import sdd.plan.gen.ExecutionOrder;
import sdd.plan.gen.PlanDrafter;
import sdd.plan.gen.PlanMdRenderer;
import sdd.plan.gen.Question;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactResult;
import sdd.plan.impact.Seed;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanMdParserSectionsTest {

    private static String rendered() {
        NormalizedSpec spec = new NormalizedSpec("SPEC-9", "T", "o", "draft", "G.", "",
                List.of(new SpecItem("R1", "req")), List.of(new SpecItem("A1", "acc")),
                List.of(), List.of(), List.of(), List.of(), List.of());
        ImpactResult impact = new ImpactResult(List.of(),
                List.of(new AffectedRepo("lib-core", "seed", "SEED", List.of("R1"),
                                List.of("touchpoint class:X", "model covers R1; owns it")),
                        new AffectedRepo("svc-a", "dependent", "CODE_CHANGE_LIKELY",
                                List.of(), List.of("depends on lib-core (PINNED)"))),
                List.of(new Seed("svc-x", "fts", "R1 hit: Y — not selected")),
                List.of("lib-core <-> svc-a"), List.of(), List.of(), List.of("a warning"));
        PlanDrafter.Draft draft = new PlanDrafter.Draft("Summary here.",
                List.of(new PlanDrafter.DraftStep("lib-core", List.of("R1"), "Do it.\nCarefully.",
                        List.of("src/A.java"), List.of("C-1"), List.of(), "minor",
                        List.of("./gradlew test"))),
                List.of(new PlanDrafter.DraftContract("C-1", "java-api", "lib-core",
                        List.of("svc-a"), "method: Tier tierFor(String)")),
                List.of(new Question("Drafted?", false)), List.of("note1"), false);
        return PlanMdRenderer.render(spec, impact,
                List.of(new ExecutionOrder.Unit(List.of("lib-core", "svc-a")),
                        new ExecutionOrder.Unit(List.of("svc-x"))),
                List.of(new Question("no repo covers R1", true)), draft);
    }

    @Test
    void roundTripsARealRenderedPlan() {
        PlanDocument doc = PlanMdParser.parse(rendered());

        assertThat(doc.specId()).isEqualTo("SPEC-9");
        assertThat(doc.summary()).isEqualTo("Summary here.");
        assertThat(doc.questions()).containsExactly(
                new PlanDocument.PlanQuestion(1, true, "no repo covers R1", null),
                new PlanDocument.PlanQuestion(2, false, "Drafted?", null));
        assertThat(doc.affected()).containsExactly(
                new PlanDocument.PlanRepo("lib-core", "seed", "SEED", List.of("R1"),
                        "touchpoint class:X; model covers R1; owns it"),
                new PlanDocument.PlanRepo("svc-a", "dependent", "CODE_CHANGE_LIKELY", List.of(),
                        "depends on lib-core (PINNED)"));
        assertThat(doc.excluded()).containsExactly(
                new PlanDocument.PlanExcluded("svc-x", "R1 hit: Y — not selected"));
        assertThat(doc.order()).containsExactly(List.of("lib-core", "svc-a"), List.of("svc-x"));
        assertThat(doc.contracts()).containsExactly(new PlanDocument.PlanContract(
                "C-1", "java-api", "lib-core", List.of("svc-a"), "method: Tier tierFor(String)"));
        assertThat(doc.steps()).containsExactly(new PlanDocument.PlanStep(
                "lib-core", List.of("R1"), "minor", List.of("C-1"), List.of(),
                List.of("src/A.java"), List.of("./gradlew test"), "Do it.\nCarefully."));
        assertThat(doc.notes()).containsExactly("note1", "a warning");
    }

    @Test
    void malformedRowsFailWithLineNumbers() {
        String plan = rendered();
        assertThatThrownBy(() -> PlanMdParser.parse(plan.replace(
                "- svc-a — dependent/CODE_CHANGE_LIKELY", "- svc-a ; dependent")))
                .isInstanceOf(PlanParseException.class)
                .hasMessageContaining("Affected Repos rows must look like");
        assertThatThrownBy(() -> PlanMdParser.parse(plan.replace("2. svc-x", "3. svc-x")))
                .isInstanceOf(PlanParseException.class)
                .hasMessageContaining("Execution Order must be numbered sequentially");
        assertThatThrownBy(() -> PlanMdParser.parse(plan.replace("```yaml", "```json")))
                .isInstanceOf(PlanParseException.class)
                .hasMessageContaining("contract body must open with '```yaml'");
        assertThatThrownBy(() -> PlanMdParser.parse(plan.replace("- version_action: minor", "")))
                .isInstanceOf(PlanParseException.class)
                .hasMessageContaining("expected '- version_action:");
    }

    @Test
    void unavailableSentinelsParseToEmptyLists() {
        String plan = rendered()
                .replaceAll("(?s)## Interface Contracts.*?## Repo Steps",
                        "## Interface Contracts\n- none (drafting unavailable)\n\n## Repo Steps")
                .replaceAll("(?s)## Repo Steps.*?## Generation Notes",
                        "## Repo Steps\n- none (drafting unavailable)\n\n## Generation Notes");

        PlanDocument doc = PlanMdParser.parse(plan);

        assertThat(doc.contracts()).isEmpty();
        assertThat(doc.steps()).isEmpty();
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (round trip returns empty lists from the no-op handlers). Implement `Sections.java` fully:

```java
package sdd.plan.approve;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Section B handlers for PlanMdParser — pin the renderer's exact row grammar. */
final class Sections {
    private static final Pattern ORDER_LINE = Pattern.compile("(\\d+)\\. (.+?)( \\(co-scheduled\\))?");
    private static final Pattern CONTRACT_HEAD =
            Pattern.compile("### (.+?) \\((java-api|rest|kafka)\\) — (\\S+)(?: -> (.+))?");
    private static final Pattern STEP_SCALAR = Pattern.compile("- (covers|version_action|provides|consumes): (.+)");

    private Sections() {
    }

    static void affected(PlanMdParser.Builder b, List<String> body, int startLine) {
        for (int i = 0; i < body.size(); i++) {
            String line = body.get(i);
            if (line.isBlank()) {
                continue;
            }
            int lineNo = startLine + i;
            if (!line.startsWith("- ")) {
                throw new PlanParseException(lineNo, "Affected Repos rows must look like "
                        + "'- <repo> — <role>/<annotation> — covers: <ids> — why: <text>'");
            }
            String[] parts = line.substring(2).split(" — ", 4);
            if (parts.length != 4 || !parts[2].startsWith("covers: ") || !parts[3].startsWith("why: ")
                    || parts[1].indexOf('/') < 0) {
                throw new PlanParseException(lineNo, "Affected Repos rows must look like "
                        + "'- <repo> — <role>/<annotation> — covers: <ids> — why: <text>'");
            }
            int slash = parts[1].indexOf('/');
            b.affected.add(new PlanDocument.PlanRepo(parts[0].strip(),
                    parts[1].substring(0, slash), parts[1].substring(slash + 1),
                    csv(parts[2].substring(8)), parts[3].substring(5)));
        }
    }

    static void excluded(PlanMdParser.Builder b, List<String> body, int startLine) {
        for (int i = 0; i < body.size(); i++) {
            String line = body.get(i);
            if (line.isBlank() || line.equals("- none")) {
                continue;
            }
            int lineNo = startLine + i;
            int sep = line.indexOf(" — ");
            if (!line.startsWith("- ") || sep < 0) {
                throw new PlanParseException(lineNo,
                        "Excluded Candidates rows must look like '- <repo> — <detail>'");
            }
            b.excluded.add(new PlanDocument.PlanExcluded(line.substring(2, sep).strip(),
                    line.substring(sep + 3)));
        }
    }

    static void order(PlanMdParser.Builder b, List<String> body, int startLine) {
        int expected = 1;
        for (int i = 0; i < body.size(); i++) {
            String line = body.get(i);
            if (line.isBlank()) {
                continue;
            }
            int lineNo = startLine + i;
            Matcher m = ORDER_LINE.matcher(line);
            if (!m.matches()) {
                throw new PlanParseException(lineNo,
                        "Execution Order lines must look like '<n>. <repo>[ + <repo>] [(co-scheduled)]'");
            }
            if (Integer.parseInt(m.group(1)) != expected) {
                throw new PlanParseException(lineNo,
                        "Execution Order must be numbered sequentially (expected " + expected + ")");
            }
            expected++;
            b.order.add(List.of(m.group(2).split(" \\+ ")));
        }
    }

    static void contracts(PlanMdParser.Builder b, List<String> body, int startLine) {
        int i = 0;
        while (i < body.size()) {
            String line = body.get(i);
            int lineNo = startLine + i;
            if (line.isBlank() || line.equals("- none") || line.equals("- none (drafting unavailable)")) {
                i++;
                continue;
            }
            Matcher head = CONTRACT_HEAD.matcher(line);
            if (!head.matches()) {
                throw new PlanParseException(lineNo, "contract headings must look like "
                        + "'### <id> (<kind>) — <provider> -> <consumers>'");
            }
            i++;
            if (i >= body.size() || !body.get(i).equals("```yaml")) {
                throw new PlanParseException(startLine + i, "contract body must open with '```yaml'");
            }
            i++;
            List<String> bodyLines = new ArrayList<>();
            while (i < body.size() && !body.get(i).equals("```")) {
                bodyLines.add(body.get(i));
                i++;
            }
            if (i >= body.size()) {
                throw new PlanParseException(startLine + i - 1, "contract body fence is never closed");
            }
            i++;
            b.contracts.add(new PlanDocument.PlanContract(head.group(1), head.group(2),
                    head.group(3), head.group(4) == null ? List.of() : csv(head.group(4)),
                    String.join("\n", bodyLines)));
        }
    }

    static void steps(PlanMdParser.Builder b, List<String> body, int startLine) {
        int i = 0;
        while (i < body.size()) {
            String line = body.get(i);
            if (line.isBlank() || line.equals("- none") || line.equals("- none (drafting unavailable)")) {
                i++;
                continue;
            }
            if (!line.startsWith("### ")) {
                throw new PlanParseException(startLine + i, "Repo Steps must start with '### <repo>'");
            }
            String repo = line.substring(4).strip();
            i++;
            String[] scalars = new String[4];   // covers, version_action, provides, consumes
            List<String> keys = List.of("covers", "version_action", "provides", "consumes");
            for (int k = 0; k < 4; k++) {
                if (i >= body.size()) {
                    throw new PlanParseException(startLine + i,
                            "expected '- " + keys.get(k) + ": <value>'");
                }
                Matcher m = STEP_SCALAR.matcher(body.get(i));
                if (!m.matches() || !m.group(1).equals(keys.get(k))) {
                    throw new PlanParseException(startLine + i,
                            "expected '- " + keys.get(k) + ": <value>'");
                }
                scalars[k] = m.group(2);
                i++;
            }
            List<String> files = new ArrayList<>();
            List<String> verification = new ArrayList<>();
            i = sublist(body, i, "- files:", files);
            i = sublist(body, i, "- verification:", verification);
            List<String> prose = new ArrayList<>();
            while (i < body.size() && !body.get(i).startsWith("### ")) {
                prose.add(body.get(i));
                i++;
            }
            b.steps.add(new PlanDocument.PlanStep(repo, csv(scalars[0]), scalars[1],
                    csv(scalars[2]), csv(scalars[3]), files, verification,
                    String.join("\n", prose).strip()));
        }
    }

    private static int sublist(List<String> body, int i, String label, List<String> out) {
        if (i < body.size() && body.get(i).equals(label)) {
            i++;
            while (i < body.size() && body.get(i).startsWith("  - ")) {
                out.add(body.get(i).substring(4));
                i++;
            }
        }
        return i;
    }

    private static List<String> csv(String value) {
        String stripped = value.strip();
        if (stripped.equals("-") || stripped.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : stripped.split(",")) {
            if (!part.strip().isEmpty()) {
                values.add(part.strip());
            }
        }
        return values;
    }
}
```

- [ ] **Step 3: Run — expect PASS** (the Task-3 MINIMAL fixture's Affected/Order rows now also parse; its assertions don't check them, which is fine).
Run: `./gradlew :sdd-plan:test`

- [ ] **Step 4: Commit**

```bash
git add sdd-plan/src
git commit -m "feat: full plan.md parsing with renderer round-trip pin"
```

---

### Task 5: LiveGit + Hashes + edges promotion

**Files:**
- Modify: `sdd-plan/build.gradle.kts` (add `implementation(libs.jgit)`)
- Create: `sdd-plan/src/main/java/sdd/plan/approve/LiveGit.java`, `Hashes.java`
- Modify: `sdd-plan/src/main/java/sdd/plan/gen/ExecutionOrder.java` (edges → `public static`)
- Test: `sdd-plan/src/test/java/sdd/plan/approve/LiveGitTest.java`, `HashesTest.java`

**Interfaces:**
- `public final class LiveGit { public record State(String head, boolean clean) {} public static State state(Path repoDir) }` — JGit calls mirror `WorkspaceScanner.scanRepo` verbatim (`Git.open`, `repo.resolve("HEAD")`, `ObjectId.name()` else `""`, `git.status().call().isClean()`); IOException/GitAPIException wrapped in `IllegalStateException("cannot read git state of " + repoDir + ": " + message)`.
- `public final class Hashes { public static String sha256(String s) }` — `RepoCardGenerator.sha256` precedent verbatim (HexFormat + MessageDigest, UTF-8).
- `ExecutionOrder.edges(Jdbi, Set<String>)` becomes `public static` (currently private; body unchanged) so `PlanValidator` can rebuild constraint edges.
- Test note: `FixtureRepo` (sdd-core testFixtures, used by IndexServiceTest) creates real git repos in a @TempDir — reuse it: `FixtureRepo.in(dir, "r1").file("a.txt", "x").commit("init")`; check its exact API in `sdd-core/src/testFixtures/java/sdd/core/testing/FixtureRepo.java` before writing the test and adapt the calls to what exists (the commit method returns the sha or the fixture — adapt assertions accordingly).

- [ ] **Step 1: Add the dependency** (`implementation(libs.jgit)` after the jsoup line in sdd-plan/build.gradle.kts), then write the failing tests:

```java
package sdd.plan.approve;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.testing.FixtureRepo;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveGitTest {
    @TempDir Path dir;

    @Test
    void readsHeadAndCleanlinessAndFlipsDirtyOnEdit() throws Exception {
        FixtureRepo.in(dir, "r1").file("a.txt", "x").commit("init");
        Path repo = dir.resolve("r1");

        LiveGit.State clean = LiveGit.state(repo);
        assertThat(clean.head()).hasSize(40);
        assertThat(clean.clean()).isTrue();

        Files.writeString(repo.resolve("a.txt"), "changed");
        LiveGit.State dirty = LiveGit.state(repo);
        assertThat(dirty.head()).isEqualTo(clean.head());
        assertThat(dirty.clean()).isFalse();
    }

    @Test
    void nonRepoFailsLoudly() {
        assertThatThrownBy(() -> LiveGit.state(dir.resolve("nope")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot read git state");
    }
}
```

```java
package sdd.plan.approve;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HashesTest {
    @Test
    void sha256IsHexAndStable() {
        assertThat(Hashes.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement:

```java
package sdd.plan.approve;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

import java.nio.file.Path;

/** Live git facts for approve-time staleness checks — mirrors WorkspaceScanner's reads. */
public final class LiveGit {

    public record State(String head, boolean clean) {
    }

    private LiveGit() {
    }

    public static State state(Path repoDir) {
        try (Git git = Git.open(repoDir.toFile())) {
            Repository repo = git.getRepository();
            ObjectId head = repo.resolve("HEAD");
            String headSha = head == null ? "" : head.name();
            boolean clean = git.status().call().isClean();
            return new State(headSha, clean);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "cannot read git state of " + repoDir + ": " + e.getMessage(), e);
        }
    }
}
```

```java
package sdd.plan.approve;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashes {

    private Hashes() {
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

Change `ExecutionOrder.edges`'s modifier from `private static` to `public static` and add one Javadoc line: `/** [provider, consumer] constraint edges among the given repos — shared with PlanValidator. */`.

- [ ] **Step 3: Run + full build (two modules' build files unaffected but jgit dep added), then commit**

```bash
./gradlew build
git add sdd-plan gradle 2>/dev/null || git add sdd-plan
git commit -m "feat: live git state, sha256 helper, shared constraint edges"
```

---

### Task 6: PlanValidator

**Files:**
- Create: `sdd-plan/src/main/java/sdd/plan/approve/PlanValidator.java`
- Test: `sdd-plan/src/test/java/sdd/plan/approve/PlanValidatorTest.java`

**Interfaces:**
- Produces: `public final class PlanValidator` with `public record Verdict(List<String> problems, List<String> warnings)` (List.copyOf) and `public static Verdict validate(Jdbi jdbi, PlanDocument plan, NormalizedSpec spec, Map<String, LiveGit.State> liveStates)` — `liveStates` keyed by repo name (the CLI builds it from KB paths; tests inject fabricated states, no git needed).
- Problem rules (exact messages):
  - `"spec id mismatch: plan says '<planId>' but the spec is '<specId>'"`
  - `"Q<n> [blocking] has no resolution"` for each blocking question with null/blank resolution.
  - `"no step covers <id>"` for each spec requirement id absent from the union of step covers.
  - `"duplicate contract id '<id>'"`; `"contract '<id>': provider '<p>' has no step providing it"` (no step with that repo listing the id in provides); `"step <repo> references undefined contract '<id>'"` (provides or consumes not in the contract set); `"step repo '<r>' is not in Affected Repos"`; `"execution order and Affected Repos disagree: <missing/extra names, sorted, comma-joined>"` (set inequality between order members and affected repos).
  - Topo legality: for each `ExecutionOrder.edges(jdbi, affectedNames)` pair `[provider, consumer]` with both in the order, `position(provider) <= position(consumer)` (same unit OK) else `"execution order violates dependency: <consumer> runs before its provider <provider>"`.
  - Staleness: for each affected repo, `liveStates` must contain an entry with `head` equal to the KB `repo.head_commit` AND `clean == true`; else `"repo <name> is stale or dirty (kb <kb8> live <live8>[, dirty]) — re-run sdd index and regenerate the plan"` where `<kb8>`/`<live8>` are the first 8 chars (or `"?"` when empty/missing).
  - `"version_action '<v>' on step <repo> is not one of none|patch|minor|major"`.
  - `"duplicate step for repo '<repo>'"` (two `### <repo>` blocks for the same repo).
  - `"contract '<id>' names consumer '<c>' that is not in Affected Repos"`.
- Warning rules:
  - `"contract '<id>' lists consumer '<c>' but <c>'s step does not consume it"` (consumer has a step lacking the id) and `"contract '<id>' lists consumer '<c>' which has no step — rebuild-only dependent?"` (affected consumer, no step).
  - Conflict detector: for each spec constraint item and each contract, tokenize both texts on `[^A-Za-z0-9/{}.-]+`, keep tokens length ≥ 4 containing `/` or `.` (path/type-like); any overlap → `"constraint <id> and contract '<cid>' both mention '<token>' — verify no conflict"` (one warning per constraint-contract pair, first overlapping token).

- [ ] **Step 1: Write the failing tests:**

```java
package sdd.plan.approve;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlanValidatorTest {
    @TempDir Path ws;
    private Database db;
    private static final String SHA_A = "a".repeat(40);
    private static final String SHA_B = "b".repeat(40);

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind, head_commit) VALUES ('lib-core','/w/1','LIBRARY','" + SHA_A + "')");
            h.execute("INSERT INTO repo(name, path, kind, head_commit) VALUES ('svc-a','/w/2','SERVICE','" + SHA_B + "')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2,':','SERVICE')");
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (2,'com.acme','lib-core','compileClasspath','1.0','DIRECT','PINNED',1,1)");
        });
    }

    private static NormalizedSpec spec() {
        return new NormalizedSpec("SPEC-9", "T", "o", "draft", "G.", "",
                List.of(new SpecItem("R1", "req"), new SpecItem("R2", "other")),
                List.of(new SpecItem("A1", "acc")),
                List.of(new SpecItem("C1", "No change to /price/{} response shape.")),
                List.of(), List.of(), List.of(), List.of());
    }

    private static PlanDocument plan(List<List<String>> order, String resolution) {
        return new PlanDocument("SPEC-9", 1, "S.",
                List.of(new PlanDocument.PlanQuestion(1, true, "q", resolution)),
                List.of(new PlanDocument.PlanRepo("lib-core", "seed", "SEED", List.of("R1"), "w"),
                        new PlanDocument.PlanRepo("svc-a", "dependent", "CODE_CHANGE_LIKELY", List.of(), "w")),
                List.of(), order,
                List.of(new PlanDocument.PlanContract("C-1", "java-api", "lib-core",
                        List.of("svc-a"), "GET /price/{} returns TierPrice")),
                List.of(new PlanDocument.PlanStep("lib-core", List.of("R1", "R2"), "minor",
                                List.of("C-1"), List.of(), List.of(), List.of(), "s"),
                        new PlanDocument.PlanStep("svc-a", List.of(), "none",
                                List.of(), List.of("C-1"), List.of(), List.of(), "s")),
                List.of());
    }

    private Map<String, LiveGit.State> freshStates() {
        return Map.of("lib-core", new LiveGit.State(SHA_A, true),
                "svc-a", new LiveGit.State(SHA_B, true));
    }

    @Test
    void cleanPlanYieldsNoProblemsButConflictWarningFires() {
        PlanValidator.Verdict verdict = PlanValidator.validate(db.jdbi(),
                plan(List.of(List.of("lib-core"), List.of("svc-a")), "resolved"),
                spec(), freshStates());

        assertThat(verdict.problems()).isEmpty();
        assertThat(verdict.warnings()).anySatisfy(w -> assertThat(w)
                .contains("constraint C1 and contract 'C-1'").contains("/price/{}"));
    }

    @Test
    void blockingResolutionCoverageTopoAndStalenessProblems() {
        PlanDocument bad = new PlanDocument("SPEC-9", 1, "S.",
                List.of(new PlanDocument.PlanQuestion(1, true, "q", null)),
                List.of(new PlanDocument.PlanRepo("lib-core", "seed", "SEED", List.of(), "w"),
                        new PlanDocument.PlanRepo("svc-a", "dependent", "X", List.of(), "w")),
                List.of(), List.of(List.of("svc-a"), List.of("lib-core")),
                List.of(),
                List.of(new PlanDocument.PlanStep("lib-core", List.of("R1"), "shipit",
                        List.of(), List.of(), List.of(), List.of(), "s"),
                        new PlanDocument.PlanStep("svc-a", List.of(), "none",
                                List.of(), List.of(), List.of(), List.of(), "s")),
                List.of());
        Map<String, LiveGit.State> stale = Map.of(
                "lib-core", new LiveGit.State("c".repeat(40), true),
                "svc-a", new LiveGit.State(SHA_B, false));

        PlanValidator.Verdict verdict = PlanValidator.validate(db.jdbi(), bad, spec(), stale);

        assertThat(verdict.problems())
                .anySatisfy(p -> assertThat(p).isEqualTo("Q1 [blocking] has no resolution"))
                .anySatisfy(p -> assertThat(p).isEqualTo("no step covers R2"))
                .anySatisfy(p -> assertThat(p).isEqualTo(
                        "execution order violates dependency: svc-a runs before its provider lib-core"))
                .anySatisfy(p -> assertThat(p).contains("repo lib-core is stale or dirty")
                        .contains("aaaaaaaa").contains("cccccccc"))
                .anySatisfy(p -> assertThat(p).contains("repo svc-a is stale or dirty").contains("dirty"))
                .anySatisfy(p -> assertThat(p).isEqualTo(
                        "version_action 'shipit' on step lib-core is not one of none|patch|minor|major"));
    }

    @Test
    void contractClosureAndSetEqualityProblems() {
        PlanDocument bad = new PlanDocument("SPEC-8", 1, "S.",
                List.of(),
                List.of(new PlanDocument.PlanRepo("lib-core", "seed", "SEED", List.of(), "w")),
                List.of(), List.of(List.of("lib-core"), List.of("ghost")),
                List.of(new PlanDocument.PlanContract("C-1", "java-api", "lib-core", List.of(), "b"),
                        new PlanDocument.PlanContract("C-1", "rest", "lib-core", List.of(), "b")),
                List.of(new PlanDocument.PlanStep("ghost", List.of("R1", "R2"), "none",
                                List.of("C-9"), List.of(), List.of(), List.of(), "s"),
                        new PlanDocument.PlanStep("ghost", List.of(), "none",
                                List.of(), List.of(), List.of(), List.of(), "s2")),
                List.of());

        PlanValidator.Verdict verdict = PlanValidator.validate(db.jdbi(), bad, spec(),
                Map.of("lib-core", new LiveGit.State(SHA_A, true)));

        assertThat(verdict.problems())
                .anySatisfy(p -> assertThat(p).isEqualTo(
                        "spec id mismatch: plan says 'SPEC-8' but the spec is 'SPEC-9'"))
                .anySatisfy(p -> assertThat(p).isEqualTo("duplicate contract id 'C-1'"))
                .anySatisfy(p -> assertThat(p).isEqualTo(
                        "contract 'C-1': provider 'lib-core' has no step providing it"))
                .anySatisfy(p -> assertThat(p).isEqualTo(
                        "step ghost references undefined contract 'C-9'"))
                .anySatisfy(p -> assertThat(p).isEqualTo("step repo 'ghost' is not in Affected Repos"))
                .anySatisfy(p -> assertThat(p).isEqualTo("duplicate step for repo 'ghost'"))
                .anySatisfy(p -> assertThat(p).contains("execution order and Affected Repos disagree")
                        .contains("ghost"));
    }

    @Test
    void contractConsumerClosureProblemsAndWarnings() {
        PlanDocument doc = new PlanDocument("SPEC-9", 1, "S.", List.of(),
                List.of(new PlanDocument.PlanRepo("lib-core", "seed", "SEED", List.of(), "w"),
                        new PlanDocument.PlanRepo("svc-a", "dependent", "BUMP_REBUILD_ONLY", List.of(), "w")),
                List.of(), List.of(List.of("lib-core"), List.of("svc-a")),
                List.of(new PlanDocument.PlanContract("C-1", "java-api", "lib-core",
                        List.of("svc-a", "nobody"), "b")),
                List.of(new PlanDocument.PlanStep("lib-core", List.of("R1", "R2"), "minor",
                        List.of("C-1"), List.of(), List.of(), List.of(), "s")),
                List.of());

        PlanValidator.Verdict verdict = PlanValidator.validate(db.jdbi(), doc, spec(), freshStates());

        assertThat(verdict.problems()).anySatisfy(p -> assertThat(p).isEqualTo(
                "contract 'C-1' names consumer 'nobody' that is not in Affected Repos"));
        assertThat(verdict.warnings()).anySatisfy(w -> assertThat(w).isEqualTo(
                "contract 'C-1' lists consumer 'svc-a' which has no step — rebuild-only dependent?"));
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement `PlanValidator.java`:

```java
package sdd.plan.approve;

import org.jdbi.v3.core.Jdbi;
import sdd.plan.gen.ExecutionOrder;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Approve-time semantics (design M5): problems block, warnings surface. Structure is
 * PlanMdParser's job; this judges the human-approved content against the spec, the KB
 * graph, and live git state.
 */
public final class PlanValidator {
    private static final Set<String> VERSION_ACTIONS = Set.of("none", "patch", "minor", "major");
    private static final Pattern TOKEN = Pattern.compile("[^A-Za-z0-9/{}.-]+");

    public record Verdict(List<String> problems, List<String> warnings) {
        public Verdict {
            problems = List.copyOf(problems);
            warnings = List.copyOf(warnings);
        }
    }

    private PlanValidator() {
    }

    public static Verdict validate(Jdbi jdbi, PlanDocument plan, NormalizedSpec spec,
                                   Map<String, LiveGit.State> liveStates) {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (!plan.specId().equals(spec.id())) {
            problems.add("spec id mismatch: plan says '" + plan.specId()
                    + "' but the spec is '" + spec.id() + "'");
        }
        for (PlanDocument.PlanQuestion question : plan.questions()) {
            if (question.blocking()
                    && (question.resolution() == null || question.resolution().isBlank())) {
                problems.add("Q" + question.number() + " [blocking] has no resolution");
            }
        }

        Set<String> covered = new LinkedHashSet<>();
        Set<String> affectedNames = new LinkedHashSet<>();
        for (PlanDocument.PlanRepo repo : plan.affected()) {
            affectedNames.add(repo.repo());
        }
        Set<String> contractIds = new LinkedHashSet<>();
        for (PlanDocument.PlanContract contract : plan.contracts()) {
            if (!contractIds.add(contract.id())) {
                problems.add("duplicate contract id '" + contract.id() + "'");
            }
        }
        Map<String, PlanDocument.PlanStep> stepByRepo = new HashMap<>();
        for (PlanDocument.PlanStep step : plan.steps()) {
            covered.addAll(step.covers());
            if (stepByRepo.put(step.repo(), step) != null) {
                problems.add("duplicate step for repo '" + step.repo() + "'");
            }
            if (!affectedNames.contains(step.repo())) {
                problems.add("step repo '" + step.repo() + "' is not in Affected Repos");
            }
            if (!VERSION_ACTIONS.contains(step.versionAction())) {
                problems.add("version_action '" + step.versionAction() + "' on step "
                        + step.repo() + " is not one of none|patch|minor|major");
            }
            for (String id : step.provides()) {
                if (!contractIds.contains(id)) {
                    problems.add("step " + step.repo() + " references undefined contract '" + id + "'");
                }
            }
            for (String id : step.consumes()) {
                if (!contractIds.contains(id)) {
                    problems.add("step " + step.repo() + " references undefined contract '" + id + "'");
                }
            }
        }
        for (SpecItem requirement : spec.requirements()) {
            if (!covered.contains(requirement.id())) {
                problems.add("no step covers " + requirement.id());
            }
        }
        for (PlanDocument.PlanContract contract : plan.contracts()) {
            PlanDocument.PlanStep providerStep = stepByRepo.get(contract.provider());
            if (providerStep == null || !providerStep.provides().contains(contract.id())) {
                problems.add("contract '" + contract.id() + "': provider '"
                        + contract.provider() + "' has no step providing it");
            }
            for (String consumer : contract.consumers()) {
                if (!affectedNames.contains(consumer)) {
                    problems.add("contract '" + contract.id() + "' names consumer '" + consumer
                            + "' that is not in Affected Repos");
                    continue;
                }
                PlanDocument.PlanStep consumerStep = stepByRepo.get(consumer);
                if (consumerStep == null) {
                    warnings.add("contract '" + contract.id() + "' lists consumer '" + consumer
                            + "' which has no step — rebuild-only dependent?");
                } else if (!consumerStep.consumes().contains(contract.id())) {
                    warnings.add("contract '" + contract.id() + "' lists consumer '" + consumer
                            + "' but " + consumer + "'s step does not consume it");
                }
            }
        }

        Map<String, Integer> position = new HashMap<>();
        Set<String> orderNames = new LinkedHashSet<>();
        for (int i = 0; i < plan.order().size(); i++) {
            for (String member : plan.order().get(i)) {
                position.put(member, i);
                orderNames.add(member);
            }
        }
        if (!orderNames.equals(affectedNames)) {
            Set<String> diff = new TreeSet<>();
            for (String name : orderNames) {
                if (!affectedNames.contains(name)) {
                    diff.add(name);
                }
            }
            for (String name : affectedNames) {
                if (!orderNames.contains(name)) {
                    diff.add(name);
                }
            }
            problems.add("execution order and Affected Repos disagree: " + String.join(", ", diff));
        }
        for (String[] edge : ExecutionOrder.edges(jdbi, affectedNames)) {
            Integer provider = position.get(edge[0]);
            Integer consumer = position.get(edge[1]);
            if (provider != null && consumer != null && provider > consumer) {
                problems.add("execution order violates dependency: " + edge[1]
                        + " runs before its provider " + edge[0]);
            }
        }

        Map<String, String> kbHeads = new HashMap<>();
        jdbi.useHandle(h -> h.createQuery("SELECT name, head_commit FROM repo").mapToMap()
                .forEach(row -> kbHeads.put(String.valueOf(row.get("name")),
                        row.get("head_commit") == null ? "" : String.valueOf(row.get("head_commit")))));
        for (String name : affectedNames) {
            String kb = kbHeads.getOrDefault(name, "");
            LiveGit.State live = liveStates.get(name);
            boolean stale = live == null || !kb.equals(live.head()) || !live.clean();
            if (stale) {
                String dirtyNote = live != null && !live.clean() ? ", dirty" : "";
                problems.add("repo " + name + " is stale or dirty (kb " + shortSha(kb)
                        + " live " + shortSha(live == null ? "" : live.head()) + dirtyNote
                        + ") — re-run sdd index and regenerate the plan");
            }
        }

        conflictWarnings(spec, plan, warnings);
        return new Verdict(problems, warnings);
    }

    private static void conflictWarnings(NormalizedSpec spec, PlanDocument plan,
                                         List<String> warnings) {
        for (SpecItem constraint : spec.constraints()) {
            Set<String> constraintTokens = tokens(constraint.text());
            for (PlanDocument.PlanContract contract : plan.contracts()) {
                for (String token : tokens(contract.body())) {
                    if (constraintTokens.contains(token)) {
                        warnings.add("constraint " + constraint.id() + " and contract '"
                                + contract.id() + "' both mention '" + token + "' — verify no conflict");
                        break;
                    }
                }
            }
        }
    }

    private static Set<String> tokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String raw : TOKEN.split(text)) {
            String token = raw.replaceAll("[.]+$", "");   // sentence-ending periods are not type dots
            if (token.length() >= 4 && (token.contains("/") || token.contains("."))) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static String shortSha(String sha) {
        return sha == null || sha.length() < 8 ? "?" : sha.substring(0, 8);
    }
}
```

- [ ] **Step 3: Run — expect PASS, then commit**

```bash
./gradlew :sdd-plan:test
git add sdd-plan/src
git commit -m "feat: approve-time plan validation with topo, closure, and staleness rules"
```

---

### Task 7: SmokeRunner seam + GradleSmokeRunner

**Files:**
- Create: `sdd-plan/src/main/java/sdd/plan/approve/SmokeRunner.java`, `GradleSmokeRunner.java`
- Test: `sdd-plan/src/test/java/sdd/plan/approve/GradleSmokeRunnerTest.java`

**Interfaces:**
- `public interface SmokeRunner { Result probe(Path consumerRepo, Path providerRepo); record Result(boolean ok, String detail) {} }`
- `public final class GradleSmokeRunner implements SmokeRunner` — ctor `GradleSmokeRunner()` (timeout 120 s) and `GradleSmokeRunner(Duration timeout)` (tests use ~5 s). Behavior: consumer `gradlew` missing → `Result(false, "no gradle wrapper in <consumer>")`; else ProcessBuilder `[./gradlew, help, --include-build, <providerRepo abs path>, --no-configuration-cache, -q]`, `directory(consumerRepo)`, `redirectErrorStream(true)`; exit 0 → ok with detail `""`; non-zero → `Result(false, "exit <code>: <last output line, or ''>")`; timeout → `destroyForcibly()` + `Result(false, "timed out after <seconds>s")`; IOException → `Result(false, e.getMessage())`. First subprocess in the codebase — keep it exactly this small.
- Tests use STUB `gradlew` shell scripts (`#!/bin/sh\nexit 0`, `exit 7` with output, and `sleep 30`) made executable — no real Gradle.

- [ ] **Step 1: Write the failing tests:**

```java
package sdd.plan.approve;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GradleSmokeRunnerTest {
    @TempDir Path dir;

    private Path consumerWith(String script) throws Exception {
        Path consumer = Files.createDirectories(dir.resolve("consumer"));
        Path gradlew = consumer.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        return consumer;
    }

    @Test
    void missingWrapperFailsWithoutRunningAnything() throws Exception {
        Path consumer = Files.createDirectories(dir.resolve("bare"));

        SmokeRunner.Result result = new GradleSmokeRunner().probe(consumer, dir);

        assertThat(result.ok()).isFalse();
        assertThat(result.detail()).contains("no gradle wrapper");
    }

    @Test
    void exitZeroIsOkNonZeroCarriesLastOutputLine() throws Exception {
        assertThat(new GradleSmokeRunner().probe(consumerWith("exit 0"), dir).ok()).isTrue();

        SmokeRunner.Result failed = new GradleSmokeRunner()
                .probe(consumerWith("echo first\necho substitution failed\nexit 7"), dir);
        assertThat(failed.ok()).isFalse();
        assertThat(failed.detail()).isEqualTo("exit 7: substitution failed");
    }

    @Test
    void hangingBuildTimesOut() throws Exception {
        SmokeRunner.Result result = new GradleSmokeRunner(Duration.ofSeconds(2))
                .probe(consumerWith("sleep 30"), dir);

        assertThat(result.ok()).isFalse();
        assertThat(result.detail()).contains("timed out after 2s");
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement:

```java
package sdd.plan.approve;

import java.nio.file.Path;

/** Approve-time probe: can this consumer build with the provider substituted via --include-build? */
public interface SmokeRunner {

    Result probe(Path consumerRepo, Path providerRepo);

    record Result(boolean ok, String detail) {
    }
}
```

```java
package sdd.plan.approve;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs `./gradlew help --include-build <provider>` in the consumer repo (design M5: the
 * propagation mechanism per edge is chosen by a LIVE smoke test). The first subprocess in
 * this codebase — deliberately minimal: inherit env, capture merged output, hard timeout.
 */
public final class GradleSmokeRunner implements SmokeRunner {
    private final Duration timeout;

    public GradleSmokeRunner() {
        this(Duration.ofSeconds(120));
    }

    public GradleSmokeRunner(Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public Result probe(Path consumerRepo, Path providerRepo) {
        Path gradlew = consumerRepo.resolve("gradlew");
        if (!Files.isExecutable(gradlew)) {
            return new Result(false, "no gradle wrapper in " + consumerRepo);
        }
        Path log = null;
        try {
            log = Files.createTempFile("sdd-smoke", ".log");
            ProcessBuilder builder = new ProcessBuilder(List.of("./gradlew", "help",
                    "--include-build", providerRepo.toAbsolutePath().toString(),
                    "--no-configuration-cache", "-q"));
            builder.directory(consumerRepo.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(log.toFile());
            Process process = builder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Result(false, "timed out after " + timeout.toSeconds() + "s");
            }
            int exit = process.exitValue();
            if (exit == 0) {
                return new Result(true, "");
            }
            String lastLine = "";
            for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    lastLine = line.strip();
                }
            }
            return new Result(false, "exit " + exit + ": " + lastLine);
        } catch (IOException e) {
            return new Result(false, String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, "interrupted");
        } finally {
            if (log != null) {
                try {
                    Files.deleteIfExists(log);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            }
        }
    }
}
```

(Output goes to a temp file, NOT a live pipe read: a hanging child that never closes its pipe would otherwise block the reader before the timeout could fire. Imports: java.io.IOException, java.nio.charset.StandardCharsets, java.nio.file.Files, java.nio.file.Path, java.time.Duration, java.util.List, java.util.concurrent.TimeUnit — no BufferedReader/InputStreamReader.)

- [ ] **Step 3: Run — expect PASS (all three tests, including the 2 s timeout), then commit**

```bash
./gradlew :sdd-plan:test
git add sdd-plan/src
git commit -m "feat: seamed include-build smoke runner with subprocess timeout"
```

---

### Task 8: PlanJson compiler

**Files:**
- Create: `sdd-plan/src/main/java/sdd/plan/approve/PlanJson.java`
- Test: `sdd-plan/src/test/java/sdd/plan/approve/PlanJsonTest.java`

**Interfaces:**
- Produces: `public final class PlanJson` with `public static String compile(Jdbi jdbi, PlanDocument plan, String specSha, String planSha, SmokeRunner smoke, List<String> warningsOut)` returning pretty-printed JSON. Snake_case record components (Jackson serializes component names as-is):
  - root: `record Root(String spec_id, int plan_version, String spec_sha256, String plan_sha256, List<Repo> repos, List<List<String>> order, List<Edge> edges, List<Contract> contracts, List<Step> steps)`
  - `record Repo(String name, String role, String annotation, String version_action, String base_sha)` — base_sha from KB `repo.head_commit`; version_action from the repo's step (`"none"` when the repo has no step); repos in plan.affected() order.
  - `record Edge(String from_repo, String to_repo, String mode, String mechanism)` — one per `v_repo_dep_edge` row with BOTH endpoints in the plan (query ordered `from_repo, to_repo, mode`); mechanism: mode `COMPOSITE` → `"NONE"` (no probe); else probe once per DISTINCT (consumer, provider) pair via the seam using KB `repo.path` values — ok → `"INCLUDE_BUILD"`, failed → `"MAVEN_LOCAL"` + `warningsOut.add("edge <from>-><to>: include-build probe failed (<detail>) — falling back to mavenLocal")`; probe results cached per pair.
  - `record Contract(String id, String kind, String provider, List<String> consumers, String body)` and `record Step(String repo, List<String> covers, String version_action, List<String> provides, List<String> consumes, List<String> files, List<String> verification, String sub_spec)` — verbatim from the document.
  - Jackson: `new ObjectMapper().writerWithDefaultPrettyPrinter()`; trailing newline appended.

- [ ] **Step 1: Write the failing test:**

```java
package sdd.plan.approve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanJsonTest {
    @TempDir Path ws;
    private Database db;
    private static final String SHA_A = "a".repeat(40);
    private static final String SHA_B = "b".repeat(40);

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind, head_commit) VALUES ('lib-core','/w/lib','LIBRARY','" + SHA_A + "')");
            h.execute("INSERT INTO repo(name, path, kind, head_commit) VALUES ('svc-a','/w/svc','SERVICE','" + SHA_B + "')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2,':','SERVICE')");
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (2,'com.acme','lib-core','compileClasspath','1.0','DIRECT','PINNED',1,1)");
        });
    }

    private static PlanDocument plan() {
        return new PlanDocument("SPEC-9", 3, "S.", List.of(),
                List.of(new PlanDocument.PlanRepo("lib-core", "seed", "SEED", List.of("R1"), "w"),
                        new PlanDocument.PlanRepo("svc-a", "dependent", "CODE_CHANGE_LIKELY", List.of(), "w")),
                List.of(), List.of(List.of("lib-core"), List.of("svc-a")),
                List.of(new PlanDocument.PlanContract("C-1", "java-api", "lib-core",
                        List.of("svc-a"), "body")),
                List.of(new PlanDocument.PlanStep("lib-core", List.of("R1"), "minor",
                        List.of("C-1"), List.of(), List.of("src/A.java"), List.of("t"), "s")),
                List.of());
    }

    @Test
    void compilesDeterministicJsonWithProbedMechanisms() throws Exception {
        List<String> warnings = new ArrayList<>();
        List<String> probed = new ArrayList<>();
        SmokeRunner ok = (consumer, provider) -> {
            probed.add(consumer + "->" + provider);
            return new SmokeRunner.Result(true, "");
        };

        String json = PlanJson.compile(db.jdbi(), plan(), "spec-sha", "plan-sha", ok, warnings);
        String again = PlanJson.compile(db.jdbi(), plan(), "spec-sha", "plan-sha", ok, new ArrayList<>());

        assertThat(json).isEqualTo(again);
        assertThat(probed).containsExactly("/w/svc->/w/lib", "/w/svc->/w/lib");
        JsonNode root = new ObjectMapper().readTree(json);
        assertThat(root.get("spec_id").asText()).isEqualTo("SPEC-9");
        assertThat(root.get("plan_version").asInt()).isEqualTo(3);
        assertThat(root.get("spec_sha256").asText()).isEqualTo("spec-sha");
        assertThat(root.get("repos").get(0).get("base_sha").asText()).isEqualTo(SHA_A);
        assertThat(root.get("repos").get(1).get("version_action").asText()).isEqualTo("none");
        assertThat(root.get("edges").get(0).get("from_repo").asText()).isEqualTo("svc-a");   // consumer
        assertThat(root.get("edges").get(0).get("to_repo").asText()).isEqualTo("lib-core");   // provider
        assertThat(root.get("edges").get(0).get("mode").asText()).isEqualTo("PINNED");
        assertThat(root.get("edges").get(0).get("mechanism").asText()).isEqualTo("INCLUDE_BUILD");
        assertThat(root.get("order").get(0).get(0).asText()).isEqualTo("lib-core");
        assertThat(warnings).isEmpty();
    }

    @Test
    void compositeEdgeGetsMechanismNoneWithoutProbing() {
        db.jdbi().useHandle(h -> h.execute(
                "INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                        + "VALUES (2,'com.acme','lib-included','compileClasspath',NULL,'DIRECT','COMPOSITE',1,1)"));
        List<String> probed = new ArrayList<>();
        SmokeRunner counting = (consumer, provider) -> {
            probed.add("hit");
            return new SmokeRunner.Result(true, "");
        };

        String json = PlanJson.compile(db.jdbi(), plan(), "s", "p", counting, new ArrayList<>());

        assertThat(json).contains("\"mechanism\" : \"NONE\"");
        assertThat(probed).hasSize(1);   // only the PINNED edge probed; COMPOSITE skipped
    }

    @Test
    void probeFailureFallsBackToMavenLocalWithWarning() {
        List<String> warnings = new ArrayList<>();
        SmokeRunner down = (consumer, provider) -> new SmokeRunner.Result(false, "exit 7: boom");

        String json = PlanJson.compile(db.jdbi(), plan(), "s", "p", down, warnings);

        assertThat(json).contains("\"mechanism\" : \"MAVEN_LOCAL\"");
        assertThat(warnings).containsExactly(
                "edge svc-a->lib-core: include-build probe failed (exit 7: boom) — falling back to mavenLocal");
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement `PlanJson.java`:

```java
package sdd.plan.approve;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles the approved plan + KB facts into the immutable run input (design M5): subgraph
 * edges with modes and LIVE-probed propagation mechanisms, base SHAs, and the SHA-256 pins
 * of both gate artifacts. Deterministic given its inputs and the probe results.
 */
public final class PlanJson {
    private static final ObjectMapper JSON = new ObjectMapper();

    record Root(String spec_id, int plan_version, String spec_sha256, String plan_sha256,
                List<Repo> repos, List<List<String>> order, List<Edge> edges,
                List<Contract> contracts, List<Step> steps) {
    }

    record Repo(String name, String role, String annotation, String version_action, String base_sha) {
    }

    record Edge(String from_repo, String to_repo, String mode, String mechanism) {
    }

    record Contract(String id, String kind, String provider, List<String> consumers, String body) {
    }

    record Step(String repo, List<String> covers, String version_action, List<String> provides,
                List<String> consumes, List<String> files, List<String> verification, String sub_spec) {
    }

    private PlanJson() {
    }

    public static String compile(Jdbi jdbi, PlanDocument plan, String specSha, String planSha,
                                 SmokeRunner smoke, List<String> warningsOut) {
        Set<String> names = new LinkedHashSet<>();
        for (PlanDocument.PlanRepo repo : plan.affected()) {
            names.add(repo.repo());
        }
        Map<String, String> heads = new HashMap<>();
        Map<String, String> paths = new HashMap<>();
        jdbi.useHandle(h -> h.createQuery("SELECT name, path, head_commit FROM repo").mapToMap()
                .forEach(row -> {
                    heads.put(String.valueOf(row.get("name")),
                            row.get("head_commit") == null ? "" : String.valueOf(row.get("head_commit")));
                    paths.put(String.valueOf(row.get("name")), String.valueOf(row.get("path")));
                }));
        Map<String, String> versionActions = new HashMap<>();
        for (PlanDocument.PlanStep step : plan.steps()) {
            versionActions.put(step.repo(), step.versionAction());
        }

        List<Repo> repos = new ArrayList<>();
        for (PlanDocument.PlanRepo repo : plan.affected()) {
            repos.add(new Repo(repo.repo(), repo.role(), repo.annotation(),
                    versionActions.getOrDefault(repo.repo(), "none"),
                    heads.getOrDefault(repo.repo(), "")));
        }

        List<Edge> edges = new ArrayList<>();
        Map<String, SmokeRunner.Result> probeCache = new HashMap<>();
        List<Map<String, Object>> rows = jdbi.withHandle(h -> h.createQuery("""
                        SELECT rf.name AS from_repo, rt.name AS to_repo, v.mode AS mode
                        FROM v_repo_dep_edge v
                        JOIN repo rf ON rf.id = v.from_repo_id
                        JOIN repo rt ON rt.id = v.to_repo_id
                        ORDER BY rf.name, rt.name, v.mode""")
                .mapToMap().list());
        for (Map<String, Object> row : rows) {
            String from = String.valueOf(row.get("from_repo"));
            String to = String.valueOf(row.get("to_repo"));
            if (!names.contains(from) || !names.contains(to)) {
                continue;
            }
            String mode = String.valueOf(row.get("mode"));
            String mechanism;
            if ("COMPOSITE".equals(mode)) {
                mechanism = "NONE";
            } else {
                String key = from + "->" + to;
                SmokeRunner.Result result = probeCache.computeIfAbsent(key, k ->
                        smoke.probe(Path.of(paths.get(from)), Path.of(paths.get(to))));
                if (result.ok()) {
                    mechanism = "INCLUDE_BUILD";
                } else {
                    mechanism = "MAVEN_LOCAL";
                    String warning = "edge " + from + "->" + to + ": include-build probe failed ("
                            + result.detail() + ") — falling back to mavenLocal";
                    if (!warningsOut.contains(warning)) {
                        warningsOut.add(warning);
                    }
                }
            }
            edges.add(new Edge(from, to, mode, mechanism));
        }

        List<Contract> contracts = new ArrayList<>();
        for (PlanDocument.PlanContract contract : plan.contracts()) {
            contracts.add(new Contract(contract.id(), contract.kind(), contract.provider(),
                    contract.consumers(), contract.body()));
        }
        List<Step> steps = new ArrayList<>();
        for (PlanDocument.PlanStep step : plan.steps()) {
            steps.add(new Step(step.repo(), step.covers(), step.versionAction(), step.provides(),
                    step.consumes(), step.files(), step.verification(), step.subSpec()));
        }
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(new Root(
                    plan.specId(), plan.planVersion(), specSha, planSha, repos, plan.order(),
                    edges, contracts, steps)) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 3: Run — expect PASS, then commit**

```bash
./gradlew :sdd-plan:test
git add sdd-plan/src
git commit -m "feat: plan.json compiler with probed propagation mechanisms"
```

---

### Task 9: ApproveCommand + nesting + e2e

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/ApproveCommand.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/PlanCommand.java` (`@Command` gains `subcommands = {ApproveCommand.class}`)
- Test: `sdd-cli/src/test/java/sdd/cli/ApproveCommandTest.java`

**Interfaces:**
- `sdd plan approve <plan.md> [--workspace <ws>]`: no config file needed; flow: read plan file (missing → error); `PlanMdParser.parse`; sibling spec = `<path with trailing ".plan.md" replaced by ".md">` (plan path not ending `.plan.md` → error `"approve expects a .plan.md file"`); parse+`SpecValidator` the spec (problems → `problem:` exit 1); KB exists check (same as PlanCommand, no-create); build `liveStates` via `LiveGit.state(Path.of(<kb repo.path>))` for each affected repo (wrap `IllegalStateException` per repo into a problem line instead of aborting); `PlanValidator.validate` → problems printed `problem: <p>` exit 1 (warnings printed `warn: <w>` first, always); on clean: `PlanJson.compile` with `GradleSmokeRunner` (or the package-private `SmokeRunner smokeForTest` seam), write `<base>.plan.json` (plain write), print each compile warning as `warn: <w>`, then `plan approved: <plan.json path>` and `spec sha256: <sha>` / `plan sha256: <sha>`, exit 0.
- Package-private seam: `SmokeRunner smokeForTest;` (mirrors plannerForTest).

- [ ] **Step 1: Write the failing e2e tests** — `ApproveCommandTest.java` (helpers mirror PlanCommandTest's `Run`/`plan()` pattern but constructing `new CommandLine(cmd)` around an `ApproveCommand`; plus one root-dispatch test through `SddCli`):

```java
package sdd.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.core.db.Database;
import sdd.core.testing.FixtureRepo;
import sdd.plan.approve.SmokeRunner;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ApproveCommandTest {
    @TempDir Path ws;

    private record Run(int exitCode, String out) {}

    private Run approve(ApproveCommand cmd, String... args) {
        StringWriter sw = new StringWriter();
        CommandLine cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(sw, true));
        cl.setErr(new PrintWriter(sw, true));
        return new Run(cl.execute(args), sw.toString());
    }

    private String seedEstateAndKb() throws Exception {
        // one real git repo so LiveGit agrees with the KB
        FixtureRepo.in(ws, "lib-core").file("a.txt", "x").commit("init");
        String sha = sdd.plan.approve.LiveGit.state(ws.resolve("lib-core")).head();
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind, head_commit) VALUES ('lib-core','"
                        + ws.resolve("lib-core") + "','LIBRARY','" + sha + "')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
            });
        }
        return sha;
    }

    private void writeSpecAndPlan(String resolution) throws Exception {
        Files.writeString(ws.resolve("loyalty.md"), """
                ---
                id: SPEC-7
                title: Loyalty tiers
                owner: ana
                status: draft
                ---

                ## Goal
                G.

                ## Requirements
                - R1: req

                ## Acceptance Criteria
                - A1: acc
                """);
        Files.writeString(ws.resolve("loyalty.plan.md"), """
                ---
                spec: SPEC-7
                plan_version: 1
                ---

                ## Summary
                S.

                ## Open Questions
                - Q1 [blocking]: which?
                %s

                ## Affected Repos
                - lib-core — seed/SEED — covers: R1 — why: w

                ## Excluded Candidates
                - none

                ## Execution Order
                1. lib-core

                ## Interface Contracts
                - none

                ## Repo Steps

                ### lib-core
                - covers: R1
                - version_action: minor
                - provides: -
                - consumes: -

                Do it.

                ## Generation Notes
                - none
                """.formatted(resolution));
    }

    @Test
    void cleanPlanApprovesAndWritesPinnedPlanJson() throws Exception {
        seedEstateAndKb();
        writeSpecAndPlan("  - resolution: use tierFor.");
        ApproveCommand cmd = new ApproveCommand();
        cmd.smokeForTest = (consumer, provider) -> new SmokeRunner.Result(true, "");

        Run run = approve(cmd, "--workspace", ws.toString(), ws.resolve("loyalty.plan.md").toString());

        assertThat(run.out()).contains("plan approved: " + ws.resolve("loyalty.plan.json"))
                .contains("spec sha256: ").contains("plan sha256: ");
        assertThat(run.exitCode()).isZero();
        String json = Files.readString(ws.resolve("loyalty.plan.json"));
        assertThat(json).contains("\"spec_id\" : \"SPEC-7\"").contains("\"base_sha\"");
    }

    @Test
    void unresolvedBlockingQuestionBlocksApproval() throws Exception {
        seedEstateAndKb();
        writeSpecAndPlan("");
        ApproveCommand cmd = new ApproveCommand();
        cmd.smokeForTest = (consumer, provider) -> new SmokeRunner.Result(true, "");

        Run run = approve(cmd, "--workspace", ws.toString(), ws.resolve("loyalty.plan.md").toString());

        assertThat(run.out()).contains("problem: Q1 [blocking] has no resolution");
        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(Files.exists(ws.resolve("loyalty.plan.json"))).isFalse();
    }

    @Test
    void dirtyRepoBlocksApproval() throws Exception {
        seedEstateAndKb();
        Files.writeString(ws.resolve("lib-core/a.txt"), "edited");
        writeSpecAndPlan("  - resolution: r.");
        ApproveCommand cmd = new ApproveCommand();
        cmd.smokeForTest = (consumer, provider) -> new SmokeRunner.Result(true, "");

        Run run = approve(cmd, "--workspace", ws.toString(), ws.resolve("loyalty.plan.md").toString());

        assertThat(run.out()).contains("problem: repo lib-core is stale or dirty");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void approveIsReachableThroughTheRootCommand() throws Exception {
        // pins picocli's subcommand-before-positional resolution for 'sdd plan approve'
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(new SddCli());
        cmd.setOut(new PrintWriter(sw, true));
        cmd.setErr(new PrintWriter(sw, true));

        int code = cmd.execute("plan", "approve", ws.resolve("missing.plan.md").toString());

        assertThat(sw.toString()).contains("error: ");
        assertThat(code).isEqualTo(1);
    }

    @Test
    void missingSpecSiblingFailsCleanly() throws Exception {
        seedEstateAndKb();
        writeSpecAndPlan("  - resolution: r.");
        Files.delete(ws.resolve("loyalty.md"));

        Run run = approve(new ApproveCommand(), "--workspace", ws.toString(),
                ws.resolve("loyalty.plan.md").toString());

        assertThat(run.out()).contains("error: ").contains("loyalty.md");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void wrongExtensionFails() throws Exception {
        Run run = approve(new ApproveCommand(), "--workspace", ws.toString(),
                ws.resolve("loyalty.md").toString());

        assertThat(run.out()).contains("error: approve expects a .plan.md file");
        assertThat(run.exitCode()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement `ApproveCommand.java`:

```java
package sdd.cli;

import org.jdbi.v3.core.Jdbi;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import sdd.core.db.Database;
import sdd.plan.approve.GradleSmokeRunner;
import sdd.plan.approve.Hashes;
import sdd.plan.approve.LiveGit;
import sdd.plan.approve.PlanDocument;
import sdd.plan.approve.PlanJson;
import sdd.plan.approve.PlanMdParser;
import sdd.plan.approve.PlanValidator;
import sdd.plan.approve.SmokeRunner;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecParser;
import sdd.plan.spec.SpecValidator;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "approve", description = "Validate the reviewed plan.md and compile plan.json (Gate 1)")
public final class ApproveCommand implements Callable<Integer> {
    @Option(names = "--workspace", description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

    @Parameters(index = "0", description = "The reviewed <spec>.plan.md")
    Path planPath;

    @Spec CommandSpec spec;

    SmokeRunner smokeForTest;   // test seam — mirrors plannerForTest

    @Override
    public Integer call() {
        PrintWriter outWriter = spec.commandLine().getOut();
        PrintWriter errWriter = spec.commandLine().getErr();
        try {
            String name = planPath.getFileName().toString();
            if (!name.endsWith(".plan.md")) {
                errWriter.println("error: approve expects a .plan.md file");
                return 1;
            }
            String planText = Files.readString(planPath);
            PlanDocument plan = PlanMdParser.parse(planText);
            Path specPath = planPath.resolveSibling(
                    name.substring(0, name.length() - ".plan.md".length()) + ".md");
            String specText = Files.readString(specPath);
            NormalizedSpec parsedSpec = SpecParser.parse(specText);
            List<String> specProblems = SpecValidator.problems(parsedSpec);
            if (!specProblems.isEmpty()) {
                for (String problem : specProblems) {
                    errWriter.println("problem: " + problem);
                }
                return 1;
            }
            if (!Files.exists(workspace.resolve(".sdd/index.db"))) {
                errWriter.println("error: knowledge base is empty — run sdd index first");
                return 1;
            }
            try (Database db = Database.open(workspace)) {
                Jdbi jdbi = db.jdbi();
                Map<String, LiveGit.State> liveStates = new HashMap<>();
                List<String> gitProblems = new ArrayList<>();
                Map<String, String> paths = new HashMap<>();
                jdbi.useHandle(h -> h.createQuery("SELECT name, path FROM repo").mapToMap()
                        .forEach(row -> paths.put(String.valueOf(row.get("name")),
                                String.valueOf(row.get("path")))));
                for (PlanDocument.PlanRepo repo : plan.affected()) {
                    String path = paths.get(repo.repo());
                    if (path == null) {
                        gitProblems.add("repo " + repo.repo() + " is not in the knowledge base");
                        continue;
                    }
                    try {
                        liveStates.put(repo.repo(), LiveGit.state(Path.of(path)));
                    } catch (IllegalStateException e) {
                        gitProblems.add(e.getMessage());
                    }
                }
                PlanValidator.Verdict verdict = PlanValidator.validate(jdbi, plan, parsedSpec, liveStates);
                for (String warning : verdict.warnings()) {
                    outWriter.println("warn: " + warning);
                }
                List<String> problems = new ArrayList<>(gitProblems);
                problems.addAll(verdict.problems());
                if (!problems.isEmpty()) {
                    for (String problem : problems) {
                        errWriter.println("problem: " + problem);
                    }
                    return 1;
                }
                SmokeRunner smoke = smokeForTest != null ? smokeForTest : new GradleSmokeRunner();
                List<String> compileWarnings = new ArrayList<>();
                String specSha = Hashes.sha256(specText);
                String planSha = Hashes.sha256(planText);
                String json = PlanJson.compile(jdbi, plan, specSha, planSha, smoke, compileWarnings);
                Path jsonPath = planPath.resolveSibling(
                        name.substring(0, name.length() - ".md".length()) + ".json");
                Files.writeString(jsonPath, json);
                for (String warning : compileWarnings) {
                    outWriter.println("warn: " + warning);
                }
                outWriter.println("plan approved: " + jsonPath);
                outWriter.println("spec sha256: " + specSha);
                outWriter.println("plan sha256: " + planSha);
                return 0;
            }
        } catch (RuntimeException | java.io.IOException e) {
            errWriter.println("error: " + e.getMessage());
            return 1;
        }
    }
}
```

Register it: `PlanCommand`'s `@Command(...)` gains `subcommands = {ApproveCommand.class}`.

- [ ] **Step 3: Run the sdd-cli suite — expect PASS** (existing PlanCommandTest tests must stay green — nesting must not break `sdd plan <ref>` positional binding).
Run: `./gradlew :sdd-cli:test`

- [ ] **Step 4: Full build, then commit**

```bash
./gradlew build
git add sdd-cli/src
git commit -m "feat: sdd plan approve validates the gate and compiles plan.json"
```

---

### Task 10: ReviseCommand

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/ReviseCommand.java`
- Modify: `sdd-plan/src/main/java/sdd/plan/gen/PlanDrafter.java` (priorQa-aware overload), `PlanMdRenderer.java` (planVersion overload), `sdd-cli/src/main/java/sdd/cli/PlanCommand.java` (register ReviseCommand)
- Test: `sdd-cli/src/test/java/sdd/cli/ReviseCommandTest.java`, `sdd-plan/src/test/java/sdd/plan/gen/PlanDrafterTest.java`

**Interfaces:**
- `PlanDrafter`: new `public static Draft draft(Jdbi, NormalizedSpec, ImpactResult, List<ExecutionOrder.Unit>, String priorQa, ChatModel, String, int)` — when `priorQa` is non-blank, `composeInput` output gains a final section `"\n# Prior questions and human resolutions\n\n" + priorQa`; the existing 7-arg overload delegates with `""`.
- `PlanMdRenderer`: new `public static String render(NormalizedSpec, ImpactResult, List<Unit>, List<Question>, PlanDrafter.Draft, int planVersion)` emitting `plan_version: <n>`; existing 5-arg overload delegates with 1.
- `sdd plan revise <plan.md> [--workspace]`: loads config (planner needed); parses the existing plan.md (for its version + Q&A); sibling spec parse+validate; KB exists + impact rerun + order + detectors + drafting with `priorQa` built as one line per prior question: `"- Q<n>[ [blocking]]: <text>" + (resolution == null ? "" : "\n  resolved: <resolution>")`; renders with `planVersion = old + 1`; `SafeWrite.writeWithBackup`; prints `plan revised (version <n>): <path>`, backup line, and the same follow-up hint as `sdd plan`. Package-private `ChatModel plannerForTest` seam (TWO scripted responses: seeding + drafting).

- [ ] **Step 1: Write the failing tests.** In `PlanDrafterTest` add:

```java
@Test
void priorQaSectionIsAppendedWhenPresent() {
    ScriptedChatModel planner = new ScriptedChatModel(List.of(response(
            "{\"summary\": \"S.\", \"questions\": [], \"contracts\": [], \"repo_steps\": []}", "stop")));

    PlanDrafter.draft(db.jdbi(), spec(), impact(), order(),
            "- Q1 [blocking]: which?\n  resolved: tierFor.", planner, "m", 256);

    assertThat(planner.requests().get(0).messages().get(1).content())
            .contains("# Prior questions and human resolutions")
            .contains("resolved: tierFor.");
}
```

`ReviseCommandTest.java`:

```java
package sdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.Usage;
import sdd.core.testing.ScriptedChatModel;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviseCommandTest {
    @TempDir Path ws;

    private record Run(int exitCode, String out) {}

    private Run revise(ReviseCommand cmd, String... args) {
        StringWriter sw = new StringWriter();
        CommandLine cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(sw, true));
        cl.setErr(new PrintWriter(sw, true));
        return new Run(cl.execute(args), sw.toString());
    }

    private String yaml() {
        return """
                models:
                  planner:
                    base_url: http://127.0.0.1:1/v1
                    model: deepseek-v4-flash
                    max_tokens: 16384
                  coder:
                    base_url: http://127.0.0.1:1/v1
                    model: qwen
                """;
    }

    @Test
    void reviseBumpsVersionFoldsQaAndBacksUpTheOldPlan() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/1','LIBRARY')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
                h.execute("INSERT INTO java_type(module_id, fqcn, kind) VALUES (1,'com.acme.LoyaltyTier','CLASS')");
            });
        }
        Files.writeString(ws.resolve("loyalty.md"), """
                ---
                id: SPEC-7
                title: Loyalty tiers
                owner: ana
                status: draft
                ---

                ## Goal
                G.

                ## Requirements
                - R1: req

                ## Acceptance Criteria
                - A1: acc

                ## Touchpoints
                - class: LoyaltyTier
                """);
        Files.writeString(ws.resolve("loyalty.plan.md"), """
                ---
                spec: SPEC-7
                plan_version: 2
                ---

                ## Summary
                Old summary.

                ## Open Questions
                - Q1 [blocking]: which method?
                  - resolution: tierFor(String).

                ## Affected Repos
                - lib-core — seed/SEED — covers: R1 — why: w

                ## Excluded Candidates
                - none

                ## Execution Order
                1. lib-core

                ## Interface Contracts
                - none

                ## Repo Steps
                - none

                ## Generation Notes
                - none
                """);
        ReviseCommand cmd = new ReviseCommand();
        cmd.plannerForTest = new ScriptedChatModel(List.of(
                new ChatResponse(ChatMessage.assistant("{\"repos\": []}"), "stop", new Usage(1, 1)),
                new ChatResponse(ChatMessage.assistant(
                        "{\"summary\": \"New summary.\", \"questions\": [], \"contracts\": [], \"repo_steps\": []}"),
                        "stop", new Usage(1, 1))));

        Run run = revise(cmd, "--workspace", ws.toString(), ws.resolve("loyalty.plan.md").toString());

        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("plan revised (version 3): " + ws.resolve("loyalty.plan.md"))
                .contains("previous version backed up: ");
        String revised = Files.readString(ws.resolve("loyalty.plan.md"));
        assertThat(revised).contains("plan_version: 3").contains("New summary.");
        assertThat(Files.readString(ws.resolve("loyalty.plan.md.bak"))).contains("Old summary.");
        // the drafter saw the prior Q&A
        ScriptedChatModel scripted = (ScriptedChatModel) cmd.plannerForTest;
        assertThat(scripted.requests().get(1).messages().get(1).content())
                .contains("# Prior questions and human resolutions")
                .contains("Q1 [blocking]: which method?")
                .contains("resolved: tierFor(String).");
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement:
  - `PlanDrafter`: rename the existing 7-arg `draft` body into the new 8-arg method inserting `priorQa` handling in `composeInput` (add a 5th `String priorQa` param to `composeInput` too, appending the section when non-blank; update the existing `composeInput` tests' call sites by passing `""`); the 7-arg overload delegates with `""`.
  - `PlanMdRenderer`: 6-arg `render` uses `planVersion` in the front matter; 5-arg delegates with 1.
  - `ReviseCommand.java`:

```java
package sdd.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import sdd.core.config.ConfigLoader;
import sdd.core.config.ModelEndpoint;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.core.llm.ChatModel;
import sdd.core.llm.HttpChatModel;
import sdd.core.retrieve.FtsRetriever;
import sdd.plan.approve.PlanDocument;
import sdd.plan.approve.PlanMdParser;
import sdd.plan.gen.ExecutionOrder;
import sdd.plan.gen.OpenQuestions;
import sdd.plan.gen.PlanDrafter;
import sdd.plan.gen.PlanMdRenderer;
import sdd.plan.gen.Question;
import sdd.plan.gen.SafeWrite;
import sdd.plan.impact.ImpactAnalysis;
import sdd.plan.impact.ImpactResult;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecParser;
import sdd.plan.spec.SpecValidator;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "revise", description = "Regenerate the plan with prior Q&A folded in, bumping plan_version")
public final class ReviseCommand implements Callable<Integer> {
    private static final int SEED_MAX_ATTEMPTS = 2;

    @Option(names = "--workspace", description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

    @Parameters(index = "0", description = "The existing <spec>.plan.md to revise")
    Path planPath;

    @Spec CommandSpec spec;

    ChatModel plannerForTest;

    @Override
    public Integer call() {
        PrintWriter outWriter = spec.commandLine().getOut();
        PrintWriter errWriter = spec.commandLine().getErr();
        try {
            String name = planPath.getFileName().toString();
            if (!name.endsWith(".plan.md")) {
                errWriter.println("error: revise expects a .plan.md file");
                return 1;
            }
            SddConfig config = ConfigLoader.load(workspace);
            PlanDocument old = PlanMdParser.parse(Files.readString(planPath));
            Path specPath = planPath.resolveSibling(
                    name.substring(0, name.length() - ".plan.md".length()) + ".md");
            NormalizedSpec parsedSpec = SpecParser.parse(Files.readString(specPath));
            List<String> specProblems = SpecValidator.problems(parsedSpec);
            if (!specProblems.isEmpty()) {
                for (String problem : specProblems) {
                    errWriter.println("problem: " + problem);
                }
                return 1;
            }
            if (!Files.exists(workspace.resolve(".sdd/index.db"))) {
                errWriter.println("error: knowledge base is empty — run sdd index first");
                return 1;
            }
            try (Database db = Database.open(workspace)) {
                ModelEndpoint planner = config.models().get("planner");
                ChatModel model = plannerForTest != null ? plannerForTest
                        : new HttpChatModel(planner, SEED_MAX_ATTEMPTS);
                ImpactResult result = ImpactAnalysis.analyze(db.jdbi(),
                        new FtsRetriever(db.jdbi()), parsedSpec, model, planner.model(),
                        planner.maxTokens());
                List<ExecutionOrder.Unit> order = ExecutionOrder.order(db.jdbi(), result);
                List<Question> questions = OpenQuestions.detect(db.jdbi(), result);
                StringBuilder priorQa = new StringBuilder();
                for (PlanDocument.PlanQuestion question : old.questions()) {
                    priorQa.append("- Q").append(question.number())
                            .append(question.blocking() ? " [blocking]: " : ": ")
                            .append(question.text()).append('\n');
                    if (question.resolution() != null && !question.resolution().isBlank()) {
                        priorQa.append("  resolved: ").append(question.resolution()).append('\n');
                    }
                }
                PlanDrafter.Draft draft = PlanDrafter.draft(db.jdbi(), parsedSpec, result, order,
                        priorQa.toString(), model, planner.model(), planner.maxTokens());
                int newVersion = old.planVersion() + 1;
                String planMd = PlanMdRenderer.render(parsedSpec, result, order, questions,
                        draft, newVersion);
                Path backup = SafeWrite.writeWithBackup(planPath, planMd);
                outWriter.println("plan revised (version " + newVersion + "): " + planPath);
                if (backup != null) {
                    outWriter.println("previous version backed up: " + backup);
                }
                outWriter.println("review and edit the plan, then run: sdd plan approve");
                return 0;
            }
        } catch (RuntimeException | java.io.IOException e) {
            errWriter.println("error: " + e.getMessage());
            return 1;
        }
    }
}
```

Register: `PlanCommand`'s `subcommands` becomes `{ApproveCommand.class, ReviseCommand.class}`.

- [ ] **Step 3: Full build — expect PASS** (PlanDrafter overload change ripples through its tests only via the `composeInput` extra `""` arg — update them in the same commit).
Run: `./gradlew build`

- [ ] **Step 4: Commit**

```bash
git add sdd-plan/src sdd-cli/src
git commit -m "feat: sdd plan revise with q&a folding and version bump"
```

---

## Verification

1. `./gradlew build` — all modules green.
2. `ApproveCommandTest.cleanPlanApprovesAndWritesPinnedPlanJson` proves the whole Gate-1 close: parse → validate → live-git staleness → probe → plan.json with SHAs; the dirty/unresolved tests prove the gates bite; the root-dispatch test pins the picocli nesting.
3. Real-estate smoke (run before merge): `sdd plan approve trading-estate/spec-tier-spreads.plan.md --workspace trading-estate` — expect blocking-question problems until the six Q's get resolutions written in; then a second run after adding resolutions approves with REAL `--include-build` probes over the five SNAPSHOT edges (each ~10-30 s) and writes `spec-tier-spreads.plan.json`.
4. Phase 4 (implement) consumes plan.json — its entry checklist lives in this plan's execution outcome.

## Self-Review (completed at write time)

1. **Spec coverage (design "plan approve" bullet, M5):** strict validation with line numbers → Tasks 3-4 (parser) + 6 (validator); "execution order is a legal topo order naming any violated edge" → Task 6 exact message; contract provider/consumer closure → Task 6; every requirement covered → Task 6; blocking questions resolved → Tasks 3 (resolution grammar) + 6; compile plan.md+KB → plan.json with subgraph edges/modes → Task 8; per-edge propagation mechanism by live `--include-build` smoke → Tasks 7-8; base SHAs → Tasks 5-6-8 (KB + live verify + pin); SHA-256 of both files → Tasks 5, 8, 9; human-edit paths: direct edits re-validated (approve IS the re-validation), `sdd plan revise` re-runs generation with Q&A appended bumping plan_version → Task 10. Entry checklist: overwrite guard → Task 1; drafting budget → Task 1 (example config; a distinct config knob deliberately deferred — recorded); duplicate-contract-id validator → Task 6; trailing arrow → Task 1; conflict detector on parsed contracts → Task 6 (warning-level token overlap — deterministic proxy, recorded).
2. **Placeholder scan:** Task 7's Step-2 explicitly replaces the flawed streaming variant with the temp-file variant and says so — implementers must implement the temp-file redirect form; no TBDs.
3. **Blank-problem producer-side check (3C-1 ledger): explicitly deferred again** — every `ImpactResult.problems()` string is a constructed literal today, the renderer renders a blank harmlessly, and no 3C-2 file produces problems; revisit if problem strings ever carry user text.
4. **Type consistency:** `PlanDocument` nested record shapes used identically in Tasks 2-4, 6, 8-10; `LiveGit.State(head, clean)` in 5, 6, 9; `SmokeRunner.Result(ok, detail)` in 7-9; `Verdict(problems, warnings)` in 6, 9; `PlanJson.compile(Jdbi, PlanDocument, String, String, SmokeRunner, List<String>)` in 8, 9; drafter/renderer overloads in 10 match their existing 3C-1 signatures with delegation.
5. **Adversarial critique pass (2 independent critics vs the real codebase, findings folded in):** Task 1's replacement scope made explicit (try/catch + following println as one unit — prevents double-printing) and the stale `(Phase 3C-2)` hint line + its pinning assertion now updated in Task 1; consumer-side contract closure completed (non-affected consumer → problem, step-less affected consumer → warning) with a dedicated test, replacing a false "covered elsewhere" claim; duplicate steps per repo now a validator problem with a test; conflict tokenizer trims sentence-ending periods; PlanJson gains edge-direction assertions and a COMPOSITE/NONE-no-probe test; approve gains a missing-spec-sibling test; parser gains a resolution-on-non-blocking round-trip pin. Critics verified: parser grammar against the real renderer line-by-line, Jackson 2.17 record serialization + exact pretty-print spacing empirically, picocli 4.7.6 subcommand-before-positional from bytecode, FixtureRepo/LiveGit/sha256 precedents, and ExecutionOrder.edges' current private modifier.
