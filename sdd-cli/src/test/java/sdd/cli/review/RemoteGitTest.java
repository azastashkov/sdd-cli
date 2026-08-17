package sdd.cli.review;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.config.AtlassianProxy;
import sdd.core.http.HttpClients;
import sdd.core.testing.FixtureRepo;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RemoteGit#push} against a local BARE repository created in a {@code @TempDir} — no
 * network, per the Task 5 brief's test section. A bare repo's {@code file://}-shaped path is a
 * plain filesystem transport, so none of {@code push}'s own tests below exercise the TLS/proxy
 * wiring {@link RemoteGit#push}'s {@code tls}/{@code proxy} parameters turn on for HTTP(S) remotes
 * (both are simply passed {@code null} throughout this class's push tests) — {@code
 * installConnectionFactory} itself has no independent test of ITS installation, since JGit's only
 * extension point for it ({@code HttpTransport.setConnectionFactory}) is a process-global static,
 * not something a local-transport test can observe without also being a network test. What IS
 * covered directly, without needing a network: {@link RemoteGit#resolveProxy}, the proxy-selection
 * logic that installation wires in — see the {@code resolveProxy*} tests below. See the Task 5
 * report's "invented / least certain" section for what remains unverified against a live instance.
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

        RemoteGit.push(repo.path(), "sdd/RUN/lib", remote.toUri().toString(), "sdd", "pat", null, null);

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
        RemoteGit.push(repo.path(), "sdd/RUN/lib", remote.toUri().toString(), "sdd", "pat", null, null);

        repo.file("A.java", "class A { int x; }\n").commit("second");
        RemoteGit.push(repo.path(), "sdd/RUN/lib", remote.toUri().toString(), "sdd", "pat", null, null);

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
        RemoteGit.push(repo.path(), "sdd/RUN/lib", remote.toUri().toString(), "sdd", "pat", null, null);

        sdd.cli.implement.RunGit.resetHard(repo.path(), base);
        repo.file("A.java", "class A { int rewritten; }\n").commit("rewritten");
        RemoteGit.push(repo.path(), "sdd/RUN/lib", remote.toUri().toString(), "sdd", "pat", null, null);

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
        RemoteGit.push(repo.path(), "sdd/RUN/lib", remote.toUri().toString(), "sdd", "pat", null, null);

        pushUnrelatedCommitFromAClone(remote);
        repo.file("A.java", "class A { int mine; }\n").commit("my own unrelated change");

        RemoteGit.push(repo.path(), "sdd/RUN/lib", remote.toUri().toString(), "sdd", "pat", null, null);

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
        RemoteGit.push(repo.path(), "sdd/RUN/lib", remote.toUri().toString(), "sdd", "pat", null, null);
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

    // --- resolveProxy (the proxy half of installConnectionFactory) ----------------------------

    @Test
    void resolveProxyPassesTheFallbackThroughUnchangedWhenNoSelectorIsConfigured() throws Exception {
        // No atlassian.proxy configured: JGit's own negotiated proxy (or a direct connection) must
        // survive untouched, never silently overridden by some other proxy decision.
        Proxy negotiated = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("other-proxy", 3128));

        assertThat(RemoteGit.resolveProxy(null, URI.create("https://bitbucket.corp.local/x").toURL(), negotiated))
                .isSameAs(negotiated);
    }

    @Test
    void resolveProxyRoutesThroughTheConfiguredProxyForAnOrdinaryHost() throws Exception {
        ProxySelector selector = HttpClients.proxySelector(
                new AtlassianProxy("proxy.corp.local", 8080, List.of("no.proxy.corp.local")));

        Proxy resolved = RemoteGit.resolveProxy(selector,
                URI.create("https://bitbucket.corp.local/scm/p/r.git").toURL(), Proxy.NO_PROXY);

        assertThat(resolved.address()).isEqualTo(InetSocketAddress.createUnresolved("proxy.corp.local", 8080));
    }

    @Test
    void resolveProxyBypassesTheConfiguredProxyForANoProxyHost() throws Exception {
        // Same no_proxy bypass HttpClientsTest already pins for the REST client — RemoteGit reuses
        // HttpClients.proxySelector directly, so a Bitbucket host listed in no_proxy connects
        // directly for the push too, exactly like it would for the REST calls.
        ProxySelector selector = HttpClients.proxySelector(
                new AtlassianProxy("proxy.corp.local", 8080, List.of("bitbucket.corp.local")));

        Proxy resolved = RemoteGit.resolveProxy(selector,
                URI.create("https://bitbucket.corp.local/scm/p/r.git").toURL(), Proxy.NO_PROXY);

        assertThat(resolved).isEqualTo(Proxy.NO_PROXY);
    }

    @Test
    void installingAConnectionFactoryWithAProxyNeverMutatesTheJvmWideDefaultProxySelector() throws Exception {
        // The whole point of resolving proxies locally (see installConnectionFactory's javadoc)
        // rather than calling ProxySelector.setDefault: this must be provably a NO-OP on global
        // proxy state, unlike the (rejected) alternative design.
        ProxySelector before = ProxySelector.getDefault();
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        sdd.cli.implement.RunGit.startBranch(repo.path(), "sdd/RUN/lib", repo.headSha());
        Path remote = bareRemote("lib");

        RemoteGit.push(repo.path(), "sdd/RUN/lib", remote.toUri().toString(), "sdd", "pat", null,
                new AtlassianProxy("proxy.corp.local", 8080, List.of()));

        assertThat(ProxySelector.getDefault()).isSameAs(before);
    }
}
