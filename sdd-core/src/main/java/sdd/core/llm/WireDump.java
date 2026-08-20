package sdd.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * Appends every model request and its reply to a file, when {@code SDD_HTTP_DUMP} names one.
 *
 * <p>This exists because of a specific, expensive failure mode: an endpoint answers HTTP 400
 * {@code "Your request contains invalid JSON syntax"} for a request that is, verifiably, valid
 * JSON. What the gateway means is that some FIELD is unacceptable, and its message names neither
 * the field nor the reason. Diagnosing that from a status code is guessing, and guessing against
 * this particular gateway has already killed five plausible theories, each supported by a
 * clean-looking table. The bytes settle it in one run.
 *
 * <p>Off unless the variable is set, so it costs a null check on every normal run. It writes only
 * bodies, never headers — an {@code Authorization} header cannot leak into the file because the
 * file has no place to put one. That is a stronger guarantee than redaction, which is a rule
 * someone has to remember to keep applying.
 *
 * <p>Deliberately NOT wired into {@code sdd doctor --report}. That file is meant to be handed to
 * someone remote and states that known secret values are redacted from it; a full prompt dump is
 * source code, spec text and estate structure, and belongs to whoever ran the command. This one is
 * opt-in, goes where the operator says, and is mentioned in {@code doctor}'s output only as a hint.
 */
final class WireDump {
    static final String ENV = "SDD_HTTP_DUMP";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    private boolean warned;

    private WireDump(Path file) {
        this.file = file;
    }

    /** The dump named by {@code SDD_HTTP_DUMP}, or null when it is unset or blank. */
    static WireDump fromEnv(Map<String, String> env) {
        String path = env.get(ENV);
        return path == null || path.isBlank() ? null : new WireDump(Path.of(path.trim()));
    }

    /** One completed exchange: what was sent, and what came back with it. */
    void record(String url, String requestBody, int status, String responseBody) {
        ObjectNode entry = JSON.createObjectNode();
        entry.put("url", url);
        entry.set("request", parsed(requestBody));
        entry.put("status", status);
        entry.set("response", parsed(responseBody));
        append(entry);
    }

    /** A request that never got a reply — the body still matters, since it is what was rejected. */
    void recordFailure(String url, String requestBody, String error) {
        ObjectNode entry = JSON.createObjectNode();
        entry.put("url", url);
        entry.set("request", parsed(requestBody));
        entry.put("transport_error", error);
        append(entry);
    }

    /** Embedded as JSON when it is JSON, as a string when it is not — a gateway's error page is
     *  routinely HTML or plain text, and that IS the interesting case here. */
    private static JsonNode parsed(String body) {
        if (body == null) {
            return JSON.nullNode();
        }
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            return JSON.getNodeFactory().textNode(body);
        }
    }

    /**
     * A broken diagnostic must not break the run it is diagnosing, so a write failure never
     * throws — but it says so once on stderr rather than leaving an operator staring at an empty
     * file, which would be a silent failure in the one tool whose whole job is to end guessing.
     */
    private void append(ObjectNode entry) {
        try {
            Files.writeString(file, entry.toString() + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            if (!warned) {
                warned = true;
                System.err.println("sdd: cannot write " + ENV + " file " + file + ": " + e.getMessage());
            }
        }
    }
}
