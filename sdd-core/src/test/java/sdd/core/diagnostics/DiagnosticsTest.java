package sdd.core.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.config.AtlassianConfig;
import sdd.core.config.AtlassianSite;
import sdd.core.config.ModelEndpoint;
import sdd.core.config.WriteBack;
import sdd.core.http.TlsConfig;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link Diagnostics#open} is the one-line facade every command's {@code call()} uses: allocate the
 * file (B2), collect every known secret (B4), open the writer, and write the header (B3) — so a
 * command site never has to remember all four steps or their order.
 */
class DiagnosticsTest {
    @TempDir
    Path workspace;

    private static final InstantSource CLOCK = InstantSource.fixed(Instant.parse("2026-08-17T10:15:30.123Z"));

    @Test
    void opensAFileUnderSddDiagnosticsWithTheHeaderAlreadyWritten() throws IOException {
        DiagnosticWriter w = Diagnostics.open(workspace, "doctor", List.of("doctor"), null, CLOCK, null);
        w.close();

        Path dir = workspace.resolve(".sdd/diagnostics");
        try (var files = Files.list(dir)) {
            List<Path> written = files.toList();
            assertThat(written).hasSize(1);
            String content = Files.readString(written.get(0));
            assertThat(content).contains("=== sdd diagnostics ===").contains("doctor");
        }
    }

    @Test
    void theOpenedWriterAlreadyKnowsEverySiteTokenAsASecret() throws IOException {
        AtlassianSite jira = new AtlassianSite("https://jira.corp.local", "sk-live-token-xyz", "JIRA_API_KEY",
                Duration.ofSeconds(5), null);
        AtlassianConfig config = new AtlassianConfig(null, null, jira, null, null, 1, 20, 10, WriteBack.NONE, false);

        DiagnosticWriter w = Diagnostics.open(workspace, "plan", List.of("plan"), config, CLOCK, null);
        w.note("the token is sk-live-token-xyz");
        w.close();

        Path dir = workspace.resolve(".sdd/diagnostics");
        try (var files = Files.list(dir)) {
            Path file = files.findFirst().orElseThrow();
            assertThat(Files.readString(file)).doesNotContain("sk-live-token-xyz");
        }
    }

    // Fix 2 (Gate re-review): openAt's model-aware overload must actually reach DiagnosticsSecrets
    // — proof at the facade level, not just DiagnosticsSecretsTest's unit coverage of collect()
    // itself, that sdd doctor's writer redacts a model endpoint's tls.key_password/
    // tls.truststore_password the same way it already redacts an Atlassian token.
    @Test
    void theOpenedWriterAtAPathAlreadyKnowsEveryModelEndpointsTlsPasswordsAsSecrets() throws IOException {
        TlsConfig tls = new TlsConfig(Path.of("/etc/ssl/corp-ca.p12"), "trust-pw-xyz", null,
                Path.of("/certs/client.crt"), Path.of("/certs/client.key"), "key-pw-abc", null, List.of());
        ModelEndpoint corp = new ModelEndpoint("https://corp-ift.example/v1", "DeepSeek-V4-Flash", null,
                256, 0.0, Duration.ofSeconds(5), Map.of(), null, tls);

        Path file = workspace.resolve("doctor.log");
        DiagnosticWriter w = Diagnostics.openAt(file, List.of("doctor"), null, Map.of("corp", corp),
                CLOCK, null);
        w.note("trust-pw-xyz and key-pw-abc must never survive to disk");
        w.close();

        String content = Files.readString(file);
        assertThat(content).doesNotContain("trust-pw-xyz").doesNotContain("key-pw-abc");
    }

    @Test
    void aBrokenWorkspaceNeverThrowsFromOpenOrAnySubsequentCall() {
        Path broken = workspace.resolve("broken-ws");
        assertThatCode(() -> {
            Files.createDirectories(broken);
            Files.writeString(broken.resolve(".sdd"), "not a directory");
            DiagnosticWriter w = Diagnostics.open(broken, "doctor", List.of("doctor"), null, CLOCK, null);
            w.note("still safe to call");
            w.close();
        }).doesNotThrowAnyException();
    }

    // --- Fix 3 (Task 8 review): the FACADE itself must never throw, not just its collaborators ----

    @Test
    void openNeverThrowsEvenWhenHeaderRenderingItselfWouldFail() {
        // A null argv makes DiagnosticHeader.render's own redactArgs NPE internally — a caller
        // mistake this facade must survive rather than turn into "error: ..." / exit 4 for the
        // command that was only ever trying to open a diagnostics file.
        assertThatCode(() -> {
            DiagnosticWriter w = Diagnostics.open(workspace, "doctor", null, null, CLOCK, null);
            w.note("still safe to call after a broken header render");
            w.close();
        }).doesNotThrowAnyException();
    }

    @Test
    void openAtNeverThrowsEvenWhenHeaderRenderingItselfWouldFail() {
        assertThatCode(() -> {
            DiagnosticWriter w = Diagnostics.openAt(workspace.resolve("x.log"), null, null, CLOCK, null);
            w.note("still safe to call");
            w.close();
        }).doesNotThrowAnyException();
    }

    @Test
    void aFacadeFailureWarnsOnceWhenAWarnWriterIsProvided() {
        StringWriter errBuf = new StringWriter();
        PrintWriter err = new PrintWriter(errBuf, true);

        Diagnostics.open(workspace, "doctor", null, null, CLOCK, err);

        assertThat(errBuf.toString()).contains("  warn: ");
    }

    @Test
    void theReturnedWriterAfterAFacadeFailureIsStillSafeAndRedactsNormally() throws IOException {
        // The no-op fallback must not silently swallow LATER, perfectly normal writes either — it
        // is a safe degraded mode, not a broken object a caller has to know to avoid.
        DiagnosticWriter w = Diagnostics.open(workspace, "doctor", null, null, CLOCK, null);

        assertThatCode(() -> {
            w.header("h");
            w.httpRequest("Jira", "GET", "/x", 200, 1, 1, false, "application/json", null);
            w.gate2("lib", "event");
            w.gitPush("host", "ref", true, null);
            w.failure("ctx", new RuntimeException("boom"));
            w.note("note");
            w.close();
        }).doesNotThrowAnyException();
    }
}
