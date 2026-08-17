package sdd.cli.review;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.cli.implement.RunGit;
import sdd.core.diagnostics.DiagnosticWriter;
import sdd.core.testing.FixtureRepo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link BitbucketClients#push} is the shared wrapper {@link BitbucketReview}/{@link
 * BitbucketDecisions} both push through — it must log B3's "Git push outcomes" (remote host, ref,
 * whether force-with-lease held, the JGit failure message on failure) while leaving {@link
 * RemoteGit#push}'s own thrown-or-not behaviour completely unchanged, since every existing
 * best-effort catch at each call site depends on that.
 */
class BitbucketClientsPushDiagnosticsTest {
    @TempDir Path tmp;

    private Path bareRemote(String name) throws Exception {
        Path bare = tmp.resolve(name + ".git");
        Git.init().setDirectory(bare.toFile()).setBare(true).call().close();
        return bare;
    }

    private DiagnosticWriter writer(Path file) {
        return new DiagnosticWriter(file, Set.of("pat"), InstantSource.fixed(Instant.parse("2026-08-17T10:00:00Z")),
                null);
    }

    @Test
    void aSuccessfulPushLogsTheHostRefAndThatTheLeaseHeld() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        RunGit.startBranch(repo.path(), "sdd/RUN/lib", repo.headSha());
        Path remote = bareRemote("lib");
        Path file = tmp.resolve("d.log");
        DiagnosticWriter w = writer(file);

        BitbucketClients.push(w, repo.path(), "sdd/RUN/lib", remote.toUri().toString(), "x-token-auth", "pat",
                null, null);
        w.close();

        String content = Files.readString(file);
        assertThat(content).contains("git-push").contains("refs/heads/sdd/RUN/lib")
                .contains("force-with-lease-held=true").contains("OK");
    }

    @Test
    void aFailedPushLogsTheFailureMessageAndStillThrows() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        RunGit.startBranch(repo.path(), "sdd/RUN/lib", repo.headSha());
        // A remote that does not exist: the push fails before any lease is even evaluated.
        Path missingRemote = tmp.resolve("does-not-exist.git");
        Path file = tmp.resolve("d.log");
        DiagnosticWriter w = writer(file);

        assertThatThrownBy(() -> BitbucketClients.push(w, repo.path(), "sdd/RUN/lib",
                missingRemote.toUri().toString(), "x-token-auth", "pat", null, null))
                .isInstanceOf(RuntimeException.class);
        w.close();

        String content = Files.readString(file);
        assertThat(content).contains("git-push").contains("FAILED").contains("force-with-lease-held=unknown");
    }

    @Test
    void aNullDiagnosticsWriterIsANoOpAndPushBehaviorIsUnchanged() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        RunGit.startBranch(repo.path(), "sdd/RUN/lib", repo.headSha());
        Path remote = bareRemote("lib");

        BitbucketClients.push(null, repo.path(), "sdd/RUN/lib", remote.toUri().toString(), "x-token-auth", "pat",
                null, null);

        try (Git bare = Git.open(remote.toFile())) {
            assertThat(bare.getRepository().resolve("refs/heads/sdd/RUN/lib").name())
                    .isEqualTo(repo.headSha());
        }
    }
}
