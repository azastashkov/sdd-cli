package sdd.cli.review;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.HttpTransport;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefLeaseSpec;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.eclipse.jgit.transport.http.JDKHttpConnection;
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory;
import sdd.core.config.AtlassianTls;
import sdd.core.http.HttpClients;

import javax.net.ssl.TrustManager;
import java.io.IOException;
import java.net.Proxy;
import java.net.URL;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Collection;
import java.util.Locale;

/**
 * Gate 2's ONE git write-verb the agent loop must never reach: push. This is the design amendment
 * (docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md, dated 2026-08-16) to the original
 * "orchestrator owns git via JGit... never push/remote" line — deliberately its own class, not a
 * method added to {@code sdd.cli.implement.RunGit}, so that {@code RunGit} (which the agent loop's
 * tool surface and the orchestrator both route every git operation through) stays exactly as
 * push-free as it always was. Reachable only from Gate-2 code paths ({@code ReviewCommand},
 * {@code DecisionCommand}), never from {@code sdd implement}.
 *
 * <p><b>Git-over-HTTP with a PAT, never SSH</b> (Task 5 brief §1): no key distribution on a closed
 * corporate network, and it reuses the exact same credential {@link sdd.core.http.RestClient}
 * already authenticates Bitbucket's REST API with.
 *
 * <p><b>Force-with-lease, not a plain force-push.</b> {@code sdd review redo} restarts a run
 * branch from its base SHA and produces a different history than whatever this class pushed last
 * time for the same branch — an ordinary non-fast-forward from this process's own point of view,
 * which a plain (non-forced) push would reject. But a force-push wide open would just as happily
 * clobber a change some OTHER writer made to the same branch name in between. Force-with-lease
 * threads that needle: {@link #push} reads the remote branch's CURRENT sha immediately before
 * pushing (via {@code ls-remote}, not a cached "last known" value — this class never fetches) and
 * conditions the push on the remote still being at exactly that sha. A branch this class has never
 * seen before leases against {@link ObjectId#zeroId()} — git's own convention for "this ref must
 * not exist yet".
 */
public final class RemoteGit {
    private RemoteGit() {
    }

    /**
     * Force-with-lease pushes {@code branch}'s current local head to {@code cloneUrl}, creating the
     * branch on the remote if it does not exist yet.
     *
     * @param username any non-empty string — Bitbucket Data Center's HTTP Personal Access Token
     *                 auth is carried entirely by the token; the username is not independently
     *                 checked. Least-certain detail, see the Task 5 report.
     * @param pat      the Bitbucket PAT — the caller names the environment variable it came from in
     *                 any error message; this method itself never logs or echoes the value.
     * @param tls      the corporate truststore/proxy config, or null when none is configured
     *                 (mirrors {@link HttpClients#build}'s own null-means-JDK-default contract).
     *                 Wired into JGit via a custom {@link HttpConnectionFactory} rather than a
     *                 second, independent truststore load — see {@link HttpClients#trustManagers}.
     * @throws RuntimeException on any transport failure, auth failure, or a lease violation (the
     *                          remote branch was not where this call expected it to be) — the
     *                          caller (Task 5's best-effort Bitbucket integration) turns this into a
     *                          {@code warn:} line, never a thrown-through failure.
     */
    public static void push(Path repo, String branch, String cloneUrl, String username, String pat,
            AtlassianTls tls) {
        if (tls != null) {
            installTrustingConnectionFactory(tls);
        }
        String refName = "refs/heads/" + branch;
        UsernamePasswordCredentialsProvider credentials = new UsernamePasswordCredentialsProvider(username, pat);
        try (Git git = Git.open(repo.toFile())) {
            String expected = currentRemoteSha(git, cloneUrl, refName, credentials);
            pushWithExpectedLease(git, refName, branch, cloneUrl, credentials, expected);
        } catch (IOException e) {
            throw new IllegalStateException("cannot push " + branch + " to " + cloneUrl + ": "
                    + e.getMessage(), e);
        }
    }

    /**
     * The actual force-with-lease push, conditioned on an ALREADY-KNOWN {@code expected} remote
     * sha rather than reading it fresh — {@link #push} always supplies one it just read via
     * {@link #currentRemoteSha}, so in real use this is only ever as stale as the gap between that
     * read and this call. Split out (package-private) so a test can supply a value it KNOWS is
     * stale and deterministically observe the rejection {@link #push}'s own narrow race window
     * cannot reliably reproduce.
     */
    static void pushWithExpectedLease(Git git, String refName, String branch, String cloneUrl,
            UsernamePasswordCredentialsProvider credentials, String expected) {
        try {
            Iterable<PushResult> results = git.push()
                    .setRemote(cloneUrl)
                    .setCredentialsProvider(credentials)
                    .setRefSpecs(new RefSpec(refName + ":" + refName))
                    .setForce(true)
                    .setRefLeaseSpecs(new RefLeaseSpec(refName, expected))
                    .call();
            for (PushResult result : results) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    RemoteRefUpdate.Status status = update.getStatus();
                    if (status != RemoteRefUpdate.Status.OK && status != RemoteRefUpdate.Status.UP_TO_DATE) {
                        throw new IllegalStateException("push of " + branch + " to " + cloneUrl + " " + status
                                + (update.getMessage() != null ? ": " + update.getMessage() : ""));
                    }
                }
            }
        } catch (GitAPIException e) {
            throw new IllegalStateException("cannot push " + branch + " to " + cloneUrl + ": "
                    + e.getMessage(), e);
        }
    }

    /** The remote branch's current sha, or {@link ObjectId#zeroId()}'s name when it does not exist
     *  yet — the force-with-lease "expected" value {@link #push} conditions its push on. A fresh
     *  {@code ls-remote} every call, deliberately: this class never fetches or keeps a local
     *  remote-tracking branch, so there is no cached "last known" value to lease against — the
     *  lease is only as tight as the gap between this call and the push moments later, which is the
     *  brief's own framing of what force-with-lease guarantees here (protection against clobbering
     *  a change this process did not know about, not a hard guarantee against every race). */
    private static String currentRemoteSha(Git git, String cloneUrl, String refName,
            UsernamePasswordCredentialsProvider credentials) {
        try {
            Collection<Ref> refs = git.lsRemote().setRemote(cloneUrl).setCredentialsProvider(credentials)
                    .setHeads(true).call();
            for (Ref ref : refs) {
                if (ref.getName().equals(refName)) {
                    return ref.getObjectId().name();
                }
            }
            return ObjectId.zeroId().name();
        } catch (GitAPIException e) {
            throw new IllegalStateException("cannot read remote branch " + refName + " of " + cloneUrl
                    + " before push: " + e.getMessage(), e);
        }
    }

    /**
     * Installs a JGit {@link HttpConnectionFactory} that trusts the corporate CA {@code tls} names,
     * wired through {@link HttpClients#trustManagers} so the truststore file is loaded exactly
     * once, not re-parsed independently of {@link sdd.core.http.RestClient}'s own TLS setup.
     *
     * <p>{@link HttpTransport#setConnectionFactory} is a process-global static — JGit exposes no
     * per-{@code PushCommand} way to configure TLS, so there is no narrower hook available. Called
     * on every {@link #push} rather than once at startup: the cost is rebuilding a small
     * {@code TrustManager[]} array and installing a lambda, and doing it per-call means a config
     * reload mid-process (unlikely in this CLI's actual lifetime, but never assumed away) is always
     * honoured rather than silently stuck with whatever was first installed.
     */
    private static void installTrustingConnectionFactory(AtlassianTls tls) {
        TrustManager[] trustManagers = HttpClients.trustManagers(tls);
        HttpTransport.setConnectionFactory(new HttpConnectionFactory() {
            @Override
            public HttpConnection create(URL url) throws IOException {
                return configure(new JDKHttpConnectionFactory().create(url));
            }

            @Override
            public HttpConnection create(URL url, Proxy proxy) throws IOException {
                return configure(new JDKHttpConnectionFactory().create(url, proxy));
            }

            private HttpConnection configure(HttpConnection connection) throws IOException {
                // Only for https: JDKHttpConnection.configure's own contract (setting an
                // SSLSocketFactory) only makes sense against an HttpsURLConnection, and calling it
                // on a plain http:// connection is untested territory this class deliberately does
                // not exercise — see the Task 5 report's least-certain list.
                if (connection instanceof JDKHttpConnection jdkConnection
                        && "https".equalsIgnoreCase(connection.getURL().getProtocol())) {
                    try {
                        jdkConnection.configure(null, trustManagers, null);
                    } catch (GeneralSecurityException e) {
                        throw new IOException("cannot configure JGit TLS trust store: " + e.getMessage(), e);
                    }
                }
                return connection;
            }
        });
    }

    /**
     * {@code <base_url>/scm/<project>/<repo>.git} — Task 5 brief §1's exact clone-URL shape, with
     * project and repo both lowercased ("the Data Center convention", per the brief — the SCM path
     * segment, distinct from {@link BitbucketClient}'s REST {@code {projectKey}} path parameter,
     * which is NOT lowercased; see that class's javadoc). This is one of the least-certain details
     * in this task: real Bitbucket Server clone URLs are sometimes shown preserving the configured
     * project key's case rather than lowercasing it. Followed verbatim from the brief regardless —
     * flagged for Phase 6 verification against a live instance.
     */
    public static String cloneUrl(String baseUrl, String project, String repo) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + "/scm/" + project.toLowerCase(Locale.ROOT) + "/" + repoSlug(repo) + ".git";
    }

    /** Bitbucket repository slugs are always lowercase, auto-derived from the repo's display name —
     *  shared by {@link #cloneUrl} and {@link BitbucketClient}'s REST paths so the two agree on
     *  which repo they mean. Non-alphanumeric characters (spaces, underscores in an unusual repo
     *  name) become {@code -}, mirroring {@code Orchestrator}'s own {@code slug} helper for branch
     *  names — a separate, private method there this deliberately does not reach into, since a
     *  Bitbucket repo slug and an {@code sdd} run-branch slug are different conventions that happen
     *  to look similar, not the same value. */
    public static String repoSlug(String repo) {
        return repo.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
    }
}
