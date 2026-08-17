package sdd.plan.jira;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import sdd.core.http.AtlassianException;
import sdd.core.http.RestClient;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.Usage;
import sdd.core.testing.ScriptedChatModel;
import sdd.plan.confluence.ConfluenceClient;
import sdd.plan.spec.NormalizedSpec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** End-to-end wiring: JiraClient + ConfluenceClient + LinkHarvester -> SourceBundle ->
 *  ConfluenceNormalizer -> a NormalizedSpec with a fetcher-written, never-model-authored
 *  "## Sources" list. Per-component behaviour (URL shapes, host filtering, depth/cap notes,
 *  cycle prevention) is pinned in JiraClientTest/ConfluenceClientTest/LinkHarvesterTest; this
 *  class only covers what only shows up once everything is wired together: root-vs-linked 404
 *  handling, the linked-issue dedup/cap policy, and Sources ordering. */
class JiraSpecSourceTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    private static final ChatResponse NORMALIZED_RESPONSE = new ChatResponse(ChatMessage.assistant("""
            {"title": "Order API", "owner": "", "status": "", "goal": "G.",
             "background": "", "requirements": ["r"], "acceptance": ["a"], "constraints": [],
             "touchpoints": [], "out_of_scope": [], "open_questions": [], "unmapped": []}"""),
            "stop", new Usage(10, 10));

    private static String fixture(String dir, String name) {
        try {
            return Files.readString(Path.of("src/test/resources/" + dir + "/" + name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void stubIssue(String key, String fixtureFile) {
        wm.stubFor(get(urlEqualTo("/rest/api/2/issue/" + key
                + "?expand=renderedFields&fields=summary,description,issuelinks,subtasks,comment,status,updated"))
                .willReturn(okJson(fixture("jira", fixtureFile))));
    }

    private void stubIssueNotFound(String key) {
        wm.stubFor(get(urlEqualTo("/rest/api/2/issue/" + key
                + "?expand=renderedFields&fields=summary,description,issuelinks,subtasks,comment,status,updated"))
                .willReturn(notFound()));
    }

    private void stubRemoteLinks(String key, String fixtureFile) {
        wm.stubFor(get(urlEqualTo("/rest/api/2/issue/" + key + "/remotelink"))
                .willReturn(okJson(fixture("jira", fixtureFile))));
    }

    private void stubConfluencePage(String id, String fixtureFile) {
        wm.stubFor(get(urlEqualTo("/rest/api/content/" + id + "?expand=body.storage,version,space"))
                .willReturn(okJson(fixture("confluence", fixtureFile))));
    }

    private JiraSpecSource source(ScriptedChatModel planner) {
        return source(planner, 1, 20, 10, true);
    }

    private JiraSpecSource source(ScriptedChatModel planner, int followDepth, int maxPages,
            int maxLinkedIssues, boolean confluenceConfigured) {
        RestClient jiraRc = new RestClient("Jira", wm.baseUrl(), "sk-token", "JIRA_PAT",
                Duration.ofSeconds(5), HttpClient.newHttpClient());
        JiraClient jiraClient = new JiraClient(jiraRc, wm.baseUrl());
        ConfluenceClient confluenceClient = null;
        String confluenceHost = null;
        if (confluenceConfigured) {
            RestClient confluenceRc = new RestClient("Confluence", wm.baseUrl(), "sk-token", "CONFLUENCE_PAT",
                    Duration.ofSeconds(5), HttpClient.newHttpClient());
            confluenceClient = new ConfluenceClient(confluenceRc, HttpClient.newHttpClient(), "sk-token",
                    wm.baseUrl(), Duration.ofSeconds(5));
            confluenceHost = URI.create(wm.baseUrl()).getHost();
        }
        return new JiraSpecSource(jiraClient, confluenceClient, confluenceHost, followDepth, maxPages,
                maxLinkedIssues, planner, "deepseek", 16384);
    }

    @Test
    void loadFetchesRootFollowsAConfluenceLinkAndOrdersSourcesRootThenConfluencePage() {
        // Reusing the shared "issue-with-confluence-link.json" fixture is not possible here: it
        // hardcodes host "confluence.corp.local", which this test's WireMock server (bound to
        // 127.0.0.1 on a random port) cannot answer for. This inline body is the same fixture,
        // parameterized with wm.baseUrl() so the harvested link actually round-trips.
        wm.stubFor(get(urlEqualTo("/rest/api/2/issue/PROJ-123"
                + "?expand=renderedFields&fields=summary,description,issuelinks,subtasks,comment,status,updated"))
                .willReturn(okJson("""
                        {"id": "10001", "key": "PROJ-123",
                         "fields": {"summary": "Order API needs pagination", "status": {"name": "In Progress"},
                                    "updated": "2026-08-16T09:12:00.000+0000", "subtasks": [], "issuelinks": [],
                                    "comment": {"comments": []}},
                         "renderedFields": {
                           "description": "<p>See %s/pages/viewpage.action?pageId=65601 for full details.</p>",
                           "comment": {"comments": []}}}
                        """.formatted(wm.baseUrl()))));
        stubRemoteLinks("PROJ-123", "remotelink-empty.json");
        stubConfluencePage("65601", "page.json");
        ScriptedChatModel planner = new ScriptedChatModel(List.of(NORMALIZED_RESPONSE));

        NormalizedSpec spec = source(planner).load("PROJ-123");

        assertThat(spec.id()).isEqualTo("PROJ-123");
        assertThat(spec.sources()).hasSize(2);
        assertThat(spec.sources().get(0)).startsWith("jira PROJ-123 updated ")
                .endsWith(wm.baseUrl() + "/browse/PROJ-123");
        assertThat(spec.sources().get(1)).isEqualTo("confluence 65601 v7 \"Order API spec\" "
                + wm.baseUrl() + "/pages/viewpage.action?pageId=65601");
    }

    @Test
    void loadFollowsANamedHyperlinkWithNoBareUrlInTheDescription() {
        // Task 3 review Fix 2, the point of the fix: a description that links the spec the
        // ordinary way a person does it — a named hyperlink, no bare URL anywhere in the text —
        // must still be followed. Before Fix 2 this page was silently never fetched: the href
        // never survived ConfluenceExtract, so LinkHarvester's text scan never saw anything to
        // harvest, and (worse) it produced no note either.
        wm.stubFor(get(urlEqualTo("/rest/api/2/issue/PROJ-300"
                + "?expand=renderedFields&fields=summary,description,issuelinks,subtasks,comment,status,updated"))
                .willReturn(okJson("""
                        {"id": "10008", "key": "PROJ-300",
                         "fields": {"summary": "Checkout flow needs a spec", "status": {"name": "Open"},
                                    "updated": "2026-08-16T09:12:00.000+0000", "subtasks": [], "issuelinks": [],
                                    "comment": {"comments": []}},
                         "renderedFields": {
                           "description": "<p>Please see <a href=\\"%s/pages/viewpage.action?pageId=65601\\">the spec</a> before starting.</p>",
                           "comment": {"comments": []}}}
                        """.formatted(wm.baseUrl()))));
        stubRemoteLinks("PROJ-300", "remotelink-empty.json");
        stubConfluencePage("65601", "page.json");
        ScriptedChatModel planner = new ScriptedChatModel(List.of(NORMALIZED_RESPONSE));

        NormalizedSpec spec = source(planner).load("PROJ-300");

        assertThat(spec.sources()).hasSize(2);
        assertThat(spec.sources().get(1)).isEqualTo("confluence 65601 v7 \"Order API spec\" "
                + wm.baseUrl() + "/pages/viewpage.action?pageId=65601");
    }

    @Test
    void loadIncludesARemoteLinkOnlyIssuesCommentAsASourceToo() {
        stubIssue("PROJ-200", "issue-remote-link-only.json");
        stubRemoteLinks("PROJ-200", "remotelink-empty.json");
        ScriptedChatModel planner = new ScriptedChatModel(List.of(NORMALIZED_RESPONSE));

        NormalizedSpec spec = source(planner).load("PROJ-200");

        assertThat(spec.sources()).hasSize(2);   // the root issue, then its one comment
        assertThat(spec.sources().get(0)).startsWith("jira PROJ-200 updated ");
        assertThat(spec.sources().get(1)).startsWith("jira-comment PROJ-200 10010 updated ");
    }

    @Test
    void rootIssueSubtaskAndBlockingLinksAreFetchedButRelatesIsIgnored() {
        stubIssue("PROJ-1", "issue-with-subtasks-and-links.json");
        stubRemoteLinks("PROJ-1", "remotelink-empty.json");
        for (String key : List.of("PROJ-2", "PROJ-3", "PROJ-4")) {
            stubIssue(key, "linked-issue.json");
            stubRemoteLinks(key, "remotelink-empty.json");
        }
        ScriptedChatModel planner = new ScriptedChatModel(List.of(NORMALIZED_RESPONSE));

        NormalizedSpec spec = source(planner, 1, 20, 10, false).load("PROJ-1");

        List<String> keys = spec.sources().stream().map(s -> s.split(" ")[1]).toList();
        // root, then subtask PROJ-2, then the two block/depend links PROJ-3 and PROJ-4 — PROJ-5
        // ("Relates") must never appear, it is not requirement material.
        assertThat(keys).containsExactly("PROJ-1", "PROJ-2", "PROJ-3", "PROJ-4");
        wm.verify(0, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                urlEqualTo("/rest/api/2/issue/PROJ-5"
                        + "?expand=renderedFields&fields=summary,description,issuelinks,subtasks,comment,status,updated")));
    }

    @Test
    void linkedIssuesBeyondTheCapAreNotedNotFetched() {
        stubIssue("PROJ-1", "issue-with-subtasks-and-links.json");
        stubRemoteLinks("PROJ-1", "remotelink-empty.json");
        stubIssue("PROJ-2", "linked-issue.json");
        stubRemoteLinks("PROJ-2", "remotelink-empty.json");
        stubIssue("PROJ-3", "linked-issue.json");
        stubRemoteLinks("PROJ-3", "remotelink-empty.json");
        // PROJ-4 must never be fetched: cap is 2 (root's own subtask+link discovery yields
        // PROJ-2, PROJ-3, PROJ-4 in that order; only the first 2 fit under the cap).
        ScriptedChatModel planner = new ScriptedChatModel(List.of(NORMALIZED_RESPONSE));

        NormalizedSpec spec = source(planner, 1, 20, 2, false).load("PROJ-1");

        List<String> keys = spec.sources().stream().map(s -> s.split(" ")[1]).toList();
        assertThat(keys).containsExactly("PROJ-1", "PROJ-2", "PROJ-3");
        assertThat(spec.openQuestions()).extracting(sdd.plan.spec.SpecItem::text)
                .anyMatch(q -> q.contains("[source]") && q.contains("PROJ-4"));
        wm.verify(0, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                urlEqualTo("/rest/api/2/issue/PROJ-4"
                        + "?expand=renderedFields&fields=summary,description,issuelinks,subtasks,comment,status,updated")));
    }

    @Test
    void aFourOhFourOnTheRootIsACleanUserErrorNamingTheKey() {
        stubIssueNotFound("PROJ-999");
        ScriptedChatModel planner = new ScriptedChatModel(List.of());

        assertThatThrownBy(() -> source(planner, 1, 20, 10, false).load("PROJ-999"))
                .isInstanceOf(AtlassianException.class)
                .hasMessage("Jira issue PROJ-999 not found");
    }

    @Test
    void aFourOhFourOnALinkedIssueIsANoteAndTheRootStillSucceeds() {
        stubIssue("PROJ-1", "issue-with-subtasks-and-links.json");
        stubRemoteLinks("PROJ-1", "remotelink-empty.json");
        stubIssueNotFound("PROJ-2");   // the subtask 404s
        stubIssue("PROJ-3", "linked-issue.json");
        stubRemoteLinks("PROJ-3", "remotelink-empty.json");
        stubIssue("PROJ-4", "linked-issue.json");
        stubRemoteLinks("PROJ-4", "remotelink-empty.json");
        ScriptedChatModel planner = new ScriptedChatModel(List.of(NORMALIZED_RESPONSE));

        NormalizedSpec spec = source(planner, 1, 20, 10, false).load("PROJ-1");

        List<String> keys = spec.sources().stream().map(s -> s.split(" ")[1]).toList();
        assertThat(keys).containsExactly("PROJ-1", "PROJ-3", "PROJ-4");   // PROJ-2 missing, not fatal
        assertThat(spec.openQuestions()).extracting(sdd.plan.spec.SpecItem::text)
                .anyMatch(q -> q.contains("[source]") && q.contains("PROJ-2"));
    }

    @Test
    void withoutConfluenceConfiguredAJiraOnlySpecStillSucceedsWithNoSpuriousNotes() {
        stubIssue("PROJ-123", "issue-with-confluence-link.json");   // its text mentions a confluence URL
        stubRemoteLinks("PROJ-123", "remotelink-empty.json");
        ScriptedChatModel planner = new ScriptedChatModel(List.of(NORMALIZED_RESPONSE));

        NormalizedSpec spec = source(planner, 1, 20, 10, false).load("PROJ-123");

        assertThat(spec.sources()).containsExactly(spec.sources().get(0));   // just the one jira bullet
        assertThat(spec.sources()).hasSize(1);
        assertThat(spec.openQuestions()).noneMatch(q -> q.text().contains("[source]"));
    }
}
