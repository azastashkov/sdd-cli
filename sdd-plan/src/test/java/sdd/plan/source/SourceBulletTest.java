package sdd.plan.source;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the exact grammar of one "## Sources" bullet — see {@link SourceBullet}'s javadoc. The
 * two examples in the Task 3 brief (a jira issue line and a confluence page line) are asserted
 * verbatim; the jira-comment shape is this class's own invention (disclosed in the Task 3
 * report), so it gets the same verbatim treatment to pin it down.
 */
class SourceBulletTest {
    @Test
    void rendersAJiraIssueBulletExactlyAsTheBriefShows() {
        SourceDoc doc = new SourceDoc(SourceDoc.Kind.JIRA_ISSUE, "PROJ-123",
                "https://jira.corp.local/browse/PROJ-123", "Order API", "2026-08-16T09:12:00Z",
                "text", List.of());

        assertThat(SourceBullet.render(doc)).isEqualTo(
                "jira PROJ-123 updated 2026-08-16T09:12:00Z https://jira.corp.local/browse/PROJ-123");
    }

    @Test
    void rendersAConfluencePageBulletExactlyAsTheBriefShows() {
        SourceDoc doc = new SourceDoc(SourceDoc.Kind.CONFLUENCE_PAGE, "65601",
                "https://confluence.corp.local/pages/viewpage.action?pageId=65601",
                "Order API spec", "7", "text", List.of());

        assertThat(SourceBullet.render(doc)).isEqualTo(
                "confluence 65601 v7 \"Order API spec\" https://confluence.corp.local/pages/viewpage.action?pageId=65601");
    }

    @Test
    void rendersAJiraCommentBulletUsingTheKeyAndCommentIdEncodedInTheDocId() {
        SourceDoc doc = new SourceDoc(SourceDoc.Kind.JIRA_COMMENT, "PROJ-123-comment-10001",
                "https://jira.corp.local/browse/PROJ-123#comment-10001", null, "2026-08-16T09:20:00Z",
                "text", List.of());

        assertThat(SourceBullet.render(doc)).isEqualTo(
                "jira-comment PROJ-123 10001 updated 2026-08-16T09:20:00Z "
                        + "https://jira.corp.local/browse/PROJ-123#comment-10001");
    }

    @Test
    void missingVersionOrUrlFallsBackRatherThanRenderingTheWordNull() {
        SourceDoc doc = new SourceDoc(SourceDoc.Kind.JIRA_ISSUE, "PROJ-9", null, "T", null, "text", List.of());

        assertThat(SourceBullet.render(doc)).isEqualTo("jira PROJ-9 updated unknown ");
    }

    @Test
    void freeTextHasNoSourceBulletByDesignSinceItWasNeverFetched() {
        SourceDoc doc = new SourceDoc(SourceDoc.Kind.FREE_TEXT, "text-1", null, null, null, "text", List.of());

        assertThatThrownBy(() -> SourceBullet.render(doc)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jiraIssueKeysParsesRootIssueBulletsOnly() {
        List<String> sources = List.of(
                "jira PROJ-123 updated 2026-08-16T09:12:00Z https://jira.corp.local/browse/PROJ-123",
                "jira-comment PROJ-123 10001 updated 2026-08-16T09:20:00Z "
                        + "https://jira.corp.local/browse/PROJ-123#comment-10001",
                "confluence 65601 v7 \"Order API spec\" https://confluence.corp.local/pages/viewpage.action?pageId=65601");

        assertThat(SourceBullet.jiraIssueKeys(sources)).containsExactly("PROJ-123");
    }

    @Test
    void jiraIssueKeysDedupesAndPreservesFirstAppearanceOrder() {
        List<String> sources = List.of(
                "jira PROJ-2 updated unknown https://jira.corp.local/browse/PROJ-2",
                "jira PROJ-1 updated unknown https://jira.corp.local/browse/PROJ-1",
                "jira PROJ-2 updated unknown https://jira.corp.local/browse/PROJ-2");

        assertThat(SourceBullet.jiraIssueKeys(sources)).containsExactly("PROJ-2", "PROJ-1");
    }

    @Test
    void jiraIssueKeysIsEmptyWhenThereAreNoJiraSources() {
        assertThat(SourceBullet.jiraIssueKeys(List.of(
                "confluence 65601 v7 \"Order API spec\" https://confluence.corp.local/pages/viewpage.action?pageId=65601")))
                .isEmpty();
        assertThat(SourceBullet.jiraIssueKeys(List.of())).isEmpty();
    }

    // --- jiraIssueUrls (Task 5: the pull-request description links back to the source issue) ---

    @Test
    void jiraIssueUrlsMapsEachRootIssueKeyToItsUrl() {
        List<String> sources = List.of(
                "jira PROJ-123 updated 2026-08-16T09:12:00Z https://jira.corp.local/browse/PROJ-123",
                "jira-comment PROJ-123 10001 updated 2026-08-16T09:20:00Z "
                        + "https://jira.corp.local/browse/PROJ-123#comment-10001",
                "confluence 65601 v7 \"Order API spec\" https://confluence.corp.local/pages/viewpage.action?pageId=65601");

        assertThat(SourceBullet.jiraIssueUrls(sources))
                .containsExactly(java.util.Map.entry("PROJ-123", "https://jira.corp.local/browse/PROJ-123"));
    }

    @Test
    void jiraIssueUrlsKeepsTheFirstUrlSeenForARepeatedKey() {
        List<String> sources = List.of(
                "jira PROJ-2 updated 2026-08-16T09:00:00Z https://jira.corp.local/browse/PROJ-2",
                "jira PROJ-2 updated 2026-08-16T10:00:00Z https://jira.corp.local/browse/PROJ-2-again");

        assertThat(SourceBullet.jiraIssueUrls(sources)).containsEntry("PROJ-2",
                "https://jira.corp.local/browse/PROJ-2");
    }

    @Test
    void jiraIssueUrlsIsEmptyWhenThereAreNoJiraSources() {
        assertThat(SourceBullet.jiraIssueUrls(List.of())).isEmpty();
        assertThat(SourceBullet.jiraIssueUrls(List.of(
                "confluence 65601 v7 \"Order API spec\" https://confluence.corp.local/pages/viewpage.action?pageId=65601")))
                .isEmpty();
    }
}
