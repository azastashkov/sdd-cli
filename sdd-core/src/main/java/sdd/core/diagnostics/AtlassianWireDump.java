package sdd.core.diagnostics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Every Atlassian HTTP exchange, as JSONL, when {@code SDD_ATLASSIAN_DUMP} names a file.
 *
 * <p>Nothing in this codebase has ever reached a real Jira, Confluence or Bitbucket Data Center
 * instance ({@code docs/commands.md}). When it finally does, the failures will be about wire
 * details no status code reports: whether {@code renderedFields} came back at all, whether a
 * corporate proxy rewrote a tiny link's redirect, what shape {@code Retry-After} arrived in. The
 * lesson from the last integration against a closed corporate gateway was recorded plainly: print
 * the bytes before theorising.
 *
 * <h2>Why not {@code SDD_HTTP_DUMP}</h2>
 *
 * <p>That variable dumps MODEL traffic, whose javadoc deliberately keeps whole prompts out of
 * {@code doctor --report}. Sharing one variable would interleave a full planner prompt — spec text
 * and estate structure — with Atlassian traffic in a single file, silently changing what handing
 * that file to somebody means.
 *
 * <h2>Why bodies-only would not be enough here</h2>
 *
 * <p>{@code WireDump} argues, correctly for its own traffic, that writing only bodies is stronger
 * than redaction because the file then has no place to put an {@code Authorization} header. Neither
 * half of that argument survives the move to Atlassian:
 *
 * <ul>
 *   <li><b>Bodies are not safe.</b> The rule guards against <em>sdd's own</em> header leaking. It
 *       says nothing about credentials living inside the payload — and a Jira description or
 *       comment is free-form human prose that routinely contains a pasted {@code curl -H
 *       "Authorization: Bearer …"} reproduction or a connection string. The very first request the
 *       runbook makes returns exactly that field. So everything written here goes through
 *       {@link Redactor}, whose {@code Authorization:}-shaped rule then also catches a token
 *       somebody typed into a ticket.
 *   <li><b>Bodies are not sufficient.</b> The three likeliest failures live entirely in RESPONSE
 *       headers: {@code Location} for tiny-link resolution, {@code Retry-After} for backoff, and
 *       {@code Content-Type} for a proxy that rewrote the response. A tiny-link probe uses
 *       {@code BodyHandlers.discarding()} and so has no body to dump at all.
 * </ul>
 *
 * <p>Request headers are still never written, keeping {@code WireDump}'s structural guarantee
 * verbatim. Response headers are an <b>allowlist</b>, never a denylist, so a future {@code
 * Set-Cookie} cannot be admitted by forgetting to exclude it. {@code WWW-Authenticate} and {@code
 * Proxy-Authenticate} are recorded by NAME with their value elided: which scheme a proxy demands is
 * the diagnostic, and the challenge itself is not.
 */
public final class AtlassianWireDump {
    public static final String ENV = "SDD_ATLASSIAN_DUMP";

    /**
     * Response headers worth recording. Small on purpose: every entry here is one somebody had to
     * justify, which is the property a denylist cannot have.
     */
    private static final Set<String> HEADERS = Set.of(
            "location", "retry-after", "content-type", "content-length",
            "x-ausername", "x-seraph-loginreason", "via", "x-cache");

    /** Recorded as present, never with their value. */
    private static final Set<String> NAME_ONLY = Set.of("www-authenticate", "proxy-authenticate");

    private static final String BANNER =
            "sdd Atlassian wire dump. Known credentials are redacted. The issue text, page content, "
            + "user names and hostnames below are NOT: this file contains whatever your Jira and "
            + "Confluence contain. Read it before sharing it.";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    private final Redactor redactor;
    private boolean started;

    private AtlassianWireDump(Path file, Redactor redactor) {
        this.file = file;
        this.redactor = redactor;
    }

    /**
     * The dump named by {@code SDD_ATLASSIAN_DUMP}, or null when unset or blank.
     *
     * <p>{@code 1} or {@code true} means "somewhere sensible": {@code .sdd/atlassian-wire.jsonl}
     * under the workspace, which {@code .gitignore} already covers. An explicit path is honoured
     * as given — including one inside a repository, which is the operator's call to make.
     */
    public static AtlassianWireDump fromEnv(Map<String, String> env, Path workspace,
                                            Collection<String> secrets) {
        String value = env.get(ENV);
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        Path target = "1".equals(trimmed) || "true".equalsIgnoreCase(trimmed)
                ? workspace.resolve(".sdd").resolve("atlassian-wire.jsonl")
                : Path.of(trimmed);
        return new AtlassianWireDump(target, Redactor.of(secrets));
    }

    /** One completed exchange. */
    public void record(String method, String url, String requestBody, int status,
                       Map<String, List<String>> responseHeaders, String responseBody) {
        ObjectNode entry = JSON.createObjectNode();
        entry.put("method", method);
        entry.put("url", scrub(url));
        entry.set("request", body(requestBody));
        entry.put("status", status);
        entry.set("response_headers", headers(responseHeaders));
        entry.set("response", body(responseBody));
        append(entry);
    }

    /** A request that never got a reply. The error is the whole record. */
    public void recordFailure(String method, String url, String requestBody, String error) {
        ObjectNode entry = JSON.createObjectNode();
        entry.put("method", method);
        entry.put("url", scrub(url));
        entry.set("request", body(requestBody));
        entry.put("transport_error", scrub(error));
        append(entry);
    }

    private ObjectNode headers(Map<String, List<String>> responseHeaders) {
        ObjectNode out = JSON.createObjectNode();
        if (responseHeaders == null) {
            return out;
        }
        // Sorted, so two runs of the same exchange diff cleanly.
        for (String name : new java.util.TreeSet<>(responseHeaders.keySet())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (NAME_ONLY.contains(lower)) {
                out.put(lower, "<present, value elided>");
            } else if (HEADERS.contains(lower)) {
                out.put(lower, scrub(String.join(", ", responseHeaders.get(name))));
            }
        }
        return out;
    }

    /** JSON when it is JSON, a string when it is not — an error page is routinely HTML, and that
     *  is exactly the case worth reading. Scrubbed either way. */
    private JsonNode body(String raw) {
        if (raw == null) {
            return JSON.nullNode();
        }
        String scrubbed = scrub(raw);
        try {
            return JSON.readTree(scrubbed);
        } catch (IOException e) {
            return JSON.getNodeFactory().textNode(scrubbed);
        }
    }

    private String scrub(String text) {
        return text == null ? null : redactor.scrub(text);
    }

    private void append(ObjectNode entry) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            StringBuilder line = new StringBuilder();
            if (!started) {
                started = true;
                ObjectNode banner = JSON.createObjectNode();
                banner.put("sdd_atlassian_wire_dump", BANNER);
                line.append(JSON.writeValueAsString(banner)).append(System.lineSeparator());
            }
            line.append(JSON.writeValueAsString(entry)).append(System.lineSeparator());
            Files.writeString(file, line.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // A diagnostic that ends the run it was enabled to diagnose is worse than no
            // diagnostic. Reported once by the caller's own channels, never thrown.
            throw new UncheckedIOException("cannot write " + ENV + " file " + file, e);
        }
    }

    /** Uniqueness of the configured secret set, for callers assembling it from several sites. */
    public static Set<String> secrets(String... values) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(v);
            }
        }
        return out;
    }
}
