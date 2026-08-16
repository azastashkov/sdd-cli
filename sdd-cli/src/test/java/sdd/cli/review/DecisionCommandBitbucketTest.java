package sdd.cli.review;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.cli.ReviewCommand;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunGit;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;
import sdd.core.db.Database;
import sdd.core.testing.FixtureRepo;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 5 brief §4's "ordering is the crux" requirement: {@code sdd review approve}'s Bitbucket
 * merge must be attempted ONLY after a real local squash was granted — a refused squash (dirty
 * tree, moved branch) must reach Bitbucket not at all. This is, per the brief, "the single most
 * important assertion in this task", so it gets its own file rather than living buried inside the
 * much larger {@code ReviewDecisionsCommandTest}.
 *
 * <p>The merge/decline REST sequence itself ({@link BitbucketDecisions#merge}/{@link
 * BitbucketDecisions#decline}) is tested directly against WireMock, given an already-built {@link
 * BitbucketClient} — the same "separate push from REST" seam {@link BitbucketReviewTest} uses,
 * since WireMock cannot serve a real git-over-HTTP push and {@link RemoteGitTest} already covers
 * push against a local bare repository on its own.
 */
class DecisionCommandBitbucketTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    @TempDir Path ws;
    private static final String RUN_ID = "SPEC-1-v1";
    private static final String BRANCH = "sdd/SPEC-1-v1/lib";

    private record Fixture(FixtureRepo lib, String base, String checkpoint, String originalBranch,
                           Path planPath, Path runDir) {
    }

    /** {@code prId}: null means "sdd review never opened a PR for this repo" (Bitbucket off, or
     *  this run predates it); non-null simulates a PR already on record, the ordinary case by the
     *  time a human runs {@code approve}/{@code reject}. Two checkpoint commits, so an approve has
     *  something real to squash — see {@link #fixtureAlreadySingleCommit} for the no-op case. */
    private Fixture fixture(Integer prId, boolean pullRequests) throws Exception {
        return fixture(prId, pullRequests, 2);
    }

    /** {@code checkpointCommits}: how many commits the run branch carries past {@code base} — 2
     *  (via {@link #fixture(Integer, boolean)}) gives {@code SquashApprove} something real to
     *  collapse; 1 (via {@link #fixtureAlreadySingleCommit}) drives it into its "already a single
     *  commit past base_sha" no-op branch instead — {@code result.applied()} is still true there,
     *  only {@code result.squashed()} is false, which is exactly the case Fix 2 of the review pins:
     *  the Bitbucket merge attempt must follow this branch too, not just the real-squash one. */
    private Fixture fixture(Integer prId, boolean pullRequests, int checkpointCommits) throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n");
        Path g = lib.path().resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path props = lib.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props,
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
        lib.commit("base");
        String base = lib.headSha();
        String originalBranch = RunGit.currentBranch(lib.path());
        RunGit.startBranch(lib.path(), BRANCH, base);
        lib.file("A.java", "class A { int x; }\n").commit("sdd: checkpoint 1");
        if (checkpointCommits >= 2) {
            lib.file("B.java", "class B {}\n").commit("sdd: checkpoint 2");
        }
        String checkpoint = lib.headSha();
        RunGit.checkout(lib.path(), originalBranch);

        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')", lib.path().toString()));
        }
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen }
                atlassian:
                  bitbucket:
                    base_url: %s
                    token: sk-token
                    project: TRADING
                    default_reviewers: []
                  pull_requests: %s
                """.formatted(wm.baseUrl(), pullRequests));
        Files.writeString(ws.resolve("s.md"), """
                ---
                id: SPEC-1
                title: Tiers
                owner: me
                status: approved
                ---

                ## Goal
                g

                ## Requirements
                - R1: x.

                ## Acceptance Criteria
                - A1: y.
                """);
        String specSha = sdd.plan.approve.Hashes.sha256(Files.readString(ws.resolve("s.md")));
        String planJson = """
                { "spec_id":"SPEC-1","plan_version":1,"spec_sha256":"%s","plan_sha256":"z",
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],"contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."}] }
                """.formatted(specSha, base);
        Path planPath = ws.resolve("s.plan.json");
        Files.writeString(planPath, planJson);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, RUN_ID, planJson, Files.readString(ws.resolve("s.md")));
        store.releaseLock(runDir);
        store.writeState(runDir, new RunState(RUN_ID, List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, BRANCH, checkpoint, "ok", null, prId,
                        prId == null ? null : "https://bb.corp.local/pull-requests/" + prId)),
                null, 21L));

        return new Fixture(lib, base, checkpoint, originalBranch, planPath, runDir);
    }

    /** Delegates to the 3-arg {@link #fixture(Integer, boolean, int)} with exactly ONE checkpoint
     *  commit — see that overload's javadoc for why. */
    private Fixture fixtureAlreadySingleCommit(Integer prId, boolean pullRequests) throws Exception {
        return fixture(prId, pullRequests, 1);
    }

    private record Invocation(int exit, String out, String err) {
    }

    private static Invocation exec(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new ReviewCommand());
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        int exit = cli.execute(args);
        return new Invocation(exit, out.toString(), err.toString());
    }

    // --- the crux: a refused squash attempts no merge at all --------------------------------------

    @Test
    void aSquashRefusedByADirtyTreeAttemptsNoBitbucketMergeAtAll() throws Exception {
        Fixture f = fixture(17, true);
        // Dirty the working tree — SquashApprove.approve refuses before touching state.json.
        Files.writeString(f.lib().path().resolve("A.java"), "class A { /* uncommitted */ }\n");

        Invocation r = exec("--workspace", ws.toString(), "approve", "lib", f.planPath().toString());

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("squash refused");
        assertThat(wm.getAllServeEvents()).isEmpty();
        // state.json's recorded PR is untouched — nothing here could have moved it.
        RunState state = RunStore.system().readState(f.runDir());
        assertThat(state.repos().get(0).prId()).isEqualTo(17);
    }

    @Test
    void aSquashRefusedByAMovedBranchAttemptsNoBitbucketMergeAtAll() throws Exception {
        Fixture f = fixture(17, true);
        // Move the branch off its recorded checkpoint — the second refusal SquashApprove checks.
        RunGit.startBranch(f.lib().path(), BRANCH, f.checkpoint());
        f.lib().file("C.java", "class C {}\n").commit("moved off checkpoint");
        RunGit.checkout(f.lib().path(), f.originalBranch());

        Invocation r = exec("--workspace", ws.toString(), "approve", "lib", f.planPath().toString());

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("squash refused");
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void aGrantedSquashDoesAttemptTheBitbucketMergeUnlikeARefusedOne() throws Exception {
        // The contrast case for the two refusal tests above: once the squash is GRANTED, this
        // class does try to reach Bitbucket for it — proven here by a real (if doomed) attempt
        // reaching the network, since WireMock is not a git-over-HTTP server and the push fails.
        // RemoteGitTest/BitbucketReviewTest already prove the push and merge halves work
        // individually against, respectively, a real bare repo and a real WireMock server.
        Fixture f = fixture(17, true);

        Invocation r = exec("--workspace", ws.toString(), "approve", "lib", f.planPath().toString());

        assertThat(r.out()).contains("squashed 2 commits");
        assertThat(r.err()).contains("could not merge PR for lib");
        assertThat(wm.getAllServeEvents()).isNotEmpty();
    }

    @Test
    void aNoOpSquashOfAnAlreadySingleCommitBranchStillAttemptsTheBitbucketMerge() throws Exception {
        // Fix 2 (code review): SquashApprove.approve's OTHER applied=true outcome — the branch was
        // already a single commit past base_sha, so squashed()=false and NO state.json checkpoint
        // rewrite happens — must still be treated as a granted approve, not a refusal. Pinned
        // separately from aGrantedSquashDoesAttemptTheBitbucketMergeUnlikeARefusedOne, which only
        // ever exercises the OTHER applied=true outcome (a real multi-commit squash).
        Fixture f = fixtureAlreadySingleCommit(17, true);

        Invocation r = exec("--workspace", ws.toString(), "approve", "lib", f.planPath().toString());

        assertThat(r.out()).contains("already").contains("a single commit past");
        assertThat(r.out()).doesNotContain("squashed ");   // confirms the no-op branch fired, not a real squash
        assertThat(r.err()).contains("could not merge PR for lib");   // the attempt was made (and failed, per WireMock not being git)
        assertThat(wm.getAllServeEvents()).isNotEmpty();
    }

    @Test
    void pullRequestsDefaultFalseMeansApproveMakesNoBitbucketCallEvenOnAGrantedSquash() throws Exception {
        Fixture f = fixture(null, false);

        Invocation r = exec("--workspace", ws.toString(), "approve", "lib", f.planPath().toString());

        assertThat(r.exit()).isEqualTo(0);
        assertThat(r.out()).contains("squashed 2 commits");
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void approveWithNoPreviouslyRecordedPrMakesNoBitbucketCallEvenWithPullRequestsOn() throws Exception {
        // pull_requests: true, but this run's sdd review never opened a PR for lib (prId null) —
        // e.g. Bitbucket was turned on after the run already had its report written once.
        Fixture f = fixture(null, true);

        Invocation r = exec("--workspace", ws.toString(), "approve", "lib", f.planPath().toString());

        assertThat(r.exit()).isEqualTo(0);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    // --- BitbucketDecisions.merge/decline: the REST half, tested directly against WireMock --------

    @Test
    void mergeRefetchesTheCurrentVersionBeforeMerging() {
        wm.stubFor(get(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests/17"))
                .willReturn(okJson("{\"id\":17,\"version\":5,\"title\":\"t\",\"description\":\"d\","
                        + "\"links\":{\"self\":[{\"href\":\"https://bb/17\"}]}}")));
        wm.stubFor(post(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests/17/merge?version=5"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.ok()));
        BitbucketClient client = new BitbucketClient(
                new sdd.core.http.RestClient("Bitbucket", wm.baseUrl(), "sk", "BITBUCKET_PAT",
                        java.time.Duration.ofSeconds(5), java.net.http.HttpClient.newHttpClient()),
                "TRADING");
        StringWriter outBuf = new StringWriter();

        try (PrintWriter out = new PrintWriter(outBuf); PrintWriter err = new PrintWriter(new StringWriter())) {
            BitbucketDecisions.merge(client, "lib", 17, out, err);
        }

        assertThat(outBuf.toString()).contains("merged PR #17");
        wm.verify(postRequestedFor(
                urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests/17/merge?version=5")));
    }

    @Test
    void declineRefetchesTheCurrentVersionBeforeDeclining() {
        wm.stubFor(get(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests/17"))
                .willReturn(okJson("{\"id\":17,\"version\":2,\"title\":\"t\",\"description\":\"d\","
                        + "\"links\":{\"self\":[{\"href\":\"https://bb/17\"}]}}")));
        wm.stubFor(post(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests/17/decline?version=2"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.ok()));
        BitbucketClient client = new BitbucketClient(
                new sdd.core.http.RestClient("Bitbucket", wm.baseUrl(), "sk", "BITBUCKET_PAT",
                        java.time.Duration.ofSeconds(5), java.net.http.HttpClient.newHttpClient()),
                "TRADING");
        StringWriter outBuf = new StringWriter();

        try (PrintWriter out = new PrintWriter(outBuf); PrintWriter err = new PrintWriter(new StringWriter())) {
            BitbucketDecisions.decline(client, "lib", 17, out, err);
        }

        assertThat(outBuf.toString()).contains("declined PR #17");
        wm.verify(postRequestedFor(
                urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests/17/decline?version=2")));
    }

    @Test
    void rejectWithAPreviouslyRecordedPrDeclinesIt() throws Exception {
        Fixture f = fixture(17, true);
        wm.stubFor(get(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests/17"))
                .willReturn(okJson("{\"id\":17,\"version\":1,\"title\":\"t\",\"description\":\"d\","
                        + "\"links\":{\"self\":[{\"href\":\"https://bb/17\"}]}}")));
        wm.stubFor(post(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests/17/decline?version=1"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.ok()));

        Invocation r = exec("--workspace", ws.toString(), "reject", "lib", f.planPath().toString());

        assertThat(r.exit()).isEqualTo(0);
        assertThat(r.out()).contains("declined PR #17");
        wm.verify(postRequestedFor(
                urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests/17/decline?version=1")));
    }
}
