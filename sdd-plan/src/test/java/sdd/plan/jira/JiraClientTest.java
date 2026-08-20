package sdd.plan.jira;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import sdd.core.http.AtlassianException;
import sdd.core.http.RestClient;
import sdd.plan.source.SourceDoc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code renderedFields.description}/{@code renderedFields.comment.comments[].body} are HTML fed
 * straight to {@code ConfluenceExtract.extract} — the "key insight" from the Task 3 brief that
 * needs no wiki-markup parser. Fixtures live in {@code src/test/resources/jira} and were
 * hand-written from the documented Data Center v2 issue shape; see the Task 3 report for which
 * fields in them are least certain (in particular: whether {@code renderedFields.comment.comments[]}
 * really does carry {@code id}/{@code updated} alongside the rendered {@code body}, since Atlassian's
 * docs describe {@code expand=renderedFields} in terms of the description field, not comments).
 */
class JiraClientTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    private JiraClient client() {
        RestClient rc = new RestClient("Jira", wm.baseUrl(), "sk-token", "JIRA_PAT",
                Duration.ofSeconds(5), HttpClient.newHttpClient());
        return new JiraClient(rc, wm.baseUrl());
    }

    private static String fixture(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/jira/" + name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void stubIssue(String key, String fixtureFile) {
        wm.stubFor(get(urlEqualTo("/rest/api/2/issue/" + key
                + "?expand=renderedFields&fields=summary,description,issuelinks,subtasks,comment,status,updated"))
                .willReturn(okJson(fixture(fixtureFile))));
    }

    private void stubRemoteLinks(String key, String fixtureFile) {
        wm.stubFor(get(urlEqualTo("/rest/api/2/issue/" + key + "/remotelink"))
                .willReturn(okJson(fixture(fixtureFile))));
    }

    @Test
    void fetchIssueExtractsDescriptionTitleAndUpdatedFromRenderedFields() {
        stubIssue("PROJ-200", "issue-remote-link-only.json");
        stubRemoteLinks("PROJ-200", "remotelink-empty.json");

        JiraClient.Issue issue = client().fetchIssue("PROJ-200");

        SourceDoc doc = issue.issueDoc();
        assertThat(doc.kind()).isEqualTo(SourceDoc.Kind.JIRA_ISSUE);
        assertThat(doc.id()).isEqualTo("PROJ-200");
        assertThat(doc.title()).isEqualTo("Pagination cursor design");
        assertThat(doc.url()).isEqualTo(wm.baseUrl() + "/browse/PROJ-200");
        assertThat(doc.version()).isEqualTo("2026-08-15T14:30:00Z");
        assertThat(doc.text()).contains("plain prose about cursors");
    }

    @Test
    void fetchIssueTurnsEachRenderedCommentIntoItsOwnCommentDoc() {
        stubIssue("PROJ-200", "issue-remote-link-only.json");
        stubRemoteLinks("PROJ-200", "remotelink-empty.json");

        JiraClient.Issue issue = client().fetchIssue("PROJ-200");

        assertThat(issue.commentDocs()).hasSize(1);
        SourceDoc comment = issue.commentDocs().get(0);
        assertThat(comment.kind()).isEqualTo(SourceDoc.Kind.JIRA_COMMENT);
        assertThat(comment.id()).isEqualTo("PROJ-200-comment-10010");
        assertThat(comment.url()).isEqualTo(wm.baseUrl() + "/browse/PROJ-200#comment-10010");
        assertThat(comment.version()).isEqualTo("2026-08-15T15:00:00Z");
        assertThat(comment.text()).contains("linked design doc");
    }

    @Test
    void fetchIssueExtractsAConfluenceLinkFromTheDescriptionText() {
        stubIssue("PROJ-123", "issue-with-confluence-link.json");
        stubRemoteLinks("PROJ-123", "remotelink-empty.json");

        JiraClient.Issue issue = client().fetchIssue("PROJ-123");

        assertThat(issue.issueDoc().text()).contains("https://confluence.corp.local/pages/viewpage.action?pageId=65601");
    }

    @Test
    void fetchIssueHarvestsRemoteLinkObjectUrls() {
        stubIssue("PROJ-123", "issue-with-confluence-link.json");
        stubRemoteLinks("PROJ-123", "remotelink.json");

        JiraClient.Issue issue = client().fetchIssue("PROJ-123");

        assertThat(issue.remoteLinkUrls())
                .containsExactly("https://confluence.corp.local/pages/viewpage.action?pageId=70000");
    }

    @Test
    void fetchIssueHarvestsNamedHyperlinkHrefsFromTheRawDescriptionHtml() {
        // Task 3 review Fix 2: ConfluenceExtract keeps an <a> element's visible text ("the spec")
        // but drops its href — this is the second, independent jsoup pass that recovers it.
        stubIssue("PROJ-300", "issue-with-named-hyperlink.json");
        stubRemoteLinks("PROJ-300", "remotelink-empty.json");

        JiraClient.Issue issue = client().fetchIssue("PROJ-300");

        assertThat(issue.hrefUrls())
                .contains("https://confluence.corp.local/pages/viewpage.action?pageId=65601");
        assertThat(issue.issueDoc().text()).doesNotContain("pageId=65601");   // confirms the href
                                                                               // really is absent from extracted text
    }

    @Test
    void fetchIssueHarvestsNamedHyperlinkHrefsFromCommentBodyHtmlButNotMailtoOrRelativeHrefs() {
        stubIssue("PROJ-300", "issue-with-named-hyperlink.json");
        stubRemoteLinks("PROJ-300", "remotelink-empty.json");

        JiraClient.Issue issue = client().fetchIssue("PROJ-300");

        assertThat(issue.hrefUrls()).contains("https://confluence.corp.local/x/AbCd")
                .doesNotContain("mailto:someone@corp.local")
                .doesNotContain("/relative/path");
    }

    @Test
    void fetchIssueCollectsSubtaskKeys() {
        stubIssue("PROJ-1", "issue-with-subtasks-and-links.json");
        stubRemoteLinks("PROJ-1", "remotelink-empty.json");

        JiraClient.Issue issue = client().fetchIssue("PROJ-1");

        assertThat(issue.subtaskKeys()).containsExactly("PROJ-2");
    }

    @Test
    void fetchIssueKeepsBlockingAndDependingLinksButIgnoresRelates() {
        stubIssue("PROJ-1", "issue-with-subtasks-and-links.json");
        stubRemoteLinks("PROJ-1", "remotelink-empty.json");

        JiraClient.Issue issue = client().fetchIssue("PROJ-1");

        // "Blocks" (outward name itself contains "block") and "Dependency" (contains "depend")
        // both qualify; "Relates" — the PROJ-5 link — must be ignored, it is not requirement material.
        assertThat(issue.linkedIssueKeys()).containsExactly("PROJ-3", "PROJ-4");
    }

    @Test
    void fetchIssueOn404PropagatesTheAtlassianExceptionForTheCallerToTranslate() {
        wm.stubFor(get(urlEqualTo("/rest/api/2/issue/PROJ-999"
                + "?expand=renderedFields&fields=summary,description,issuelinks,subtasks,comment,status,updated"))
                .willReturn(notFound()));

        assertThatThrownBy(() -> client().fetchIssue("PROJ-999"))
                .isInstanceOf(AtlassianException.class)
                .hasMessageContaining("404");
    }

    @Test
    void fetchIssueSendsBearerAuth() {
        stubIssue("PROJ-200", "issue-remote-link-only.json");
        stubRemoteLinks("PROJ-200", "remotelink-empty.json");

        client().fetchIssue("PROJ-200");

        wm.verify(getRequestedFor(urlEqualTo("/rest/api/2/issue/PROJ-200"
                + "?expand=renderedFields&fields=summary,description,issuelinks,subtasks,comment,status,updated"))
                .withHeader("Authorization", com.github.tomakehurst.wiremock.client.WireMock.equalTo("Bearer sk-token")));
    }

    @Test
    void commentPostsABodyOnlyPayloadToTheIssuesCommentEndpoint() {
        wm.stubFor(post(urlEqualTo("/rest/api/2/issue/PROJ-123/comment"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.created()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\": \"10050\"}")));

        client().comment("PROJ-123", "sdd: plan approved for `SPEC-7`");

        wm.verify(postRequestedFor(urlEqualTo("/rest/api/2/issue/PROJ-123/comment"))
                .withHeader("Authorization", com.github.tomakehurst.wiremock.client.WireMock.equalTo("Bearer sk-token"))
                .withRequestBody(equalToJson("{\"body\": \"sdd: plan approved for `SPEC-7`\"}")));
    }

    @Test
    void aRelativeHrefIsResolvedAgainstTheJiraBaseUrlRatherThanDropped() {
        // Data Center commonly serves Jira and Confluence on ONE host under two context paths, and
        // renders a same-origin link relatively. Keeping only absolute hrefs dropped those without
        // a note -- LinkHarvester never saw a candidate, so not one of its "declined" branches
        // fired. A silent drop, in a class whose whole contract is that every declined link is
        // named.
        List<String> hrefs = JiraClient.hrefsIn("""
                <p>see <a href="/confluence/pages/viewpage.action?pageId=65601">the spec</a>
                and <a href="https://elsewhere.example/x">this</a>
                and <a href="#local">this anchor</a>
                and <a href="mailto:someone@corp.local">this address</a></p>""",
                "https://atlassian.corp.local/jira");

        assertThat(hrefs).containsExactly(
                "https://atlassian.corp.local/confluence/pages/viewpage.action?pageId=65601",
                "https://elsewhere.example/x");
    }

    @Test
    void aRelativeHrefIsStillDroppedWhenThereIsNoBaseUrlToResolveAgainst() {
        assertThat(JiraClient.hrefsIn("<a href=\"/confluence/x/AbCd\">s</a>", null)).isEmpty();
    }

    @Test
    void commentIdAndTimestampAreRecoveredFromFieldsWhenRenderedFieldsOmitThem() {
        // Whether renderedFields.comment.comments[] carries id and updated is one of the
        // assumptions no live instance has ever confirmed. fields.comment.comments[] is already
        // requested in ISSUE_FIELDS and was going unused, and it is the same comments in the same
        // order -- so the fallback costs no extra request. Without it a missing id produced the doc
        // id "PROJ-9-comment-" and a Sources bullet with a double space and no timestamp.
        wm.stubFor(get(urlEqualTo(
                "/rest/api/2/issue/PROJ-9?expand=renderedFields"
                + "&fields=summary,description,issuelinks,subtasks,comment,status,updated"))
                .willReturn(okJson(fixture("issue-with-comments.json"))));
        wm.stubFor(get(urlEqualTo("/rest/api/2/issue/PROJ-9/remotelink"))
                .willReturn(okJson(fixture("remotelink-empty.json"))));

        JiraClient.Issue issue = client().fetchIssue("PROJ-9");

        assertThat(issue.commentDocs()).hasSize(2);
        assertThat(issue.commentDocs().get(0).id()).isEqualTo("PROJ-9-comment-30001");
        assertThat(issue.commentDocs().get(0).version()).isEqualTo("2026-08-19T08:30:00Z");
        assertThat(issue.commentDocs().get(1).id()).isEqualTo("PROJ-9-comment-30002");
        assertThat(issue.commentDocs().get(0).text()).isEqualTo("first comment");
    }

    @Test
    void readingOnlyTheFirstPageOfCommentsIsReportedRatherThanSilent() {
        wm.stubFor(get(urlEqualTo(
                "/rest/api/2/issue/PROJ-9?expand=renderedFields"
                + "&fields=summary,description,issuelinks,subtasks,comment,status,updated"))
                .willReturn(okJson(fixture("issue-with-comments.json"))));
        wm.stubFor(get(urlEqualTo("/rest/api/2/issue/PROJ-9/remotelink"))
                .willReturn(okJson(fixture("remotelink-empty.json"))));

        assertThat(client().fetchIssue("PROJ-9").notes())
                .anySatisfy(n -> assertThat(n).contains("only 2 of 7 comments read on PROJ-9"));
    }

    @Test
    void everyIssueLinkTypeSeenIsNamedIncludingTheOnesNotFollowed() {
        // "Followed" is decided by a substring match on "block"/"depend" against a type NAME, and
        // those names are configurable per instance and localised per language. On this fixture --
        // a Russian-language Data Center -- nothing matches, no error is raised, and the blocking
        // dependency simply is not in the plan. The note is what makes that visible instead of
        // leaving it to be discovered by its absence.
        wm.stubFor(get(urlEqualTo(
                "/rest/api/2/issue/PROJ-9?expand=renderedFields"
                + "&fields=summary,description,issuelinks,subtasks,comment,status,updated"))
                .willReturn(okJson(fixture("issue-with-comments.json"))));
        wm.stubFor(get(urlEqualTo("/rest/api/2/issue/PROJ-9/remotelink"))
                .willReturn(okJson(fixture("remotelink-empty.json"))));

        JiraClient.Issue issue = client().fetchIssue("PROJ-9");

        assertThat(issue.linkedIssueKeys()).isEmpty();
        assertThat(issue.notes()).anySatisfy(n -> assertThat(n)
                .contains("issue link types on PROJ-9")
                .contains("блокирует (not followed)")
                .contains("Relates (not followed)"));
    }

    @Test
    void commentOnAServerErrorPropagatesAnAtlassianExceptionForTheCallerToTreatAsBestEffort() {
        wm.stubFor(post(urlEqualTo("/rest/api/2/issue/PROJ-123/comment")).willReturn(serverError()));
        // maxAttempts=1 with a no-op sleeper (the same seam RestClientTest's own
        // failsAfterExhaustingMaxAttemptsOnPersistent5xx test uses) so this assertion does not
        // burn several real seconds on RestClient's exponential backoff.
        RestClient rc = new RestClient("Jira", wm.baseUrl(), "sk-token", "JIRA_PAT",
                Duration.ofSeconds(5), 1, HttpClient.newHttpClient(), millis -> { });

        assertThatThrownBy(() -> new JiraClient(rc, wm.baseUrl()).comment("PROJ-123", "text"))
                .isInstanceOf(AtlassianException.class);
    }
}
