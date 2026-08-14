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
}
