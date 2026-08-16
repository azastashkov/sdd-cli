package sdd.cli.review;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.testing.FixtureRepo;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RemoteGit#push} against a local BARE repository created in a {@code @TempDir} — no
 * network, per the Task 5 brief's test section. A bare repo's {@code file://}-shaped path is a
 * plain filesystem transport, so none of this exercises the TLS wiring
 * {@link RemoteGit#push(Path, String, String, String, String, sdd.core.config.AtlassianTls)}'s
 * {@code tls} parameter turns on for HTTP(S) remotes (that parameter is simply passed {@code null}
 * throughout this class) — the TLS wiring itself has no independent unit test in this brief because
 * JGit's only extension point for it ({@code HttpTransport.setConnectionFactory}) is a
 * process-global static, not something a local-transport test can observe without also being a
 * network test. See the Task 5 report's "invented / least certain" section.
 */
class RemoteGitTest {
    @TempDir Path tmp;

    private Path bareRemote(String name) throws Exception {
        Path bare = tmp.resolve(name + ".git");
        Git.init().setDirectory(bare.toFile()).setBare(true).call().close();
        return bare;
    }

    @Test
    void pushCreatesTheBranchOnAFreshBareRemote() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        sdd.cli.implement.RunGit.startBranch(repo.path(), "sdd/RUN/lib", repo.headSha());
        Path remote = bareRemote("lib");

        RemoteGit.push(repo.path(), "sdd/RUN/lib", remote.toUri().toString(), "sdd", "pat", null);

        try (Git bare = Git.open(remote.toFile())) {
            assertThat(bare.getRepository().resolve("refs/heads/sdd/RUN/lib").name())
                    .isEqualTo(repo.headSha());
        }
    }

    @Test
    void pushingAgainAfterANewLocalCommitFastForwardsTheRemoteBranch() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        sdd.cli.implement.RunGit.startBranch(repo.path(), "sdd/RUN/lib", repo.headSha());
        Path remote = bareRemote("lib");
        RemoteGit.push(repo.path(), "sdd/RUN/lib", remote.toUri().toString(), "sdd", "pat", null);

        repo.file("A.java", "class A { int x; }\n").commit("second");
        RemoteGit.push(repo.path(), "sdd/RUN/lib", remote.toUri().toString(), "sdd", "pat", null);

        try (Git bare = Git.open(remote.toFile())) {
            assertThat(bare.getRepository().resolve("refs/heads/sdd/RUN/lib").name())
                    .isEqualTo(repo.headSha());
        }
    }

    @Test
    void aRedoThatResetsAndRewritesHistoryStillUpdatesTheRemoteBranch() throws Exception {
        // Force-with-lease allows a non-fast-forward push when the remote is still exactly where
        // THIS process last left it — the ordinary "redo" case: sdd starts the run branch over
        // from base and produces a different history than what it pushed last time.
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();
        sdd.cli.implement.RunGit.startBranch(repo.path(), "sdd/RUN/lib", base);
        Path remote = bareRemote("lib");
        RemoteGit.push(repo.path(), "sdd/RUN/lib", remote.toUri().toString(), "sdd", "pat", null);

        sdd.cli.implement.RunGit.resetHard(repo.path(), base);
        repo.file("A.java", "class A { int rewritten; }\n").commit("rewritten");
        RemoteGit.push(repo.path(), "sdd/RUN/lib", remote.toUri().toString(), "sdd", "pat", null);

        try (Git bare = Git.open(remote.toFile())) {
            assertThat(bare.getRepository().resolve("refs/heads/sdd/RUN/lib").name())
                    .isEqualTo(repo.headSha());
        }
    }

    @Test
    void aFreshPushSurvivesAnUnrelatedConcurrentChangeBecauseItAlwaysReadsTheLeaseJustBeforePushing() throws Exception {
        // sdd never fetches or keeps a remote-tracking branch, so RemoteGit.push always re-reads
        // the CURRENT remote sha immediately before pushing (see currentRemoteSha's javadoc) —
        // which means an ordinary call self-heals against a change that landed before it started,
        // rather than failing on one. That is a real, disclosed limit of this design (the lease
        // only protects the narrow gap between the read and the push itself); the next test proves
        // the mechanism DOES reject a push whose lease has actually gone stale, using the
        // package-private seam that lets a stale expectation be supplied deterministically instead
        // of racing for it.
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        sdd.cli.implement.RunGit.startBranch(repo.path(), "sdd/RUN/lib", repo.headSha());
        Path remote = bareRemote("lib");
        RemoteGit.push(repo.path(), "sdd/RUN/lib", remote.toUri().toString(), "sdd", "pat", null);

        pushUnrelatedCommitFromAClone(remote);
        repo.file("A.java", "class A { int mine; }\n").commit("my own unrelated change");

        RemoteGit.push(repo.path(), "sdd/RUN/lib", remote.toUri().toString(), "sdd", "pat", null);

        try (Git bare = Git.open(remote.toFile())) {
            assertThat(bare.getRepository().resolve("refs/heads/sdd/RUN/lib").name())
                    .isEqualTo(repo.headSha());
        }
    }

    @Test
    void aPushWithAKnownStaleLeaseIsRejectedRatherThanSilentlyClobberingTheRemote() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        sdd.cli.implement.RunGit.startBranch(repo.path(), "sdd/RUN/lib", repo.headSha());
        Path remote = bareRemote("lib");
        RemoteGit.push(repo.path(), "sdd/RUN/lib", remote.toUri().toString(), "sdd", "pat", null);
        String staleExpected = repo.headSha();   // what we (wrongly) still believe the remote is

        // An unrelated writer moves the remote branch forward, entirely outside RemoteGit.
        pushUnrelatedCommitFromAClone(remote);

        try (Git git = Git.open(repo.path().toFile())) {
            org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider credentials =
                    new org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider("sdd", "pat");
            assertThatThrownBy(() -> RemoteGit.pushWithExpectedLease(git, "refs/heads/sdd/RUN/lib",
                    "sdd/RUN/lib", remote.toUri().toString(), credentials, staleExpected))
                    .isInstanceOf(RuntimeException.class);
        }

        try (Git bare = Git.open(remote.toFile())) {
            // The intruder's commit must survive — the rejected push did NOT clobber it.
            assertThat(bare.getRepository().resolve("refs/heads/sdd/RUN/lib").name())
                    .isNotEqualTo(repo.headSha());
        }
    }

    /** A plain (uninitialized) destination directory, not another {@link FixtureRepo} —
     *  {@code CloneCommand} refuses a destination that already has its own {@code .git} in it. */
    private void pushUnrelatedCommitFromAClone(Path remote) throws Exception {
        Path intruderDir = tmp.resolve("intruder-clone-" + java.util.UUID.randomUUID());
        try (Git clone = Git.cloneRepository().setURI(remote.toUri().toString())
                .setDirectory(intruderDir.toFile()).setBranch("sdd/RUN/lib").call()) {
            java.nio.file.Files.writeString(intruderDir.resolve("intruder.txt"), "x");
            clone.add().addFilepattern(".").call();
            clone.commit().setMessage("unrelated change").call();
            clone.push().call();
        }
    }

    @Test
    void cloneUrlLowercasesProjectAndRepoPerTheDataCenterConvention() {
        assertThat(RemoteGit.cloneUrl("https://bitbucket.corp.local", "TRADING", "Order-Service"))
                .isEqualTo("https://bitbucket.corp.local/scm/trading/order-service.git");
    }

    @Test
    void cloneUrlStripsATrailingSlashOnTheBaseUrl() {
        assertThat(RemoteGit.cloneUrl("https://bitbucket.corp.local/", "P", "r"))
                .isEqualTo("https://bitbucket.corp.local/scm/p/r.git");
    }
}
