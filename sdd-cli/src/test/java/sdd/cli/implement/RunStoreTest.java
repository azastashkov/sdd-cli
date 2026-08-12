package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;

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
}
