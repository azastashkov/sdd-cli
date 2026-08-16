package sdd.core.http;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Fix 6 (Task 8 review): the one {@code hostOf} shared by {@code AtlassianProbe}'s TLS-failure
 *  diagnostics, {@code DiagnosticHeader}'s config summary, and {@code
 *  sdd.cli.review.BitbucketClients}' git-push diagnostics — previously three near-identical private
 *  copies. */
class UrlHostsTest {
    @Test
    void extractsTheHostFromAnOrdinaryUrl() {
        assertThat(UrlHosts.hostOf("https://jira.corp.local:8443/rest/api/2")).isEqualTo("jira.corp.local");
    }

    @Test
    void returnsTheOriginalStringWhenItIsNotParseableAsAUri() {
        assertThat(UrlHosts.hostOf("not a url at all")).isEqualTo("not a url at all");
    }

    @Test
    void returnsTheOriginalStringWhenTheUriHasNoHostComponent() {
        assertThat(UrlHosts.hostOf("/just/a/path")).isEqualTo("/just/a/path");
    }

    @Test
    void returnsTheLiteralStringNullRatherThanThrowingOnANullInput() {
        assertThat(UrlHosts.hostOf(null)).isEqualTo("null");
    }
}
