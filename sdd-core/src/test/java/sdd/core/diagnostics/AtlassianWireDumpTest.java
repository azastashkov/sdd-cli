package sdd.core.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AtlassianWireDumpTest {
    @TempDir Path ws;

    private static final String TOKEN = "sk-jira-abcdef123456";

    private AtlassianWireDump dump(Path target) {
        return AtlassianWireDump.fromEnv(Map.of(AtlassianWireDump.ENV, target.toString()), ws,
                Set.of(TOKEN));
    }

    private String contents(Path target) throws Exception {
        return Files.readString(target);
    }

    @Test
    void unsetOrBlankMeansNoDumpAtAll() {
        assertThat(AtlassianWireDump.fromEnv(Map.of(), ws, Set.of())).isNull();
        assertThat(AtlassianWireDump.fromEnv(Map.of(AtlassianWireDump.ENV, "  "), ws, Set.of()))
                .isNull();
    }

    @Test
    void aBareOneMeansTheGitignoredSddDirectory() {
        AtlassianWireDump d = AtlassianWireDump.fromEnv(Map.of(AtlassianWireDump.ENV, "1"), ws,
                Set.of());
        d.record("GET", "https://jira/x", null, 200, Map.of(), "{}");
        assertThat(ws.resolve(".sdd/atlassian-wire.jsonl")).exists();
    }

    @Test
    void aTokenPlantedInAResponseBodyIsRedacted() throws Exception {
        // THE assertion. WireDump's bodies-only rule guards against sdd's OWN Authorization header
        // leaking; it says nothing about a credential living inside the payload. A Jira comment is
        // free-form human prose, and someone pasting a curl reproduction into a ticket is ordinary.
        // If this ever fails, the argument for writing bodies at all collapses.
        Path target = ws.resolve("wire.jsonl");
        dump(target).record("GET", "https://jira.corp.local/rest/api/2/issue/PROJ-1", null, 200,
                Map.of("Content-Type", List.of("application/json")),
                "{\"body\":\"repro: curl -H 'Authorization: Bearer " + TOKEN + "' https://jira\"}");

        assertThat(contents(target)).doesNotContain(TOKEN);
    }

    @Test
    void theRequestHeadersAreNeverWritten() throws Exception {
        // WireDump's structural guarantee, kept verbatim: this class has no parameter for request
        // headers, so an Authorization header cannot reach the file even by mistake. Pinned as a
        // test rather than left as a convention, because a convention is what someone adds a
        // parameter to.
        Path target = ws.resolve("wire.jsonl");
        dump(target).record("POST", "https://jira.corp.local/rest/api/2/issue/PROJ-1/comment",
                "{\"body\":\"plan approved\"}", 201, Map.of(), "{\"id\":\"1\"}");

        assertThat(contents(target))
                .doesNotContain("Authorization")
                .doesNotContain(TOKEN)
                .contains("plan approved");
    }

    @Test
    void allowlistedResponseHeadersAreCapturedAndEverythingElseIsNot() throws Exception {
        Path target = ws.resolve("wire.jsonl");
        dump(target).record("GET", "https://confluence.corp.local/x/AbCd", null, 302,
                Map.of("Location", List.of("/pages/viewpage.action?pageId=65601"),
                        "Retry-After", List.of("120"),
                        "Set-Cookie", List.of("JSESSIONID=deadbeef; HttpOnly"),
                        "X-Secret-Internal", List.of("nope")),
                null);

        String out = contents(target);
        // Location is why this dump records headers at all: the tiny-link probe uses
        // BodyHandlers.discarding(), so it has no body and the header IS the record.
        assertThat(out).contains("location").contains("pageId=65601");
        assertThat(out).contains("retry-after").contains("120");
        // Allowlist, never a denylist -- so a header nobody thought about is out by default.
        assertThat(out).doesNotContain("JSESSIONID").doesNotContain("deadbeef")
                .doesNotContain("x-secret-internal").doesNotContain("nope");
    }

    @Test
    void anAuthenticationChallengeIsRecordedByNameWithoutItsValue() throws Exception {
        // Which scheme a proxy demands is the diagnostic for a 407; the challenge itself is not.
        Path target = ws.resolve("wire.jsonl");
        dump(target).record("GET", "https://jira.corp.local/rest/api/2/myself", null, 407,
                Map.of("Proxy-Authenticate", List.of("Negotiate realm=\"corp\", nonce=\"xyzzy\"")),
                null);

        assertThat(contents(target))
                .contains("proxy-authenticate")
                .contains("value elided")
                .doesNotContain("xyzzy");
    }

    @Test
    void aTransportFailureIsRecordedBecauseThatIsTheCaseTheDumpExistsFor() throws Exception {
        Path target = ws.resolve("wire.jsonl");
        dump(target).recordFailure("GET", "https://jira.corp.local/rest/api/2/myself", null,
                "javax.net.ssl.SSLHandshakeException: PKIX path building failed");

        assertThat(contents(target)).contains("transport_error").contains("PKIX path building");
    }

    @Test
    void theFileOpensWithABannerSayingWhatIsAndIsNotRedacted() throws Exception {
        Path target = ws.resolve("wire.jsonl");
        AtlassianWireDump d = dump(target);
        d.record("GET", "https://jira/a", null, 200, Map.of(), "{}");
        d.record("GET", "https://jira/b", null, 200, Map.of(), "{}");

        List<String> lines = Files.readAllLines(target);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).contains("Known credentials are redacted")
                .contains("are NOT");
        // Written once, not before every entry.
        assertThat(lines.get(1)).contains("https://jira/a").doesNotContain("redacted");
    }

    @Test
    void anHtmlErrorPageIsKeptAsTextRatherThanDropped() throws Exception {
        // A gateway or proxy that rewrote the response answers in HTML, and that IS the
        // interesting case -- the status code alone never says who answered.
        Path target = ws.resolve("wire.jsonl");
        dump(target).record("GET", "https://jira/x", null, 502,
                Map.of("Content-Type", List.of("text/html")),
                "<html><title>502 Bad Gateway</title></html>");

        assertThat(contents(target)).contains("502 Bad Gateway");
    }
}
