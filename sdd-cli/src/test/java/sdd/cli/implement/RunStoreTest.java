package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunStoreTest {
    @TempDir Path ws;
    private final RunStore store = new RunStore(InstantSource.fixed(Instant.parse("2026-08-12T00:00:00Z")));

    @Test
    void createsRunDirWithImmutablePlanAndLock() throws Exception {
        Path runDir = store.create(ws, "SPEC-101-v1", "{\"plan\":true}");

        assertThat(runDir).isEqualTo(ws.resolve(".sdd/runs/SPEC-101-v1"));
        assertThat(Files.readString(runDir.resolve("plan.json"))).isEqualTo("{\"plan\":true}");
        assertThat(runDir.resolve("lock")).exists();
        assertThatThrownBy(() -> store.create(ws, "SPEC-101-v1", "x"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("in progress");
    }

    @Test
    void writesAtomicStateAndAppendsEvents() throws Exception {
        Path runDir = store.create(ws, "R", "{}");
        RunState state = new RunState("R", List.of("lib", "svc"));
        state.set("lib", RepoState.SUCCEEDED, "sdd/R/lib", "abc123", "done");
        store.writeState(runDir, state);
        store.appendEvent(runDir, "lib", RepoState.IN_PROGRESS, RepoState.SUCCEEDED, "done");

        String stateJson = Files.readString(runDir.resolve("state.json"));
        assertThat(stateJson).contains("\"runId\" : \"R\"").contains("SUCCEEDED").contains("abc123");
        String events = Files.readString(runDir.resolve("events.jsonl"));
        assertThat(events).contains("\"repo\":\"lib\"").contains("\"to\":\"SUCCEEDED\"")
                .contains("2026-08-12T00:00:00Z").endsWith("\n");
        assertThat(Files.exists(runDir.resolve("state.json.tmp"))).isFalse();
    }

    @Test
    void runStateSeedsPendingAndTracksTransitions() {
        RunState state = new RunState("R", List.of("lib", "svc"));
        assertThat(state.stateOf("lib")).isEqualTo(RepoState.PENDING);
        state.set("lib", RepoState.FAILED, "b", null, "boom");
        assertThat(state.stateOf("lib")).isEqualTo(RepoState.FAILED);
        assertThat(state.repos()).extracting(RepoRun::repo).containsExactly("lib", "svc");
    }

    @Test
    void writesPerRepoAgentEvents() throws Exception {
        Path runDir = store.create(ws, "R", "{}");
        store.writeAgentEvents(runDir, "lib", List.of("no tool call", "wedged"));
        assertThat(Files.readString(runDir.resolve("lib/agent-events.jsonl")))
                .contains("no tool call").contains("wedged");
        store.writeAgentEvents(runDir, "grp/lib", List.of("x"));
        assertThat(Files.exists(runDir.resolve("grp-lib/agent-events.jsonl"))).isTrue();
    }

    @Test
    void readStateRoundTripsThePauseFields() {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "spec body");
        RunState state = new RunState("S-v1", List.of("lib"));
        state.set("lib", RepoState.PAUSED_ENDPOINT, "sdd/S-v1/lib", null, "outage");
        state.pause("model endpoint unavailable: x");
        state.addTokens(42L);
        store.writeState(runDir, state);

        RunState read = store.readState(runDir);

        assertThat(read.runId()).isEqualTo("S-v1");
        assertThat(read.stateOf("lib")).isEqualTo(RepoState.PAUSED_ENDPOINT);
        assertThat(read.pausedReason()).isEqualTo("model endpoint unavailable: x");
        assertThat(read.tokensSpent()).isEqualTo(42L);
        assertThat(ws.resolve(".sdd/runs/S-v1/spec.md")).hasContent("spec body");
    }

    @Test
    void appendRunEventWritesARunScopedLine() throws Exception {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");

        store.appendRunEvent(runDir, "run token budget exhausted (42 tokens)");

        assertThat(Files.readString(runDir.resolve("events.jsonl")))
                .contains("\"run\":\"pause\"")
                .contains("run token budget exhausted");
    }

    @Test
    void aStaleLockFromADeadProcessIsReclaimed() throws Exception {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = Files.createDirectories(ws.resolve(".sdd/runs/S-v1"));
        Process dead = new ProcessBuilder("true").start();
        dead.waitFor();
        Files.writeString(runDir.resolve("lock"), Long.toString(dead.pid()));

        store.acquireLock(runDir);   // must NOT throw: owner is dead

        assertThat(Files.readString(runDir.resolve("lock")))
                .isEqualTo(Long.toString(ProcessHandle.current().pid()));
    }

    @Test
    void aLiveLockStillRefuses() throws Exception {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = Files.createDirectories(ws.resolve(".sdd/runs/S-v2"));
        Files.writeString(runDir.resolve("lock"), Long.toString(ProcessHandle.current().pid()));

        assertThatThrownBy(() -> store.acquireLock(runDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already in progress");
    }

    @Test
    void propagationSnapshotRoundTrips() {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v3", "{}", "");
        Map<String, RepoPropagation> map = Map.of(
                "svc", new RepoPropagation(
                        List.of(new RepoPropagation.BumpEdit("com.acme", "lib", "1.2.3", "1.3.0")), null),
                "lib", new RepoPropagation(List.of(),
                        new RepoPropagation.PublishSpec("1.3.0", runDir.resolve("m2"))));

        store.writePropagation(runDir, map);
        Map<String, RepoPropagation> read = store.readPropagation(runDir);

        assertThat(read).isEqualTo(map);
        assertThat(store.readPropagation(ws.resolve("nowhere"))).isNull();
    }

    @Test
    void contractFilesRoundTripUnderTheContractsDir() {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");

        store.writeContract(runDir, "c1/api", "actual body");

        assertThat(runDir.resolve("contracts/c1-api.md")).exists();
        assertThat(store.readContract(runDir, "c1/api")).isEqualTo("actual body");
        assertThat(store.readContract(runDir, "ghost")).isNull();
    }
}
