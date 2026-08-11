package sdd.core.llm;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import sdd.core.config.ModelEndpoint;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
                256, 0.0, Duration.ofSeconds(5));
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
    void omitsAuthorizationHeaderWhenNoApiKey() {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));
        ModelEndpoint ep = new ModelEndpoint(wm.baseUrl() + "/v1", "test-model", null,
                256, 0.0, Duration.ofSeconds(5));
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
                256, 0.0, Duration.ofSeconds(5));
        HttpChatModel model = new HttpChatModel(ep, HttpClient.newHttpClient(), recordedSleeps::add);

        assertThat(model.complete(request()).message().content()).isEqualTo("hello");

        assertThat(recordedSleeps).hasSize(1);
        assertThat(recordedSleeps.get(0)).isLessThanOrEqualTo(60_000L);
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
                256, 0.0, Duration.ofSeconds(5));
        HttpChatModel capped = new HttpChatModel(ep, 2, HttpClient.newHttpClient(), millis -> { });

        assertThatThrownBy(() -> capped.complete(request()))
                .isInstanceOf(ModelException.class);
        wm.verify(2, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }
}
