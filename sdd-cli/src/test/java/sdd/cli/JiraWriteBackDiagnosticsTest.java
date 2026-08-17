package sdd.cli;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.diagnostics.DiagnosticWriter;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code JiraWriteBack.post}'s Task 8 overload: the Jira comment POST it makes should land in the
 * same diagnostics file as everything else a command touches, when the caller has one to give it —
 * {@code ApproveCommand}/{@code ReviewCommand} both do. Additive: {@link JiraWriteBackTest} (the
 * 6-arg overload) is untouched.
 */
class JiraWriteBackDiagnosticsTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    @TempDir Path ws;

    private DiagnosticWriter writer(Path file) {
        return new DiagnosticWriter(file, Set.of("sk-jira-secret"),
                InstantSource.fixed(Instant.parse("2026-08-17T10:00:00Z")), null);
    }

    @Test
    void aPostedCommentIsLoggedThroughTheGivenDiagnosticsWriter() throws IOException {
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p }
                  coder: { base_url: http://y/v1, model: q }
                atlassian:
                  jira:
                    base_url: %s
                    token: sk-jira-secret
                  write_back: comment
                """.formatted(wm.baseUrl()));
        wm.stubFor(post("/rest/api/2/issue/PROJ-1/comment").willReturn(okJson("{\"id\":\"1\"}")));
        Path file = ws.resolve("d.log");
        DiagnosticWriter w = writer(file);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();

        JiraWriteBack.post(ws, List.of("PROJ-1"), false, "hello", new PrintWriter(out, true),
                new PrintWriter(err, true), w);
        w.close();

        String content = Files.readString(file);
        assertThat(content).contains("Jira").contains("PROJ-1").contains("status=200");
        assertThat(content).doesNotContain("sk-jira-secret");
    }

    @Test
    void aNullDiagnosticsWriterIsANoOpAndBehaviorIsUnchanged() throws IOException {
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p }
                  coder: { base_url: http://y/v1, model: q }
                atlassian:
                  jira:
                    base_url: %s
                    token: sk-jira-secret
                  write_back: comment
                """.formatted(wm.baseUrl()));
        wm.stubFor(post("/rest/api/2/issue/PROJ-1/comment").willReturn(okJson("{\"id\":\"1\"}")));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();

        JiraWriteBack.post(ws, List.of("PROJ-1"), false, "hello", new PrintWriter(out, true),
                new PrintWriter(err, true), null);

        assertThat(out.toString()).contains("commented on PROJ-1");
    }
}
