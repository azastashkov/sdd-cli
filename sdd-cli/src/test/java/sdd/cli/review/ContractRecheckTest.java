package sdd.cli.review;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.cli.implement.ContractActualizer;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContractRecheckTest {
    @TempDir Path ws;

    // These provider roots are deliberately plain directories, not git checkouts — most of a KB
    // repo.path's callers never require it to be one, and check() must not either (see
    // nonGitProviderPathDegradesToAFindingInsteadOfThrowing below).
    private Path libWith(String source) throws Exception {
        Path root = Files.createDirectories(ws.resolve("lib/src/main/java/com/acme"));
        Files.writeString(root.resolve("Api.java"),
                "package com.acme;\npublic class Api { " + source + " }\n");
        return ws.resolve("lib");
    }

    private static PlanModel plan(PlanModel.PlanContract contract) {
        return new PlanModel("S", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", "a")),
                List.of(List.of("lib")), List.of(), List.of(contract), List.of());
    }

    private static RunState succeeded() {
        return new RunState("S-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "sdd/S-v1/lib", "abc", "ok")), null, 0L);
    }

    @Test
    void matchingActualizationReportsMatches() throws Exception {
        Path lib = libWith("public int f(int x) { return x; }");
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        PlanModel.PlanContract c = new PlanModel.PlanContract("c1", "java-api", "lib",
                List.of("svc"), "Api.f", null, List.of());
        // record exactly what a fresh actualization produces
        store.writeContract(runDir, "c1",
                sdd.cli.implement.ContractActualizer.actualize(lib, List.of(c)).get("c1"));

        List<ContractRecheck.Finding> findings = ContractRecheck.check(plan(c), succeeded(),
                Map.of("lib", lib), store, runDir);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).status()).isEqualTo(ContractRecheck.Status.MATCHES);
    }

    @Test
    void driftedTreeReportsDrifted() throws Exception {
        Path lib = libWith("public int f(int x) { return x; }");
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        PlanModel.PlanContract c = new PlanModel.PlanContract("c1", "java-api", "lib",
                List.of("svc"), "Api.f", null, List.of());
        store.writeContract(runDir, "c1",
                sdd.cli.implement.ContractActualizer.actualize(lib, List.of(c)).get("c1"));
        // the tree changes after the run recorded its contract
        Files.writeString(lib.resolve("src/main/java/com/acme/Api.java"),
                "package com.acme;\npublic class Api { public long f(int x) { return x; } }\n");

        List<ContractRecheck.Finding> findings = ContractRecheck.check(plan(c), succeeded(),
                Map.of("lib", lib), store, runDir);

        assertThat(findings.get(0).status()).isEqualTo(ContractRecheck.Status.DRIFTED);
        assertThat(findings.get(0).detail()).contains("long");
    }

    @Test
    void missingRecordAndNonSucceededProvider() throws Exception {
        Path lib = libWith("public int f(int x) { return x; }");
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        PlanModel.PlanContract c = new PlanModel.PlanContract("c1", "java-api", "lib",
                List.of("svc"), "Api.f", null, List.of());   // nothing written to contracts/

        assertThat(ContractRecheck.check(plan(c), succeeded(), Map.of("lib", lib), store, runDir))
                .singleElement()
                .satisfies(f -> assertThat(f.status())
                        .isEqualTo(ContractRecheck.Status.MISSING_RECORD));

        RunState failed = new RunState("S-v1", List.of(
                new RepoRun("lib", RepoState.FAILED, null, null, "x")), null, 0L);
        assertThat(ContractRecheck.check(plan(c), failed, Map.of("lib", lib), store, runDir)).isEmpty();
    }

    private String hugeSource(String lastReturnType) {
        StringBuilder src = new StringBuilder("package com.acme;\npublic class Huge {\n");
        for (int i = 0; i < 200; i++) {
            String returnType = i == 199 ? lastReturnType : "String";
            String returnExpr = returnType.equals("String") ? "account" : "0";
            src.append("    public ").append(returnType).append(" method").append(i)
                    .append("(String account) { return ").append(returnExpr).append("; }\n");
        }
        src.append("}\n");
        return src.toString();
    }

    private Path hugeLib(String lastReturnType) throws Exception {
        Path root = Files.createDirectories(ws.resolve("lib/src/main/java/com/acme"));
        Files.writeString(root.resolve("Huge.java"), hugeSource(lastReturnType));
        return ws.resolve("lib");
    }

    @Test
    void driftPastTheTruncationCapReportsTruncatedMatchNotMatches() throws Exception {
        Path lib = hugeLib("String");
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        PlanModel.PlanContract c = new PlanModel.PlanContract("c1", "java-api", "lib",
                List.of("svc"), "Huge", null, List.of());
        String recorded = ContractActualizer.actualize(lib, List.of(c)).get("c1");
        assertThat(recorded).endsWith("…(truncated)");   // sanity: this fixture is over the cap
        store.writeContract(runDir, "c1", recorded);
        // change the very last method's return type — beyond the 4000-char actualization cap
        Files.writeString(lib.resolve("src/main/java/com/acme/Huge.java"), hugeSource("long"));

        List<ContractRecheck.Finding> findings = ContractRecheck.check(plan(c), succeeded(),
                Map.of("lib", lib), store, runDir);

        assertThat(findings.get(0).status()).isEqualTo(ContractRecheck.Status.TRUNCATED_MATCH);
        assertThat(findings.get(0).detail())
                .isEqualTo("bodies match up to the 4000-char actualization cap"
                        + " — drift beyond the cap cannot be detected");
    }

    private Path libWithTwoTypes() throws Exception {
        Path root = Files.createDirectories(ws.resolve("lib/src/main/java/com/acme"));
        Files.writeString(root.resolve("Alpha.java"),
                "package com.acme;\npublic class Alpha { public int a(int x) { return x; } }\n");
        Files.writeString(root.resolve("Beta.java"),
                "package com.acme;\npublic class Beta { public int b(int x) { return x; } }\n");
        return ws.resolve("lib");
    }

    @Test
    void twoContractsOnTheSameProviderBothGetCorrectFindings() throws Exception {
        Path lib = libWithTwoTypes();
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        PlanModel.PlanContract cAlpha = new PlanModel.PlanContract("cA", "java-api", "lib",
                List.of("svc"), "Alpha.a", null, List.of());
        PlanModel.PlanContract cBeta = new PlanModel.PlanContract("cB", "java-api", "lib",
                List.of("svc"), "Beta.b", null, List.of());
        store.writeContract(runDir, "cA", ContractActualizer.actualize(lib, List.of(cAlpha)).get("cA"));
        store.writeContract(runDir, "cB", ContractActualizer.actualize(lib, List.of(cBeta)).get("cB"));
        // Beta drifts after recording; Alpha does not
        Files.writeString(lib.resolve("src/main/java/com/acme/Beta.java"),
                "package com.acme;\npublic class Beta { public long b(int x) { return x; } }\n");
        PlanModel plan = new PlanModel("S", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", "a")),
                List.of(List.of("lib")), List.of(), List.of(cAlpha, cBeta), List.of());

        List<ContractRecheck.Finding> findings = ContractRecheck.check(plan, succeeded(),
                Map.of("lib", lib), store, runDir);

        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).contractId()).isEqualTo("cA");
        assertThat(findings.get(0).status()).isEqualTo(ContractRecheck.Status.MATCHES);
        assertThat(findings.get(1).contractId()).isEqualTo("cB");
        assertThat(findings.get(1).status()).isEqualTo(ContractRecheck.Status.DRIFTED);
        assertThat(findings.get(1).detail()).contains("long");
    }

    @Test
    void succeededProviderMissingFromRepoPathsGetsANotExtractableFinding() throws Exception {
        Path lib = libWith("public int f(int x) { return x; }");
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        PlanModel.PlanContract c1 = new PlanModel.PlanContract("c1", "java-api", "lib",
                List.of("svc"), "Api.f", null, List.of());
        PlanModel.PlanContract c2 = new PlanModel.PlanContract("c2", "java-api", "other",
                List.of("svc"), "Other.g", null, List.of());
        store.writeContract(runDir, "c1", ContractActualizer.actualize(lib, List.of(c1)).get("c1"));
        PlanModel plan = new PlanModel("S", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", "a"),
                        new PlanModel.PlanRepo("other", "dependent", "X", "patch", "b")),
                List.of(List.of("lib"), List.of("other")), List.of(), List.of(c1, c2), List.of());
        RunState state = new RunState("S-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "sdd/S-v1/lib", "abc", "ok"),
                new RepoRun("other", RepoState.SUCCEEDED, "sdd/S-v1/other", "def", "ok")), null, 0L);

        // "other" has no entry in repoPaths — no checkout in the knowledge base
        List<ContractRecheck.Finding> findings = ContractRecheck.check(plan, state,
                Map.of("lib", lib), store, runDir);

        assertThat(findings).hasSize(2);
        ContractRecheck.Finding otherFinding = findings.stream()
                .filter(f -> f.contractId().equals("c2")).findFirst().orElseThrow();
        assertThat(otherFinding.status()).isEqualTo(ContractRecheck.Status.NOT_EXTRACTABLE);
        assertThat(otherFinding.detail())
                .isEqualTo("provider other has no checkout path in the knowledge base");
    }

    @Test
    void nonGitProviderPathDegradesToAFindingInsteadOfThrowing() throws Exception {
        // A stale KB repo.path (deleted checkout, or a path that was simply never a git repo in
        // the first place) must degrade exactly like ContractActualizer's own extraction does for
        // an unreadable root — one benign finding, not an exception that aborts the whole review.
        Path lib = libWith("public int f(int x) { return x; }");   // a plain directory, no .git
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        PlanModel.PlanContract c = new PlanModel.PlanContract("c1", "java-api", "lib",
                List.of("svc"), "Api.f", null, List.of());

        List<ContractRecheck.Finding> findings = ContractRecheck.check(plan(c), succeeded(),
                Map.of("lib", lib), store, runDir);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).extractedFrom()).isEqualTo("unknown");
    }

    // -- conformance axis (Gate-2 plan-conformance) -------------------------------------------

    @Test
    void aContractWhoseImplementationMatchesTheDeclarationIsDeclaredMet() throws Exception {
        Path lib = libWith("public int f(int x) { return x; }");
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        PlanModel.PlanContract c = new PlanModel.PlanContract("c1", "java-api", "lib",
                List.of("svc"), "Api.f", null, List.of("com.acme.Api#f(int): int"));
        store.writeContract(runDir, "c1", ContractActualizer.actualize(lib, List.of(c)).get("c1"));

        List<ContractRecheck.Finding> findings = ContractRecheck.check(plan(c), succeeded(),
                Map.of("lib", lib), store, runDir);

        assertThat(findings.get(0).conformance()).isEqualTo(ContractRecheck.Conformance.DECLARED_MET);
        assertThat(findings.get(0).missing()).isEmpty();
    }

    private Path libWithTierResolver() throws Exception {
        Path root = Files.createDirectories(ws.resolve("lib/src/main/java/com/trading/pricing/core"));
        Files.writeString(root.resolve("TierResolver.java"), """
                package com.trading.pricing.core;
                public class TierResolver {
                    public Tier tierFor(String account) { return null; }
                }
                """);
        return ws.resolve("lib");
    }

    @Test
    void aWrongReturnTypeIsDivergedFromPlanEvenWhenItMatchesWhatTheRunRecorded() throws Exception {
        // The core case: fresh == recorded, so the drift axis says MATCHES — the implementation
        // returns Tier where the declared contract says Optional<Tier>, and it has been wrong
        // since implement time. The conformance axis must still say DIVERGED_FROM_PLAN.
        Path lib = libWithTierResolver();
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        PlanModel.PlanContract c = new PlanModel.PlanContract("c1", "java-api", "lib",
                List.of("svc"), "TierResolver.tierFor", null,
                List.of("com.trading.pricing.core.TierResolver#tierFor(String): Optional<Tier>"));
        store.writeContract(runDir, "c1", ContractActualizer.actualize(lib, List.of(c)).get("c1"));

        List<ContractRecheck.Finding> findings = ContractRecheck.check(plan(c), succeeded(),
                Map.of("lib", lib), store, runDir);

        ContractRecheck.Finding finding = findings.get(0);
        assertThat(finding.status()).isEqualTo(ContractRecheck.Status.MATCHES);
        assertThat(finding.conformance()).isEqualTo(ContractRecheck.Conformance.DIVERGED_FROM_PLAN);
        assertThat(finding.missing())
                .containsExactly("com.trading.pricing.core.TierResolver#tierFor(String):Optional<Tier>");
    }

    @Test
    void aContractWithNoDeclarationsIsNotDeclaredRatherThanMet() throws Exception {
        Path lib = libWith("public int f(int x) { return x; }");
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        PlanModel.PlanContract c = new PlanModel.PlanContract("c1", "java-api", "lib",
                List.of("svc"), "Api.f", null, List.of());   // pre-5C plan: no declared block
        store.writeContract(runDir, "c1", ContractActualizer.actualize(lib, List.of(c)).get("c1"));

        List<ContractRecheck.Finding> findings = ContractRecheck.check(plan(c), succeeded(),
                Map.of("lib", lib), store, runDir);

        assertThat(findings.get(0).conformance()).isEqualTo(ContractRecheck.Conformance.NOT_DECLARED);
    }

    @Test
    void aDeclaredBlockThatParsesToNothingIsNotDeclaredRatherThanMet() throws Exception {
        // The raw declared list is non-empty but every line is skipped by the parser, so there are
        // zero members AND zero problems: containment over an empty member list is vacuously
        // satisfied and the verdict used to be DECLARED_MET — a silent false pass indistinguishable
        // from a genuinely verified contract. Both shapes are ordinary hand-edits: the empty
        // ```contract fence a human left behind after deleting its one line (Sections.contracts
        // copies fence lines verbatim), and the TODO a human or the drafter parked there.
        Path lib = libWith("public int f(int x) { return x; }");
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        for (List<String> declared : List.of(List.of("# TODO: confirm signature"), List.of(""))) {
            PlanModel.PlanContract c = new PlanModel.PlanContract("c1", "java-api", "lib",
                    List.of("svc"), "Api.f", null, declared);
            store.writeContract(runDir, "c1", ContractActualizer.actualize(lib, List.of(c)).get("c1"));

            List<ContractRecheck.Finding> findings = ContractRecheck.check(plan(c), succeeded(),
                    Map.of("lib", lib), store, runDir);

            assertThat(findings.get(0).conformance())
                    .describedAs("declared=%s", declared)
                    .isEqualTo(ContractRecheck.Conformance.NOT_DECLARED);
            assertThat(findings.get(0).missing()).isEmpty();
        }
    }

    @Test
    void aMissingMemberBeyondTheTruncationCapIsNotComparableNotDiverged() throws Exception {
        Path lib = hugeLib("String");
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        // declares the very last method (past the 4000-char actualization cap) with a return type
        // that never appears anywhere in the actual tree
        PlanModel.PlanContract c = new PlanModel.PlanContract("c1", "java-api", "lib",
                List.of("svc"), "Huge", null,
                List.of("com.acme.Huge#method199(String): long"));
        String recorded = ContractActualizer.actualize(lib, List.of(c)).get("c1");
        assertThat(recorded).endsWith("…(truncated)");   // sanity: this fixture is over the cap
        store.writeContract(runDir, "c1", recorded);

        List<ContractRecheck.Finding> findings = ContractRecheck.check(plan(c), succeeded(),
                Map.of("lib", lib), store, runDir);

        assertThat(findings.get(0).conformance()).isEqualTo(ContractRecheck.Conformance.NOT_COMPARABLE);
        assertThat(findings.get(0).missing()).isEmpty();
    }

    @Test
    void aFindingCanBeBothDriftedAndDiverged() throws Exception {
        Path lib = libWith("public int f(int x) { return x; }");
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        PlanModel.PlanContract c = new PlanModel.PlanContract("c1", "java-api", "lib",
                List.of("svc"), "Api.f", null, List.of("com.acme.Api#f(int): int"));
        store.writeContract(runDir, "c1", ContractActualizer.actualize(lib, List.of(c)).get("c1"));
        // the tree changes after the run recorded its contract, AND diverges from the plan's
        // declared contract (int -> long) — both axes must fire independently
        Files.writeString(lib.resolve("src/main/java/com/acme/Api.java"),
                "package com.acme;\npublic class Api { public long f(int x) { return x; } }\n");

        List<ContractRecheck.Finding> findings = ContractRecheck.check(plan(c), succeeded(),
                Map.of("lib", lib), store, runDir);

        ContractRecheck.Finding finding = findings.get(0);
        assertThat(finding.status()).isEqualTo(ContractRecheck.Status.DRIFTED);
        assertThat(finding.conformance()).isEqualTo(ContractRecheck.Conformance.DIVERGED_FROM_PLAN);
        assertThat(finding.missing()).containsExactly("com.acme.Api#f(int):int");
    }

    @Test
    void aMalformedDeclaredContractIsNotComparableNotNotDeclared() throws Exception {
        // Carried finding from Task 1's review: DeclaredContract.isEmpty() only checks members, so
        // a contract whose declared lines are all malformed looks identical to one that declared
        // nothing. A hand-edited plan.json can carry this even though `sdd plan approve` blocks it.
        // Reporting NOT_DECLARED here would be a quiet lie — it must surface as NOT_COMPARABLE with
        // the grammar problem visible in the finding's detail.
        Path lib = libWith("public int f(int x) { return x; }");
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        PlanModel.PlanContract c = new PlanModel.PlanContract("c1", "java-api", "lib",
                List.of("svc"), "Api.f", null, List.of("this is not a valid declaration line"));
        store.writeContract(runDir, "c1", ContractActualizer.actualize(lib, List.of(c)).get("c1"));

        List<ContractRecheck.Finding> findings = ContractRecheck.check(plan(c), succeeded(),
                Map.of("lib", lib), store, runDir);

        ContractRecheck.Finding finding = findings.get(0);
        assertThat(finding.conformance()).isEqualTo(ContractRecheck.Conformance.NOT_COMPARABLE);
        assertThat(finding.missing()).isEmpty();
        assertThat(finding.detail()).contains("malformed java-api declaration");
    }

    // -- conformance through the REAL actualizer, per kind ------------------------------------
    //
    // Every other conformance case here is java-api. A containment check whose two sides are both
    // hand-written proves only that the grammar agrees with itself — which is exactly how the
    // kafka vocabulary mismatch (declared "consumes" vs the extractor's literal "CONSUMER")
    // survived review. These two derive the ACTUAL side from ContractActualizer.actualize against
    // a real fixture tree, so the declared grammar is pinned to what Gate 2 can genuinely extract.

    private Path libWithKafkaListener() throws Exception {
        Path root = Files.createDirectories(ws.resolve("lib/src/main/java/com/acme/svc"));
        Files.writeString(root.resolve("OrderListener.java"), """
                package com.acme.svc;
                import org.springframework.kafka.annotation.KafkaListener;
                @KafkaListener(topics = "t.orders")
                public class OrderListener {
                }
                """);
        return ws.resolve("lib");
    }

    @Test
    void aKafkaContractIsComparedAgainstWhatTheActualizerReallyEmits() throws Exception {
        Path lib = libWithKafkaListener();
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        PlanModel.PlanContract met = new PlanModel.PlanContract("c1", "kafka", "lib",
                List.of("svc"), "consumes t.orders", null, List.of("consumes t.orders"));
        store.writeContract(runDir, "c1", ContractActualizer.actualize(lib, List.of(met)).get("c1"));

        List<ContractRecheck.Finding> findings = ContractRecheck.check(plan(met), succeeded(),
                Map.of("lib", lib), store, runDir);

        assertThat(findings.get(0).status()).isEqualTo(ContractRecheck.Status.MATCHES);
        assertThat(findings.get(0).conformance()).isEqualTo(ContractRecheck.Conformance.DECLARED_MET);
        assertThat(findings.get(0).missing()).isEmpty();

        // ...and the check still has teeth: the same tree against the opposite role diverges.
        PlanModel.PlanContract wrongRole = new PlanModel.PlanContract("c1", "kafka", "lib",
                List.of("svc"), "produces t.orders", null, List.of("produces t.orders"));
        ContractRecheck.Finding diverged = ContractRecheck.check(plan(wrongRole), succeeded(),
                Map.of("lib", lib), store, runDir).get(0);
        assertThat(diverged.conformance()).isEqualTo(ContractRecheck.Conformance.DIVERGED_FROM_PLAN);
        assertThat(diverged.missing()).containsExactly("produces t.orders");
    }

    private Path libWithRestController() throws Exception {
        Path root = Files.createDirectories(ws.resolve("lib/src/main/java/com/acme/svc"));
        Files.writeString(root.resolve("SpreadController.java"), """
                package com.acme.svc;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class SpreadController {
                    @GetMapping("/admin/spreads")
                    public String spreads() { return ""; }
                }
                """);
        return ws.resolve("lib");
    }

    @Test
    void aRestContractIsComparedAgainstWhatTheActualizerReallyEmits() throws Exception {
        Path lib = libWithRestController();
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        PlanModel.PlanContract met = new PlanModel.PlanContract("c1", "rest", "lib",
                List.of("svc"), "GET /admin/spreads", null, List.of("GET /admin/spreads"));
        store.writeContract(runDir, "c1", ContractActualizer.actualize(lib, List.of(met)).get("c1"));

        List<ContractRecheck.Finding> findings = ContractRecheck.check(plan(met), succeeded(),
                Map.of("lib", lib), store, runDir);

        assertThat(findings.get(0).status()).isEqualTo(ContractRecheck.Status.MATCHES);
        assertThat(findings.get(0).conformance()).isEqualTo(ContractRecheck.Conformance.DECLARED_MET);
        assertThat(findings.get(0).missing()).isEmpty();

        // ...and the check still has teeth: the same tree against the wrong verb diverges.
        PlanModel.PlanContract wrongVerb = new PlanModel.PlanContract("c1", "rest", "lib",
                List.of("svc"), "POST /admin/spreads", null, List.of("POST /admin/spreads"));
        ContractRecheck.Finding diverged = ContractRecheck.check(plan(wrongVerb), succeeded(),
                Map.of("lib", lib), store, runDir).get(0);
        assertThat(diverged.conformance()).isEqualTo(ContractRecheck.Conformance.DIVERGED_FROM_PLAN);
        assertThat(diverged.missing()).containsExactly("POST /admin/spreads");
    }

    @Test
    void everyDeclaredTypeMissingFromExtractionIsDivergedNotNotComparable() throws Exception {
        // Extraction genuinely ran (the provider has a real checkout) but found zero matches for
        // the declared type — every declared type was renamed, moved or deleted. That is the
        // grossest divergence possible, and must read as an implementation failure the human can
        // act on, not as a tooling failure ("nothing extractable").
        Path lib = libWith("public int f(int x) { return x; }");   // real code exists, just not Ghost
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        PlanModel.PlanContract c = new PlanModel.PlanContract("c1", "java-api", "lib",
                List.of("svc"), "Ghost.foo", null, List.of("com.acme.Ghost#foo(): void"));
        // deliberately no store.writeContract — irrelevant to this scenario: the branch that fires
        // here (fresh == null) is checked before the recorded-contract lookup even matters

        List<ContractRecheck.Finding> findings = ContractRecheck.check(plan(c), succeeded(),
                Map.of("lib", lib), store, runDir);

        ContractRecheck.Finding finding = findings.get(0);
        assertThat(finding.status()).isEqualTo(ContractRecheck.Status.NOT_EXTRACTABLE);
        assertThat(finding.conformance()).isEqualTo(ContractRecheck.Conformance.DIVERGED_FROM_PLAN);
        assertThat(finding.missing()).containsExactly("com.acme.Ghost#foo():void");
    }

    // -- NOT_RESOLVED (2026-08-14 amendment: unresolved extraction is its own conformance verdict)

    private Path libWithDynamicKafkaTopic() throws Exception {
        Path root = Files.createDirectories(ws.resolve("lib/src/main/java/com/acme/svc"));
        Files.writeString(root.resolve("OrderListener.java"), """
                package com.acme.svc;
                import org.springframework.kafka.annotation.KafkaListener;
                public class OrderListener {
                    @KafkaListener(topics = "${orders.topic}")
                    public void onOrder(String order) { }
                }
                """);
        return ws.resolve("lib");   // no application.properties: "orders.topic" never resolves
    }

    @Test
    void aDynamicKafkaTopicIsNotResolvedNotDiverged() throws Exception {
        Path lib = libWithDynamicKafkaTopic();
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        PlanModel.PlanContract c = new PlanModel.PlanContract("c1", "kafka", "lib",
                List.of("svc"), "consumes orders.topic", null, List.of("consumes orders.topic"));
        String actual = ContractActualizer.actualize(lib, List.of(c)).get("c1");
        assertThat(actual).contains("[unresolved]");   // sanity: the fixture is genuinely unresolved
        store.writeContract(runDir, "c1", actual);

        List<ContractRecheck.Finding> findings = ContractRecheck.check(plan(c), succeeded(),
                Map.of("lib", lib), store, runDir);

        ContractRecheck.Finding finding = findings.get(0);
        assertThat(finding.conformance()).isEqualTo(ContractRecheck.Conformance.NOT_RESOLVED);
        assertThat(finding.missing()).isEmpty();
        assertThat(finding.unresolved()).containsExactly("consumes orders.topic");
    }

    private Path libWithKafkaTopicPatternListener() throws Exception {
        Path root = Files.createDirectories(ws.resolve("lib/src/main/java/com/acme/svc"));
        Files.writeString(root.resolve("OrderListener.java"), """
                package com.acme.svc;
                import org.springframework.kafka.annotation.KafkaListener;
                public class OrderListener {
                    @KafkaListener(topicPattern = "orders.*")
                    public void onOrder(String order) { }
                }
                """);
        return ws.resolve("lib");
    }

    @Test
    void aTopicPatternListenerThatResolvedFineStillReportsDeclaredMet() throws Exception {
        // Round-2 review fix: KafkaExtractor hardcodes resolution="DYNAMIC" for every
        // topicPattern listener regardless of whether the pattern text itself resolved (a topic
        // pattern is never a literal topic to compare for equality), so ContractActualizer marks
        // the line even though "orders.*" here is a plain literal with nothing unresolved about
        // it. That marker must not stop the line from canonicalizing normally: before the fix,
        // canonicalizeKafkaActual's parts.length == 2 guard saw three tokens on the marked line,
        // came back null, and the whole raw line (marker included) went into the actual set
        // unmatchable — a contract that reported DECLARED_MET before the marker existed regressed
        // to NOT_RESOLVED for no implementation reason at all.
        Path lib = libWithKafkaTopicPatternListener();
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        PlanModel.PlanContract c = new PlanModel.PlanContract("c1", "kafka", "lib",
                List.of("svc"), "consumes orders.*", null, List.of("consumes orders.*"));
        String actual = ContractActualizer.actualize(lib, List.of(c)).get("c1");
        assertThat(actual).contains(ContractActualizer.UNRESOLVED_MARKER);   // sanity: still marked
        store.writeContract(runDir, "c1", actual);

        List<ContractRecheck.Finding> findings = ContractRecheck.check(plan(c), succeeded(),
                Map.of("lib", lib), store, runDir);

        ContractRecheck.Finding finding = findings.get(0);
        assertThat(finding.conformance()).isEqualTo(ContractRecheck.Conformance.DECLARED_MET);
        assertThat(finding.missing()).isEmpty();
        assertThat(finding.unresolved()).isEmpty();
    }

    private Path libWithVerblessRequestMapping() throws Exception {
        Path root = Files.createDirectories(ws.resolve("lib/src/main/java/com/acme/svc"));
        Files.writeString(root.resolve("OrderController.java"), """
                package com.acme.svc;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class OrderController {
                    @RequestMapping("/orders")
                    public String orders() { return ""; }
                }
                """);
        return ws.resolve("lib");
    }

    @Test
    void aVerblessRequestMappingIsNotResolvedNotDiverged() throws Exception {
        Path lib = libWithVerblessRequestMapping();
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        PlanModel.PlanContract c = new PlanModel.PlanContract("c1", "rest", "lib",
                List.of("svc"), "GET /orders", null, List.of("GET /orders"));
        String actual = ContractActualizer.actualize(lib, List.of(c)).get("c1");
        assertThat(actual).contains("[unresolved]");   // sanity: the fixture is genuinely unresolved
        store.writeContract(runDir, "c1", actual);

        List<ContractRecheck.Finding> findings = ContractRecheck.check(plan(c), succeeded(),
                Map.of("lib", lib), store, runDir);

        ContractRecheck.Finding finding = findings.get(0);
        assertThat(finding.conformance()).isEqualTo(ContractRecheck.Conformance.NOT_RESOLVED);
        assertThat(finding.missing()).isEmpty();
        assertThat(finding.unresolved()).containsExactly("GET /orders");
    }

    @Test
    void aPreMarkerRecordedBodyStillMatchesAFreshMarkedOneOnUnchangedSource() throws Exception {
        // Round-2 review fix: a run whose recorded contracts/<id> body predates UNRESOLVED_MARKER
        // holds an unmarked line for source that has not changed since. The drift axis (Status)
        // answers "did the implementation change", not "how confident was extraction" — that is
        // the conformance axis's business — so normalize() must not let the marker itself read as
        // a change. The estate has two frozen pre-this-commit runs this exact shape applies to.
        Path lib = libWithVerblessRequestMapping();
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        PlanModel.PlanContract c = new PlanModel.PlanContract("c1", "rest", "lib",
                List.of("svc"), "GET /orders", null, List.of("GET /orders"));
        String fresh = ContractActualizer.actualize(lib, List.of(c)).get("c1");
        assertThat(fresh).contains(ContractActualizer.UNRESOLVED_MARKER);   // sanity
        // simulate a pre-marker recording of the identical interface: the marker never applied
        String preMarkerRecorded = fresh.replace(ContractActualizer.UNRESOLVED_MARKER, "");
        assertThat(preMarkerRecorded).doesNotContain(ContractActualizer.UNRESOLVED_MARKER);
        store.writeContract(runDir, "c1", preMarkerRecorded);

        List<ContractRecheck.Finding> findings = ContractRecheck.check(plan(c), succeeded(),
                Map.of("lib", lib), store, runDir);

        assertThat(findings.get(0).status()).isEqualTo(ContractRecheck.Status.MATCHES);
    }

    @Test
    void aRealDivergenceAlongsideAnUnresolvedMemberStillReportsDiverged() throws Exception {
        Path root = Files.createDirectories(ws.resolve("lib/src/main/java/com/acme/svc"));
        Files.writeString(root.resolve("OrderController.java"), """
                package com.acme.svc;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class OrderController {
                    @RequestMapping("/orders")
                    public String orders() { return ""; }
                    @GetMapping("/spreads")
                    public String spreads() { return ""; }
                }
                """);
        Path lib = ws.resolve("lib");
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        // "GET /orders" is excused by the verbless @RequestMapping on the same path; "POST
        // /spreads" is a genuine divergence — /spreads really is exposed, but as GET, not POST.
        PlanModel.PlanContract c = new PlanModel.PlanContract("c1", "rest", "lib",
                List.of("svc"), "GET /orders and POST /spreads", null,
                List.of("GET /orders", "POST /spreads"));
        store.writeContract(runDir, "c1", ContractActualizer.actualize(lib, List.of(c)).get("c1"));

        List<ContractRecheck.Finding> findings = ContractRecheck.check(plan(c), succeeded(),
                Map.of("lib", lib), store, runDir);

        ContractRecheck.Finding finding = findings.get(0);
        assertThat(finding.conformance()).isEqualTo(ContractRecheck.Conformance.DIVERGED_FROM_PLAN);
        assertThat(finding.missing()).containsExactly("POST /spreads");
        assertThat(finding.unresolved()).containsExactly("GET /orders");   // named separately
    }

    @Test
    void aFullyResolvedSurfaceMissingAMemberIsStillDiverged() throws Exception {
        // The regression guard: nothing in the actual surface is marked unresolved, so a missing
        // declared member must stay real divergence, never be swallowed into NOT_RESOLVED.
        Path lib = libWithRestController();   // GET /admin/spreads only, fully resolved
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        PlanModel.PlanContract c = new PlanModel.PlanContract("c1", "rest", "lib",
                List.of("svc"), "GET /admin/other", null, List.of("GET /admin/other"));
        store.writeContract(runDir, "c1", ContractActualizer.actualize(lib, List.of(c)).get("c1"));

        List<ContractRecheck.Finding> findings = ContractRecheck.check(plan(c), succeeded(),
                Map.of("lib", lib), store, runDir);

        ContractRecheck.Finding finding = findings.get(0);
        assertThat(finding.conformance()).isEqualTo(ContractRecheck.Conformance.DIVERGED_FROM_PLAN);
        assertThat(finding.missing()).containsExactly("GET /admin/other");
        assertThat(finding.unresolved()).isEmpty();
    }

    private Path libWithBareRootGetMapping() throws Exception {
        Path root = Files.createDirectories(ws.resolve("lib/src/main/java/com/acme/svc"));
        Files.writeString(root.resolve("RootController.java"), """
                package com.acme.svc;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class RootController {
                    @GetMapping
                    public String root() { return ""; }
                }
                """);
        return ws.resolve("lib");
    }

    @Test
    void aRootEndpointDoesNotExcuseAnUnrelatedMissingMemberOfTheSameVerb() throws Exception {
        // Pins the dropped REST shape's safe direction (task-3-report.md): pathTemplate=="/" is
        // ambiguous between a genuine bare-root @GetMapping and an unresolvable path expression,
        // and ContractActualizer deliberately marks neither as unresolved. A bare root endpoint
        // — same verb, GET — must never be mistaken by the partition rule for an excuse covering
        // some other, genuinely missing GET member. If it were, this surface would silently read
        // as NOT_RESOLVED instead of DIVERGED_FROM_PLAN.
        Path lib = libWithBareRootGetMapping();   // GET / only, fully resolved, never marked
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        PlanModel.PlanContract c = new PlanModel.PlanContract("c1", "rest", "lib",
                List.of("svc"), "GET /admin/other", null, List.of("GET /admin/other"));
        String actual = ContractActualizer.actualize(lib, List.of(c)).get("c1");
        assertThat(actual).doesNotContain(ContractActualizer.UNRESOLVED_MARKER);   // sanity
        store.writeContract(runDir, "c1", actual);

        List<ContractRecheck.Finding> findings = ContractRecheck.check(plan(c), succeeded(),
                Map.of("lib", lib), store, runDir);

        ContractRecheck.Finding finding = findings.get(0);
        assertThat(finding.conformance()).isEqualTo(ContractRecheck.Conformance.DIVERGED_FROM_PLAN);
        assertThat(finding.missing()).containsExactly("GET /admin/other");
        assertThat(finding.unresolved()).isEmpty();
    }
}
