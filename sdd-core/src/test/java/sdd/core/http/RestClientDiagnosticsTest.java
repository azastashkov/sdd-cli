package sdd.core.http;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.diagnostics.DiagnosticWriter;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.badRequest;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.unauthorized;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code RestClient} is the single choke point Task 8's diagnostic writer is instrumented into
 * (see the Task 8 brief: "the natural single choke point... prefer instrumenting there over
 * sprinkling calls across four clients"), so every Jira/Confluence/Bitbucket call gets per-request
 * diagnostics for free regardless of which of the four product clients issued it. These tests are
 * additive — {@link RestClientTest} is untouched — and exercise the new diagnostics-aware
 * constructor overload only.
 */
class RestClientDiagnosticsTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    @TempDir
    Path tmp;

    private DiagnosticWriter writer(Path file) {
        return new DiagnosticWriter(file, Set.of("sk-token"),
                InstantSource.fixed(Instant.parse("2026-08-17T10:00:00Z")), null);
    }

    @Test
    void logsASuccessfulRequestWithMethodPathStatusAndDuration() throws IOException {
        wm.stubFor(get("/rest/api/2/myself").willReturn(okJson("{\"name\":\"jsmith\"}")));
        Path file = tmp.resolve("d.log");
        DiagnosticWriter w = writer(file);

        new RestClient("Jira", wm.baseUrl(), "sk-token", "JIRA_PAT", Duration.ofSeconds(5),
                HttpClient.newHttpClient(), w).get("/rest/api/2/myself");
        w.close();

        String content = Files.readString(file);
        assertThat(content).contains("Jira").contains("GET").contains("/rest/api/2/myself").contains("status=200");
    }

    @Test
    void logsTheErrorBodySnippetOnANonTwoXxResponse() throws IOException {
        wm.stubFor(get("/x").willReturn(badRequest().withBody("bad request detail")));
        Path file = tmp.resolve("d.log");
        DiagnosticWriter w = writer(file);

        assertThatThrownBy(() -> new RestClient("Jira", wm.baseUrl(), "sk-token", "JIRA_PAT",
                Duration.ofSeconds(5), HttpClient.newHttpClient(), w).get("/x"))
                .isInstanceOf(AtlassianException.class);
        w.close();

        assertThat(Files.readString(file)).contains("bad request detail").contains("status=400");
    }

    @Test
    void logsAFailureEntryOnTheFinalThrow() throws IOException {
        wm.stubFor(get("/x").willReturn(unauthorized()));
        Path file = tmp.resolve("d.log");
        DiagnosticWriter w = writer(file);

        assertThatThrownBy(() -> new RestClient("Jira", wm.baseUrl(), "sk-token", "JIRA_PAT",
                Duration.ofSeconds(5), HttpClient.newHttpClient(), w).get("/x"))
                .isInstanceOf(AtlassianException.class);
        w.close();

        assertThat(Files.readString(file)).contains("failure:").contains("rejected the token in $JIRA_PAT");
    }

    @Test
    void theTokenNeverReachesTheDiagnosticFileEvenThoughItIsSentOnEveryRequest() throws IOException {
        wm.stubFor(get("/x").willReturn(okJson("{}")));
        Path file = tmp.resolve("d.log");
        DiagnosticWriter w = writer(file);

        new RestClient("Jira", wm.baseUrl(), "sk-token", "JIRA_PAT", Duration.ofSeconds(5),
                HttpClient.newHttpClient(), w).get("/x");
        w.close();

        assertThat(Files.readString(file)).doesNotContain("sk-token");
    }

    @Test
    void aNullDiagnosticsWriterIsANoOpAndBehaviorIsUnchanged() {
        wm.stubFor(get("/rest/api/2/myself").willReturn(okJson("{\"name\":\"jsmith\"}")));

        var resp = new RestClient("Jira", wm.baseUrl(), "sk-token", "JIRA_PAT", Duration.ofSeconds(5),
                HttpClient.newHttpClient(), null).get("/rest/api/2/myself");

        assertThat(resp.path("name").asText()).isEqualTo("jsmith");
    }

    @Test
    void postAlsoLogsThroughTheSameDiagnosticsWriter() throws IOException {
        wm.stubFor(post("/rest/api/2/issue").willReturn(okJson("{\"id\":\"1\"}")));
        Path file = tmp.resolve("d.log");
        DiagnosticWriter w = writer(file);
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("summary", "hello");

        new RestClient("Jira", wm.baseUrl(), "sk-token", "JIRA_PAT", Duration.ofSeconds(5),
                HttpClient.newHttpClient(), w).post("/rest/api/2/issue", body);
        w.close();

        assertThat(Files.readString(file)).contains("POST").contains("/rest/api/2/issue");
    }

    @Test
    void everyPreExistingConstructorOverloadStillWorksWithoutDiagnostics() {
        wm.stubFor(get("/rest/api/2/myself").willReturn(okJson("{\"name\":\"jsmith\"}")));

        var resp = new RestClient("Jira", wm.baseUrl(), "sk-token", "JIRA_PAT", Duration.ofSeconds(5),
                HttpClient.newHttpClient()).get("/rest/api/2/myself");

        assertThat(resp.path("name").asText()).isEqualTo("jsmith");
        wm.verify(getRequestedFor(urlEqualTo("/rest/api/2/myself")));
    }
}
