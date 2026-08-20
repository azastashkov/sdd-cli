package sdd.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WireDumpTest {
    @TempDir Path dir;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void unsetOrBlankMeansNoDump() {
        assertThat(WireDump.fromEnv(Map.of())).isNull();
        assertThat(WireDump.fromEnv(Map.of(WireDump.ENV, "   "))).isNull();
    }

    @Test
    void anExchangeIsOneJsonLineCarryingBothBodiesAsJson() throws Exception {
        Path file = dir.resolve("wire.jsonl");
        WireDump dump = WireDump.fromEnv(Map.of(WireDump.ENV, file.toString()));

        dump.record("https://gw/v1/chat/completions", "{\"model\":\"m\",\"messages\":[]}",
                400, "{\"status\":400,\"message\":\"Your request contains invalid JSON syntax\"}");

        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(1);
        var entry = JSON.readTree(lines.get(0));
        assertThat(entry.path("url").asText()).isEqualTo("https://gw/v1/chat/completions");
        assertThat(entry.path("status").asInt()).isEqualTo(400);
        // Nested as JSON, not as an escaped string: the whole point is being able to read the
        // field the gateway objected to without unescaping anything first.
        assertThat(entry.path("request").path("model").asText()).isEqualTo("m");
        assertThat(entry.path("response").path("message").asText())
                .isEqualTo("Your request contains invalid JSON syntax");
    }

    // A gateway's rejection is routinely an HTML error page or bare text, and that is exactly the
    // case worth capturing — embedding it must not fail or drop it.
    @Test
    void aNonJsonResponseIsKeptVerbatimAsAString() throws Exception {
        Path file = dir.resolve("wire.jsonl");
        WireDump dump = WireDump.fromEnv(Map.of(WireDump.ENV, file.toString()));

        dump.record("https://gw/v1/chat/completions", "{}", 502, "<html>Bad Gateway</html>");

        var entry = JSON.readTree(Files.readAllLines(file).get(0));
        assertThat(entry.path("response").isTextual()).isTrue();
        assertThat(entry.path("response").asText()).isEqualTo("<html>Bad Gateway</html>");
    }

    @Test
    void aTransportFailureStillRecordsTheRequestThatWasSent() throws Exception {
        Path file = dir.resolve("wire.jsonl");
        WireDump dump = WireDump.fromEnv(Map.of(WireDump.ENV, file.toString()));

        dump.recordFailure("https://gw/v1/chat/completions", "{\"model\":\"m\"}", "Connection reset");

        var entry = JSON.readTree(Files.readAllLines(file).get(0));
        assertThat(entry.path("transport_error").asText()).isEqualTo("Connection reset");
        assertThat(entry.path("request").path("model").asText()).isEqualTo("m");
        assertThat(entry.has("status")).isFalse();
    }

    @Test
    void everyExchangeAppendsRatherThanReplacingTheLast() throws Exception {
        Path file = dir.resolve("wire.jsonl");
        WireDump dump = WireDump.fromEnv(Map.of(WireDump.ENV, file.toString()));

        dump.record("u", "{\"n\":1}", 200, "{}");
        dump.record("u", "{\"n\":2}", 200, "{}");

        assertThat(Files.readAllLines(file)).hasSize(2);
    }

    // A broken diagnostic must not break the run it is diagnosing.
    @Test
    void anUnwritablePathDoesNotThrow() {
        WireDump dump = WireDump.fromEnv(
                Map.of(WireDump.ENV, dir.resolve("no-such-dir").resolve("wire.jsonl").toString()));

        assertThat(dump).isNotNull();
        dump.record("u", "{}", 200, "{}");
    }
}
