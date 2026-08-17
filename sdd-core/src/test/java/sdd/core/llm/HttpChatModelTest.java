package sdd.core.llm;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import sdd.core.config.ConfigException;
import sdd.core.config.ModelEndpoint;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

class HttpChatModelTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    private static final String OK_BODY = """
            {"choices":[{"message":{"role":"assistant","content":"hello",
              "tool_calls":[{"id":"c1","type":"function",
                "function":{"name":"read_file","arguments":"{\\"path\\":\\"A.java\\"}"}}]},
              "finish_reason":"tool_calls"}],
             "usage":{"prompt_tokens":42,"completion_tokens":7}}
            """;

    private HttpChatModel model() {
        ModelEndpoint ep = new ModelEndpoint(wm.baseUrl() + "/v1", "test-model", "sk-key",
                256, 0.0, Duration.ofSeconds(5), Map.of());
        return new HttpChatModel(ep, HttpClient.newHttpClient(), millis -> { });
    }

    private static ChatRequest request() {
        return new ChatRequest("test-model", List.of(ChatMessage.user("hi")), List.of(), 256, 0.0);
    }

    @Test
    void parsesContentToolCallsUsageAndFinishReason() {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));

        ChatResponse resp = model().complete(request());

        assertThat(resp.message().content()).isEqualTo("hello");
        assertThat(resp.message().toolCalls()).hasSize(1);
        assertThat(resp.message().toolCalls().get(0).name()).isEqualTo("read_file");
        assertThat(resp.finishReason()).isEqualTo("tool_calls");
        assertThat(resp.usage().promptTokens()).isEqualTo(42);

        wm.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer sk-key"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("test-model")))
                .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("256"))));
    }

    // Phase 2 (model mTLS): HttpChatModel's default-client constructors now build via
    // HttpClients.buildClient(endpoint.tls(), null) instead of inlining HttpClient.newHttpClient().
    // This is the regression that matters most — every existing workspace is api-key-only with no
    // tls block, and this feature must be invisible to it: same client, same headers, same body.
    // Unlike model() above (which always injects a client, bypassing buildClient entirely), this
    // goes through the real 1-arg constructor to exercise the code path that changed.
    @Test
    void defaultClientConstructorSendsAnIdenticalRequestWhenTheEndpointHasNoTls() {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));
        ModelEndpoint ep = new ModelEndpoint(wm.baseUrl() + "/v1", "test-model", "sk-key",
                256, 0.0, Duration.ofSeconds(5), Map.of());
        assertThat(ep.tls()).isNull();

        ChatResponse resp = new HttpChatModel(ep).complete(request());

        assertThat(resp.message().content()).isEqualTo("hello");
        wm.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer sk-key"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("test-model")))
                .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("256"))));
    }

    @Test
    void retriesOn5xxThenSucceeds() {
        wm.stubFor(post("/v1/chat/completions").inScenario("flaky")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(serverError()).willSetStateTo("second"));
        wm.stubFor(post("/v1/chat/completions").inScenario("flaky")
                .whenScenarioStateIs("second").willReturn(okJson(OK_BODY)));

        assertThat(model().complete(request()).message().content()).isEqualTo("hello");
        wm.verify(2, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void doesNotRetry400AndCarriesStatus() {
        wm.stubFor(post("/v1/chat/completions").willReturn(badRequest().withBody("context too long")));

        assertThatThrownBy(() -> model().complete(request()))
                .isInstanceOf(ModelException.class)
                .satisfies(e -> assertThat(((ModelException) e).statusCode()).isEqualTo(400))
                .hasMessageContaining("context too long");
        wm.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void failsAfterSixAttemptsOnPersistent5xx() {
        wm.stubFor(post("/v1/chat/completions").willReturn(serverError()));

        assertThatThrownBy(() -> model().complete(request()))
                .isInstanceOf(ModelException.class);
        wm.verify(6, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void constructingOnAnEndpointWithAnApiKeyErrorFailsWithTheExactDeferredMessage() {
        ModelEndpoint ep = new ModelEndpoint(wm.baseUrl() + "/v1", "test-model", null,
                256, 0.0, Duration.ofSeconds(5), Map.of(),
                "models.flash.api_key: environment variable ROUTER_AI_API_KEY is not set");

        assertThatThrownBy(() -> new HttpChatModel(ep))
                .isInstanceOf(ConfigException.class)
                .hasMessage("models.flash.api_key: environment variable ROUTER_AI_API_KEY is not set");
        wm.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions")));   // failed before any call
    }

    @Test
    void omitsAuthorizationHeaderWhenNoApiKey() {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));
        ModelEndpoint ep = new ModelEndpoint(wm.baseUrl() + "/v1", "test-model", null,
                256, 0.0, Duration.ofSeconds(5), Map.of());
        new HttpChatModel(ep, HttpClient.newHttpClient(), millis -> { }).complete(request());

        wm.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withoutHeader("Authorization"));
    }

    @Test
    void retries429WithDeltaSecondsRetryAfter() {
        wm.stubFor(post("/v1/chat/completions").inScenario("rate-limit")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(status(429).withHeader("Retry-After", "1")).willSetStateTo("second"));
        wm.stubFor(post("/v1/chat/completions").inScenario("rate-limit")
                .whenScenarioStateIs("second").willReturn(okJson(OK_BODY)));

        assertThat(model().complete(request()).message().content()).isEqualTo("hello");
        wm.verify(2, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void retries429WithHttpDateRetryAfterFallsBackToExponential() {
        wm.stubFor(post("/v1/chat/completions").inScenario("date-limit")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(status(429).withHeader("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT")).willSetStateTo("second"));
        wm.stubFor(post("/v1/chat/completions").inScenario("date-limit")
                .whenScenarioStateIs("second").willReturn(okJson(OK_BODY)));

        assertThat(model().complete(request()).message().content()).isEqualTo("hello");
        wm.verify(2, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void capsRetryAfterSleepAtMaxBackoff() {
        wm.stubFor(post("/v1/chat/completions").inScenario("huge-retry-after")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(status(429).withHeader("Retry-After", "86400")).willSetStateTo("second"));
        wm.stubFor(post("/v1/chat/completions").inScenario("huge-retry-after")
                .whenScenarioStateIs("second").willReturn(okJson(OK_BODY)));

        List<Long> recordedSleeps = new ArrayList<>();
        ModelEndpoint ep = new ModelEndpoint(wm.baseUrl() + "/v1", "test-model", "sk-key",
                256, 0.0, Duration.ofSeconds(5), Map.of());
        HttpChatModel model = new HttpChatModel(ep, HttpClient.newHttpClient(), recordedSleeps::add);

        assertThat(model.complete(request()).message().content()).isEqualTo("hello");

        assertThat(recordedSleeps).hasSize(1);
        assertThat(recordedSleeps.get(0)).isLessThanOrEqualTo(60_000L);
    }

    @Test
    void mergesExtraBodyAsTopLevelFields() {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));
        ModelEndpoint ep = new ModelEndpoint(wm.baseUrl() + "/v1", "test-model", "sk-key",
                256, 0.0, Duration.ofSeconds(5),
                Map.of("chat_template_kwargs", Map.of("enable_thinking", false)));
        new HttpChatModel(ep, HttpClient.newHttpClient(), millis -> { }).complete(request());

        wm.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(matchingJsonPath(
                        "$.chat_template_kwargs.enable_thinking", equalTo("false"))));
    }

    @Test
    void extraBodyCannotOverrideCoreFields() {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));
        ModelEndpoint ep = new ModelEndpoint(wm.baseUrl() + "/v1", "test-model", "sk-key",
                256, 0.0, Duration.ofSeconds(5),
                Map.of("model", "evil-model", "max_tokens", 99999, "stream", true));
        new HttpChatModel(ep, HttpClient.newHttpClient(), millis -> { }).complete(request());

        wm.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("test-model")))
                .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("256")))
                .withRequestBody(notMatching(".*\"stream\".*")));
    }

    @Test
    void parsesNullContentFromMissingFieldWithToolCalls() {
        String toolCallsOnlyBody = """
                {"choices":[{"message":{"role":"assistant",
                  "tool_calls":[{"id":"c1","type":"function",
                    "function":{"name":"read_file","arguments":"{\\"path\\":\\"A.java\\"}"}}]},
                  "finish_reason":"tool_calls"}],
                 "usage":{"prompt_tokens":42,"completion_tokens":7}}
                """;
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(toolCallsOnlyBody)));

        ChatResponse resp = model().complete(request());

        assertThat(resp.message().content()).isNull();
        assertThat(resp.message().toolCalls()).hasSize(1);
        assertThat(resp.message().toolCalls().get(0).name()).isEqualTo("read_file");
    }

    @Test
    void attemptCapBoundsRetries() {
        wm.stubFor(post("/v1/chat/completions").willReturn(serverError()));
        ModelEndpoint ep = new ModelEndpoint(wm.baseUrl() + "/v1", "test-model", "sk-key",
                256, 0.0, Duration.ofSeconds(5), Map.of());
        HttpChatModel capped = new HttpChatModel(ep, 2, HttpClient.newHttpClient(), millis -> { });

        assertThatThrownBy(() -> capped.complete(request()))
                .isInstanceOf(ModelException.class);
        wm.verify(2, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void transportErrorWithNullMessageFallsBackToExceptionClassName() {
        HttpClient refusing = new HttpClient() {
            @Override public java.util.Optional<java.time.Duration> connectTimeout() { return java.util.Optional.empty(); }
            @Override public java.net.http.HttpClient.Redirect followRedirects() { return Redirect.NEVER; }
            @Override public java.util.Optional<java.net.ProxySelector> proxy() { return java.util.Optional.empty(); }
            @Override public javax.net.ssl.SSLContext sslContext() { return null; }
            @Override public javax.net.ssl.SSLParameters sslParameters() { return null; }
            @Override public java.util.Optional<java.net.Authenticator> authenticator() { return java.util.Optional.empty(); }
            @Override public java.util.Optional<java.net.CookieHandler> cookieHandler() { return java.util.Optional.empty(); }
            @Override public java.net.http.HttpClient.Version version() { return Version.HTTP_1_1; }
            @Override public java.util.Optional<java.util.concurrent.Executor> executor() { return java.util.Optional.empty(); }
            @Override public <T> java.net.http.HttpResponse<T> send(java.net.http.HttpRequest req,
                    java.net.http.HttpResponse.BodyHandler<T> h2) throws java.io.IOException {
                throw new java.net.ConnectException();   // getMessage() == null
            }
            @Override public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                    java.net.http.HttpRequest req, java.net.http.HttpResponse.BodyHandler<T> h2) {
                throw new UnsupportedOperationException();
            }
            @Override public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                    java.net.http.HttpRequest req, java.net.http.HttpResponse.BodyHandler<T> h2,
                    java.net.http.HttpResponse.PushPromiseHandler<T> p) {
                throw new UnsupportedOperationException();
            }
        };
        ModelEndpoint ep = new ModelEndpoint("http://127.0.0.1:1/v1", "m", null, 16, 0.0, Duration.ofSeconds(1), Map.of());
        HttpChatModel model = new HttpChatModel(ep, 2, refusing, millis -> { });

        assertThatThrownBy(() -> model.complete(request()))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("ConnectException")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("null"));
    }
}
