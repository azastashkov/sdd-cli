package sdd.cli.review;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;
import sdd.core.config.ConfigLoader;
import sdd.core.config.SddConfig;
import sdd.core.testing.FixtureRepo;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code sdd review --interactive}'s reject key must decline the same PR a scripted {@code sdd
 * review reject} would (Task 5 brief §4) — {@link InteractiveReview}'s own javadoc states the
 * principle this pins: "a human walking this loop and a script... leave the estate in identical
 * shape". Approve's squash-then-merge is already covered end-to-end by {@code
 * InteractiveReviewTest} calling the shared {@code DecisionCommand.squashAndRecord}; this covers
 * the one path ({@code "r"}) that does NOT go through a shared {@code DecisionCommand} follow-up
 * method and so needed its own explicit wiring.
 */
class InteractiveReviewBitbucketTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    @TempDir Path ws;

    @Test
    void typingRDeclinesTheRepoSPreviouslyRecordedPullRequest() throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n").commit("base");

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
                  pull_requests: true
                """.formatted(wm.baseUrl()));
        SddConfig config = ConfigLoader.load(ws);

        PlanModel plan = new PlanModel("SPEC-1", 1, "s", "p",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", lib.headSha())),
                List.of(List.of("lib")), List.of(), List.of(), List.of());
        RepoRun repoRun = new RepoRun("lib", RepoState.SUCCEEDED, "sdd/SPEC-1-v1/lib", lib.headSha(),
                "ok", null, 17, "https://bb.corp.local/pull-requests/17");
        RunState state = new RunState("SPEC-1-v1", List.of(repoRun), null, 0L);
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "SPEC-1-v1", "{}", "");
        store.releaseLock(runDir);
        store.writeState(runDir, state);
        RunContext run = new RunContext("SPEC-1-v1", runDir, store, plan, state, config,
                Map.of("lib", lib.path()));

        wm.stubFor(get(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests/17"))
                .willReturn(okJson("{\"id\":17,\"version\":6,\"title\":\"t\",\"description\":\"d\","
                        + "\"links\":{\"self\":[{\"href\":\"https://bb.corp.local/pull-requests/17\"}]}}")));
        wm.stubFor(post(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests/17/decline?version=6"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.ok()));

        BufferedReader in = new BufferedReader(new StringReader("r\n\nq\n"));   // reject, blank reason, quit
        StringWriter outBuf = new StringWriter();
        StringWriter errBuf = new StringWriter();
        try (PrintWriter out = new PrintWriter(outBuf); PrintWriter err = new PrintWriter(errBuf)) {
            InteractiveReview.run(in, out, err, new InteractiveReview.Context(run, ws, runDir.resolve("x"),
                    new RebuildPass.Outcome(Map.of(), List.of(), List.of(), List.of(), List.of()),
                    RebuildScope.estate()));
        }

        assertThat(outBuf.toString()).contains("declined PR #17");
        wm.verify(postRequestedFor(
                urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests/17/decline?version=6")));
    }
}
