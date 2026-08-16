package sdd.cli;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.created;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.unauthorized;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared Gate-1/Gate-2 comment poster (Task 4 brief section 4): a failed post must never
 * throw — {@code ApproveCommand} and {@code ReviewCommand} both call this AFTER their artifact
 * (plan.json / report.md) is already written, so nothing downstream of {@link JiraWriteBack#post}
 * may change either command's exit code. Exercised directly here (rather than only through the
 * two commands) so every gating branch — none/comment, --no-comment, no keys, per-issue failure,
 * client-build failure — has one cheap, focused test; {@code ApproveCommandTest}/
 * {@code ReviewCommandTest} separately pin that each command actually calls this at the right
 * point with the right body.
 */
class JiraWriteBackTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    @TempDir Path ws;

    private record Out(String out, String err) {}

    private Out postComment(List<String> jiraKeys, boolean noComment, String body) throws Exception {
        StringWriter outSw = new StringWriter();
        StringWriter errSw = new StringWriter();
        JiraWriteBack.post(ws, jiraKeys, noComment, body, new PrintWriter(outSw, true), new PrintWriter(errSw, true));
        return new Out(outSw.toString(), errSw.toString());
    }

    private static final String MODELS = """
            models:
              planner:
                base_url: http://127.0.0.1:1/v1
                model: deepseek-v4-flash
              coder:
                base_url: http://127.0.0.1:1/v1
                model: qwen
            """;

    private void writeYaml(String writeBack) throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), MODELS + """
                atlassian:
                  jira:
                    base_url: %s
                    token: sk-jira
                  write_back: %s
                """.formatted(wm.baseUrl(), writeBack));
    }

    @Test
    void writeBackNonePostsNothingAndPrintsNothing() throws Exception {
        writeYaml("none");

        Out result = postComment(List.of("PROJ-1"), false, "body");

        assertThat(result.out()).isEmpty();
        assertThat(result.err()).isEmpty();
        wm.verify(0, postRequestedFor(urlEqualTo("/rest/api/2/issue/PROJ-1/comment")));
    }

    @Test
    void writeBackCommentPostsOncePerSourceIssue() throws Exception {
        writeYaml("comment");
        wm.stubFor(post(urlEqualTo("/rest/api/2/issue/PROJ-1/comment")).willReturn(created()));
        wm.stubFor(post(urlEqualTo("/rest/api/2/issue/PROJ-2/comment")).willReturn(created()));

        Out result = postComment(List.of("PROJ-1", "PROJ-2"), false, "sdd: plan approved for `SPEC-7`");

        assertThat(result.out()).contains("commented on PROJ-1").contains("commented on PROJ-2");
        assertThat(result.err()).isEmpty();
        wm.verify(postRequestedFor(urlEqualTo("/rest/api/2/issue/PROJ-1/comment")));
        wm.verify(postRequestedFor(urlEqualTo("/rest/api/2/issue/PROJ-2/comment")));
    }

    @Test
    void noCommentFlagSuppressesEvenWhenConfigured() throws Exception {
        writeYaml("comment");

        Out result = postComment(List.of("PROJ-1"), true, "body");

        assertThat(result.out()).isEmpty();
        assertThat(result.err()).isEmpty();
        wm.verify(0, postRequestedFor(urlEqualTo("/rest/api/2/issue/PROJ-1/comment")));
    }

    @Test
    void noJiraKeysPostsNothingAndPrintsNothing() throws Exception {
        writeYaml("comment");

        Out result = postComment(List.of(), false, "body");

        assertThat(result.out()).isEmpty();
        assertThat(result.err()).isEmpty();
    }

    @Test
    void aFailingPostWarnsButNeverThrows() throws Exception {
        // 401, not 5xx: RestClient throws a 401/403 immediately with no backoff, so this stays
        // fast and deterministic — a 5xx here would burn several real seconds retrying, and
        // JiraWriteBack (unlike JiraClientTest) has no seam to inject a no-op Sleeper.
        writeYaml("comment");
        wm.stubFor(post(urlEqualTo("/rest/api/2/issue/PROJ-1/comment")).willReturn(unauthorized()));

        Out result = postComment(List.of("PROJ-1"), false, "body");

        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("  warn: jira comment failed: ");
    }

    @Test
    void aFailureOnOneIssueDoesNotStopTheOthersFromBeingCommented() throws Exception {
        writeYaml("comment");
        wm.stubFor(post(urlEqualTo("/rest/api/2/issue/PROJ-1/comment")).willReturn(unauthorized()));
        wm.stubFor(post(urlEqualTo("/rest/api/2/issue/PROJ-2/comment")).willReturn(created()));

        Out result = postComment(List.of("PROJ-1", "PROJ-2"), false, "body");

        assertThat(result.err()).contains("  warn: jira comment failed: ");
        assertThat(result.out()).contains("commented on PROJ-2").doesNotContain("commented on PROJ-1");
    }

    @Test
    void writeBackCommentWithNoJiraSiteConfiguredWarnsOnceRatherThanThrowing() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), MODELS + """
                atlassian:
                  write_back: comment
                """);

        Out result = postComment(List.of("PROJ-1"), false, "body");

        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("  warn: jira comment failed: ");
    }
}
