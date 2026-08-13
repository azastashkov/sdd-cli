package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.agent.run.RepoStep;
import sdd.core.testing.FixtureRepo;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeTest {
    @TempDir Path ws;

    private static RepoStep step(String repo, Path root) {
        return new RepoStep(repo, root, "x", List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static RunState persisted(RepoRun... repos) {
        return new RunState("S-v1", List.of(repos), "model endpoint unavailable: x", 123L);
    }

    @Test
    void verifiedSucceededReposAreKeptAndOthersReset() throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n").commit("base");
        RunGit.startBranch(lib.path(), "sdd/S-v1/lib", lib.headSha());
        String checkpoint = RunGit.commitAll(lib.path(), "checkpoint");
        RunState state = persisted(
                new RepoRun("lib", RepoState.SUCCEEDED, "sdd/S-v1/lib", checkpoint, "done"),
                new RepoRun("svc", RepoState.PAUSED_ENDPOINT, "sdd/S-v1/svc", null, "outage"),
                new RepoRun("app", RepoState.SKIPPED_UPSTREAM_FAILED, null, null, "upstream failed"));

        Resume.Prep prep = Resume.prepare(state, Map.of("lib", step("lib", lib.path())));

        assertThat(prep.problems()).isEmpty();
        assertThat(prep.state().stateOf("lib")).isEqualTo(RepoState.SUCCEEDED);
        assertThat(prep.state().stateOf("svc")).isEqualTo(RepoState.PENDING);
        assertThat(prep.state().stateOf("app")).isEqualTo(RepoState.PENDING);
        assertThat(prep.state().pausedReason()).isNull();
        assertThat(prep.state().tokensSpent()).isEqualTo(123L);
    }

    @Test
    void failedReposStayFailed() {
        RunState state = persisted(new RepoRun("lib", RepoState.FAILED, "sdd/S-v1/lib", null, "VERIFY_FAILED"));

        Resume.Prep prep = Resume.prepare(state, Map.of());

        assertThat(prep.problems()).isEmpty();
        assertThat(prep.state().stateOf("lib")).isEqualTo(RepoState.FAILED);
    }

    @Test
    void aDriftedCheckpointIsAProblem() throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n").commit("base");
        RunGit.startBranch(lib.path(), "sdd/S-v1/lib", lib.headSha());
        RunState state = persisted(
                new RepoRun("lib", RepoState.SUCCEEDED, "sdd/S-v1/lib", "0000000000000000000000000000000000000000", "done"));

        Resume.Prep prep = Resume.prepare(state, Map.of("lib", step("lib", lib.path())));

        assertThat(prep.problems()).hasSize(1);
        assertThat(prep.problems().get(0)).contains("lib").contains("checkpoint");
    }
}
