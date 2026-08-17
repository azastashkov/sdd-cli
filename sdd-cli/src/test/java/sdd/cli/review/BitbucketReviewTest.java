package sdd.cli.review;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunGit;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;
import sdd.core.config.ConfigLoader;
import sdd.core.config.SddConfig;
import sdd.core.testing.FixtureRepo;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BitbucketReview#run} and its {@link BitbucketReview#openOrUpdate} half — the latter given
 * an already-built {@link BitbucketClient} pointed at WireMock, so this exercises Task 5's real
 * PR-management logic (title, description, reviewers, find-vs-create, state.json write-back)
 * without needing a working git-over-HTTP push in front of it; {@link RemoteGitTest} covers push on
 * its own, against a local bare repository.
 */
class BitbucketReviewTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    @TempDir Path ws;

    private RunContext runContext(String repo, RepoRun repoRun, FixtureRepo fixture, boolean pullRequests)
            throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen }
                atlassian:
                  bitbucket:
                    base_url: %s
                    token: ${BITBUCKET_PAT}
                    project: TRADING
                    default_reviewers: [jsmith, adoe]
                  pull_requests: %s
                """.formatted(wm.baseUrl(), pullRequests));
        SddConfig config = ConfigLoader.load(ws, name -> "BITBUCKET_PAT".equals(name) ? "sk-token" : null);

        PlanModel plan = new PlanModel("SPEC-1", 1, "s", "p",
                List.of(new PlanModel.PlanRepo(repo, "seed", "SEED", "minor", fixture.headSha())),
                List.of(List.of(repo)), List.of(), List.of(), List.of());
        RunState state = new RunState("SPEC-1-v1", List.of(repoRun), null, 0L);
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "SPEC-1-v1", "{}", """
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

                ## Sources

                - jira PROJ-9 updated 2026-08-16T09:00:00Z https://jira.corp.local/browse/PROJ-9
                """);
        store.releaseLock(runDir);
        store.writeState(runDir, state);

        return new RunContext("SPEC-1-v1", runDir, store, plan, state, config, Map.of(repo, fixture.path()));
    }

    private ReportInputs reportInputs(RunContext run) {
        return new ReportInputs(run.runId(), run.plan(), run.state(), Map.of(), Map.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), RunContext.Checkpoints.none(),
                "runbook", RebuildScope.estate());
    }

    // --- run(): the pull_requests gate ----------------------------------------------------------

    @Test
    void pullRequestsDefaultFalseMakesNoBitbucketCallAtAll() throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n").commit("base");
        RepoRun repoRun = new RepoRun("lib", RepoState.SUCCEEDED, "sdd/SPEC-1-v1/lib", lib.headSha(), "ok", null);
        RunContext run = runContext("lib", repoRun, lib, false);

        StringWriter outBuf = new StringWriter();
        StringWriter errBuf = new StringWriter();
        try (PrintWriter out = new PrintWriter(outBuf); PrintWriter err = new PrintWriter(errBuf)) {
            BitbucketReview.run(run, reportInputs(run), out, err);
        }

        assertThat(outBuf.toString()).isEmpty();
        assertThat(errBuf.toString()).isEmpty();
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    // --- openOrUpdate(): create vs update, description, reviewers, state.json write-back ---------

    @Test
    void opensANewPullRequestWhenNoneExistsAndRecordsItInState() throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n").commit("base");
        RunGit.startBranch(lib.path(), "sdd/SPEC-1-v1/lib", lib.headSha());
        RepoRun repoRun = new RepoRun("lib", RepoState.SUCCEEDED, "sdd/SPEC-1-v1/lib", lib.headSha(), "ok", null);
        RunContext run = runContext("lib", repoRun, lib, true);
        ReportInputs in = reportInputs(run);

        wm.stubFor(get(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/branches/default"))
                .willReturn(okJson("{\"id\":\"refs/heads/main\",\"displayId\":\"main\"}")));
        wm.stubFor(get(urlPathEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests"))
                .willReturn(okJson("{\"size\":0,\"isLastPage\":true,\"values\":[]}")));
        wm.stubFor(post(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests"))
                .willReturn(okJson("""
                        {"id":9,"version":0,"title":"t","description":"d",
                         "links":{"self":[{"href":"https://bb.corp.local/pull-requests/9"}]}}
                        """)));

        BitbucketClient client = BitbucketClients.rest(run.config().atlassian());
        StringWriter outBuf = new StringWriter();
        try (PrintWriter out = new PrintWriter(outBuf); PrintWriter err = new PrintWriter(new StringWriter())) {
            BitbucketReview.openOrUpdate(client, run, in, "lib", repoRun, lib.path(),
                    run.config().atlassian().bitbucket().defaultReviewers(), "SPEC-1: Tiers",
                    "https://jira.corp.local/browse/PROJ-9", out, err);
        }

        assertThat(outBuf.toString()).contains("opened PR #9").contains("https://bb.corp.local/pull-requests/9");
        wm.verify(postRequestedFor(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.title",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("SPEC-1: Tiers")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                        "$.reviewers[0].user.name", com.github.tomakehurst.wiremock.client.WireMock.equalTo("jsmith"))));

        RunState reread = run.store().readState(run.runDir());
        RepoRun updated = reread.repos().get(0);
        assertThat(updated.prId()).isEqualTo(9);
        assertThat(updated.prUrl()).isEqualTo("https://bb.corp.local/pull-requests/9");
        // The description reuses ReviewReport.renderRepo verbatim, plus the run id/spec id/Jira link.
        wm.verify(postRequestedFor(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.description",
                        com.github.tomakehurst.wiremock.client.WireMock.containing("Run: SPEC-1-v1")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.description",
                        com.github.tomakehurst.wiremock.client.WireMock.containing("https://jira.corp.local/browse/PROJ-9"))));
    }

    @Test
    void updatesAnExistingOpenPullRequestInsteadOfOpeningADuplicate() throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n").commit("base");
        RunGit.startBranch(lib.path(), "sdd/SPEC-1-v1/lib", lib.headSha());
        RepoRun repoRun = new RepoRun("lib", RepoState.SUCCEEDED, "sdd/SPEC-1-v1/lib", lib.headSha(), "ok", null);
        RunContext run = runContext("lib", repoRun, lib, true);
        ReportInputs in = reportInputs(run);

        wm.stubFor(get(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/branches/default"))
                .willReturn(okJson("{\"id\":\"refs/heads/main\",\"displayId\":\"main\"}")));
        wm.stubFor(get(urlPathEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests"))
                .willReturn(okJson("""
                        {"size":1,"isLastPage":true,"values":[
                          {"id":9,"version":3,"title":"old","description":"old",
                           "links":{"self":[{"href":"https://bb.corp.local/pull-requests/9"}]}}]}
                        """)));
        wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.put(
                        urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests/9"))
                .willReturn(okJson("""
                        {"id":9,"version":4,"title":"t","description":"d",
                         "links":{"self":[{"href":"https://bb.corp.local/pull-requests/9"}]}}
                        """)));

        BitbucketClient client = BitbucketClients.rest(run.config().atlassian());
        StringWriter outBuf = new StringWriter();
        try (PrintWriter out = new PrintWriter(outBuf); PrintWriter err = new PrintWriter(new StringWriter())) {
            BitbucketReview.openOrUpdate(client, run, in, "lib", repoRun, lib.path(), List.of(), "SPEC-1: Tiers",
                    null, out, err);
        }

        assertThat(outBuf.toString()).contains("updated PR #9");
        wm.verify(0, postRequestedFor(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests")));
    }

    @Test
    void warnsWhenBaseShaIsNotAnAncestorOfTheDefaultBranch() throws Exception {
        // "main" (the Bitbucket default branch, resolved locally) stays at the very first commit.
        // The plan's base_sha is a LATER commit on a side branch — a descendant of main's head, so
        // main is NOT reachable by walking backward from base_sha, which is exactly the "not an
        // ancestor" direction this check flags (a base_sha that is itself ahead of main would make
        // the eventual PR diff include everything already on main, hence "PR diff will be noisy").
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n").commit("root");
        String mainHead = lib.headSha();
        RunGit.startBranch(lib.path(), "ahead-of-main", mainHead);
        lib.file("A.java", "class A { int x; }\n").commit("ahead of main");
        String divergentBase = lib.headSha();
        RunGit.startBranch(lib.path(), "sdd/SPEC-1-v1/lib", divergentBase);

        RepoRun repoRun = new RepoRun("lib", RepoState.SUCCEEDED, "sdd/SPEC-1-v1/lib", divergentBase, "ok", null);
        RunContext run = runContext("lib", repoRun, lib, true);
        ReportInputs in = reportInputs(run);

        wm.stubFor(get(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/branches/default"))
                .willReturn(okJson("{\"id\":\"refs/heads/main\",\"displayId\":\"main\"}")));
        wm.stubFor(get(urlPathEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests"))
                .willReturn(okJson("{\"size\":0,\"isLastPage\":true,\"values\":[]}")));
        wm.stubFor(post(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests"))
                .willReturn(okJson("{\"id\":1,\"version\":0,\"title\":\"t\",\"description\":\"d\","
                        + "\"links\":{\"self\":[{\"href\":\"https://bb/1\"}]}}")));

        BitbucketClient client = BitbucketClients.rest(run.config().atlassian());
        StringWriter errBuf = new StringWriter();
        try (PrintWriter out = new PrintWriter(new StringWriter()); PrintWriter err = new PrintWriter(errBuf)) {
            BitbucketReview.openOrUpdate(client, run, in, "lib", repoRun, lib.path(), List.of(), "t", null, out, err);
        }

        assertThat(errBuf.toString()).contains("  warn: lib: base_sha is not an ancestor of main; "
                + "PR diff will be noisy");
    }

    // --- run(): per-repo isolation and best-effort failure handling -------------------------------

    @Test
    void aRepoWithNoRecordedBranchIsWarnedAboutAndSkippedRatherThanThrowing() throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n").commit("base");
        RepoRun repoRun = new RepoRun("lib", RepoState.SUCCEEDED, null, null, "ok", null);   // no branch
        RunContext run = runContext("lib", repoRun, lib, true);

        StringWriter errBuf = new StringWriter();
        try (PrintWriter out = new PrintWriter(new StringWriter()); PrintWriter err = new PrintWriter(errBuf)) {
            BitbucketReview.run(run, reportInputs(run), out, err);
        }

        assertThat(errBuf.toString()).contains("  warn: bitbucket: lib:");
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void aSetupFailureWritesAFailureEntryToDiagnosticsNotJustTheWarnLine() throws Exception {
        // Gate review I3: pull_requests is on but no atlassian.bitbucket is configured, so
        // requireBitbucket throws before any HTTP call — the exact catch site that used to print
        // only e.getMessage() and leave a null-message failure as a bare "null" on stderr with
        // nothing at all in the diagnostics file built to debug it remotely.
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen }
                atlassian:
                  pull_requests: true
                """);
        SddConfig config = ConfigLoader.load(ws);
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n").commit("base");
        PlanModel plan = new PlanModel("SPEC-1", 1, "s", "p",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", lib.headSha())),
                List.of(List.of("lib")), List.of(), List.of(), List.of());
        RepoRun repoRun = new RepoRun("lib", RepoState.SUCCEEDED, "sdd/SPEC-1-v1/lib", lib.headSha(), "ok", null);
        RunState state = new RunState("SPEC-1-v1", List.of(repoRun), null, 0L);
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "SPEC-1-v1", "{}", """
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
        store.releaseLock(runDir);
        store.writeState(runDir, state);
        sdd.core.diagnostics.DiagnosticWriter diagnostics = sdd.core.diagnostics.Diagnostics.open(
                ws, "review", List.of("review"), config.atlassian(), InstantSource.system(),
                new PrintWriter(new StringWriter()));
        RunContext run = new RunContext("SPEC-1-v1", runDir, store, plan, state, config, Map.of("lib", lib.path()),
                diagnostics);
        ReportInputs in = reportInputs(run);

        StringWriter errBuf = new StringWriter();
        try (PrintWriter out = new PrintWriter(new StringWriter()); PrintWriter err = new PrintWriter(errBuf)) {
            BitbucketReview.run(run, in, out, err);
        }
        diagnostics.close();

        assertThat(errBuf.toString()).contains("  warn: bitbucket: atlassian.pull_requests is true but no atlassian.bitbucket is configured");
        Path diagDir = ws.resolve(".sdd/diagnostics");
        StringBuilder content = new StringBuilder();
        try (var files = Files.list(diagDir)) {
            for (Path f : files.toList()) {
                content.append(Files.readString(f));
            }
        }
        assertThat(content.toString()).contains("failure: bitbucket: setup");
    }
}
