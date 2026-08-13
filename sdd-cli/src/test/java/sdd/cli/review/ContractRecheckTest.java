package sdd.cli.review;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
                List.of("svc"), "Api.f", null);
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
                List.of("svc"), "Api.f", null);
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
                List.of("svc"), "Api.f", null);   // nothing written to contracts/

        assertThat(ContractRecheck.check(plan(c), succeeded(), Map.of("lib", lib), store, runDir))
                .singleElement()
                .satisfies(f -> assertThat(f.status())
                        .isEqualTo(ContractRecheck.Status.MISSING_RECORD));

        RunState failed = new RunState("S-v1", List.of(
                new RepoRun("lib", RepoState.FAILED, null, null, "x")), null, 0L);
        assertThat(ContractRecheck.check(plan(c), failed, Map.of("lib", lib), store, runDir)).isEmpty();
    }
}
