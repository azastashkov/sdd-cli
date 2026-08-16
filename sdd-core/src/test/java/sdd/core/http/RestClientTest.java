package sdd.core.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

class RestClientTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    private RestClient client() {
        return new RestClient("Jira", wm.baseUrl(), "sk-token", "JIRA_PAT",
                Duration.ofSeconds(5), HttpClient.newHttpClient());
    }

    private RestClient client(String token, String tokenVar) {
        return new RestClient("Jira", wm.baseUrl(), token, tokenVar,
                Duration.ofSeconds(5), HttpClient.newHttpClient());
    }

    @Test
    void getSendsBearerAuthAndAcceptHeaderAndParsesJson() {
        wm.stubFor(get("/rest/api/2/myself").willReturn(okJson("{\"name\":\"jsmith\"}")));

        JsonNode resp = client().get("/rest/api/2/myself");

        assertThat(resp.path("name").asText()).isEqualTo("jsmith");
        wm.verify(getRequestedFor(urlEqualTo("/rest/api/2/myself"))
                .withHeader("Authorization", equalTo("Bearer sk-token"))
                .withHeader("Accept", equalTo("application/json")));
    }

    @Test
    void postSendsContentTypeAndBody() {
        wm.stubFor(post("/rest/api/2/issue").willReturn(okJson("{\"id\":\"1\"}")));
        ObjectNode body = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        body.put("summary", "hello");

        JsonNode resp = client().post("/rest/api/2/issue", body);

        assertThat(resp.path("id").asText()).isEqualTo("1");
        wm.verify(postRequestedFor(urlEqualTo("/rest/api/2/issue"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("Accept", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.summary", equalTo("hello"))));
    }

    @Test
    void putSendsContentTypeAndBody() {
        wm.stubFor(put("/rest/api/2/issue/1").willReturn(okJson("{\"id\":\"1\"}")));
        ObjectNode body = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        body.put("summary", "updated");

        JsonNode resp = client().put("/rest/api/2/issue/1", body);

        assertThat(resp.path("id").asText()).isEqualTo("1");
        wm.verify(putRequestedFor(urlEqualTo("/rest/api/2/issue/1"))
                .withHeader("Content-Type", equalTo("application/json")));
    }

    @Test
    void postExpectingNoContentDoesNotFailOnAnEmptyBody() {
        wm.stubFor(post("/rest/api/1.0/comments").willReturn(noContent()));
        ObjectNode body = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();

        assertThatCode(() -> client().postExpectingNoContent("/rest/api/1.0/comments", body))
                .doesNotThrowAnyException();
        wm.verify(postRequestedFor(urlEqualTo("/rest/api/1.0/comments")));
    }

    @Test
    void retriesOn5xxThenSucceeds() {
        wm.stubFor(get("/x").inScenario("flaky")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(serverError()).willSetStateTo("second"));
        wm.stubFor(get("/x").inScenario("flaky")
                .whenScenarioStateIs("second").willReturn(okJson("{\"ok\":true}")));

        JsonNode resp = new RestClient("Jira", wm.baseUrl(), "sk-token", "JIRA_PAT", Duration.ofSeconds(5),
                Backoff.DEFAULT_MAX_ATTEMPTS, HttpClient.newHttpClient(), millis -> { }).get("/x");

        assertThat(resp.path("ok").asBoolean()).isTrue();
        wm.verify(2, getRequestedFor(urlEqualTo("/x")));
    }

    @Test
    void retriesOn429WithNoRealSleeping() {
        wm.stubFor(get("/x").inScenario("rate-limit")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(status(429).withHeader("Retry-After", "1")).willSetStateTo("second"));
        wm.stubFor(get("/x").inScenario("rate-limit")
                .whenScenarioStateIs("second").willReturn(okJson("{\"ok\":true}")));

        List<Long> sleeps = new ArrayList<>();
        JsonNode resp = new RestClient("Jira", wm.baseUrl(), "sk-token", "JIRA_PAT", Duration.ofSeconds(5),
                Backoff.DEFAULT_MAX_ATTEMPTS, HttpClient.newHttpClient(), sleeps::add).get("/x");

        assertThat(resp.path("ok").asBoolean()).isTrue();
        assertThat(sleeps).hasSize(1);
        wm.verify(2, getRequestedFor(urlEqualTo("/x")));
    }

    @Test
    void doesNotRetryA4xxOtherThan429() {
        wm.stubFor(get("/x").willReturn(badRequest().withBody("bad request")));

        assertThatThrownBy(() -> client().get("/x"))
                .isInstanceOf(AtlassianException.class)
                .hasMessageContaining("bad request");
        wm.verify(1, getRequestedFor(urlEqualTo("/x")));
    }

    @Test
    void a401NamesTheTokenEnvVarAndSiteAndDoesNotRetry() {
        wm.stubFor(get("/x").willReturn(unauthorized()));

        assertThatThrownBy(() -> client().get("/x"))
                .isInstanceOf(AtlassianException.class)
                .hasMessage("Jira rejected the token in $JIRA_PAT (HTTP 401) — reissue it");
        wm.verify(1, getRequestedFor(urlEqualTo("/x")));
    }

    @Test
    void a403NamesTheTokenEnvVarAndSiteAndDoesNotRetry() {
        wm.stubFor(get("/x").willReturn(forbidden()));

        assertThatThrownBy(() -> client().get("/x"))
                .isInstanceOf(AtlassianException.class)
                .hasMessage("Jira rejected the token in $JIRA_PAT (HTTP 403) — reissue it");
        wm.verify(1, getRequestedFor(urlEqualTo("/x")));
    }

    @Test
    void a401WithALiteralTokenOmitsTheVarName() {
        wm.stubFor(get("/x").willReturn(unauthorized()));

        assertThatThrownBy(() -> client("literal-token", null).get("/x"))
                .isInstanceOf(AtlassianException.class)
                .hasMessage("Jira rejected the configured token (HTTP 401) — reissue it");
    }

    @Test
    void failsAfterExhaustingMaxAttemptsOnPersistent5xx() {
        wm.stubFor(get("/x").willReturn(serverError()));

        assertThatThrownBy(() -> new RestClient("Jira", wm.baseUrl(), "sk-token", "JIRA_PAT",
                Duration.ofSeconds(5), 2, HttpClient.newHttpClient(), millis -> { }).get("/x"))
                .isInstanceOf(AtlassianException.class);
        wm.verify(2, getRequestedFor(urlEqualTo("/x")));
    }

    @Test
    void transportErrorIsWrappedAndRetried() {
        HttpClient refusing = new HttpClient() {
            @Override public java.util.Optional<Duration> connectTimeout() { return java.util.Optional.empty(); }
            @Override public Redirect followRedirects() { return Redirect.NEVER; }
            @Override public java.util.Optional<java.net.ProxySelector> proxy() { return java.util.Optional.empty(); }
            @Override public javax.net.ssl.SSLContext sslContext() { return null; }
            @Override public javax.net.ssl.SSLParameters sslParameters() { return null; }
            @Override public java.util.Optional<java.net.Authenticator> authenticator() { return java.util.Optional.empty(); }
            @Override public java.util.Optional<java.net.CookieHandler> cookieHandler() { return java.util.Optional.empty(); }
            @Override public Version version() { return Version.HTTP_1_1; }
            @Override public java.util.Optional<java.util.concurrent.Executor> executor() { return java.util.Optional.empty(); }
            @Override public <T> java.net.http.HttpResponse<T> send(java.net.http.HttpRequest req,
                    java.net.http.HttpResponse.BodyHandler<T> h) throws java.io.IOException {
                throw new java.net.ConnectException("refused");
            }
            @Override public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                    java.net.http.HttpRequest req, java.net.http.HttpResponse.BodyHandler<T> h) {
                throw new UnsupportedOperationException();
            }
            @Override public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                    java.net.http.HttpRequest req, java.net.http.HttpResponse.BodyHandler<T> h,
                    java.net.http.HttpResponse.PushPromiseHandler<T> p) {
                throw new UnsupportedOperationException();
            }
        };
        List<Long> sleeps = new ArrayList<>();
        RestClient rc = new RestClient("Jira", "http://127.0.0.1:1", "sk-token", "JIRA_PAT",
                Duration.ofSeconds(1), 2, refusing, sleeps::add);

        assertThatThrownBy(() -> rc.get("/x"))
                .isInstanceOf(AtlassianException.class)
                .hasMessageContaining("refused");
        assertThat(sleeps).hasSize(1);
    }
}
