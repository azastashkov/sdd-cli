package sdd.cli;

import sdd.core.config.AtlassianConfig;
import sdd.core.config.AtlassianSite;
import sdd.core.config.ConfigException;
import sdd.core.config.ConfigLoader;
import sdd.core.config.SddConfig;
import sdd.core.config.WriteBack;
import sdd.core.diagnostics.DiagnosticWriter;
import sdd.core.http.HttpClients;
import sdd.core.http.RestClient;
import sdd.plan.jira.JiraClient;

import java.io.PrintWriter;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;

/**
 * Task 4's write-back: posts a best-effort comment back to every Jira source issue when a plan is
 * approved (Gate 1, {@code ApproveCommand}) and when a review is written (Gate 2,
 * {@code ReviewCommand}). One method, called identically from both — the Task 4 brief's failure
 * policy ({@code write_back: none} is silent, {@code --no-comment} suppresses, a failed post warns
 * and never touches the caller's exit code) is a single piece of logic, not two, so a divergence
 * between the two gates can never creep in.
 *
 * <p><b>Never throws.</b> Both call sites invoke this AFTER their artifact (plan.json / report.md)
 * is already durably written — the brief's central rule is that a human must never lose that
 * artifact because Jira was down, and the only way to guarantee that from a shared helper is for
 * the helper itself to swallow every failure it can produce, not merely the ones the per-issue
 * loop raises. That is why config loading and Jira-client construction are inside the same
 * try/catch as the per-issue posts, not validated separately beforehand.
 *
 * <p>Config is (re)loaded from {@code workspace} here rather than accepted as an already-loaded
 * {@link SddConfig} parameter, even though {@code ReviewCommand} already has one via
 * {@code RunContext.config()}: {@code ApproveCommand} does not load config at all today, and must
 * not be made to — every existing {@code ApproveCommandTest} case writes no {@code sdd.yml}, and
 * they stay green only because this method is never reached when there are no Jira source keys to
 * comment on (see the empty-{@code jiraKeys} short-circuit below, before any file I/O happens).
 * Loading it twice on the {@code ReviewCommand} path costs one extra, cheap YAML re-parse and keeps
 * both call sites identical.
 */
public final class JiraWriteBack {
    private JiraWriteBack() {
    }

    /**
     * @param workspace   resolved against {@code sdd.yml} — never touched at all when {@code
     *                    noComment} is set or {@code jiraKeys} is empty, so a spec with no Jira
     *                    sources needs no config and produces no output (Task 4 brief section 2/4).
     * @param jiraKeys    distinct Jira issue keys to comment on — see
     *                    {@code SourceBullet.jiraIssueKeys}.
     * @param noComment   the {@code --no-comment} flag; suppresses posting even when {@code
     *                    atlassian.write_back} is {@code comment}.
     * @param commentBody the exact text to post — plain, short, no wiki-markup formatting attempt
     *                    (brief section 1); built by the caller, since Gate 1's and Gate 2's
     *                    wording differ.
     */
    public static void post(Path workspace, List<String> jiraKeys, boolean noComment,
            String commentBody, PrintWriter out, PrintWriter err) {
        post(workspace, jiraKeys, noComment, commentBody, out, err, null);
    }

    /** Same as {@link #post(Path, List, boolean, String, PrintWriter, PrintWriter)}, plus an
     *  optional {@link DiagnosticWriter} (nullable) the comment POST reports to — Task 8. A
     *  separate overload, not a nullable parameter added to the existing one, so {@link
     *  JiraWriteBackTest} keeps compiling unchanged. */
    public static void post(Path workspace, List<String> jiraKeys, boolean noComment,
            String commentBody, PrintWriter out, PrintWriter err, DiagnosticWriter diagnostics) {
        if (noComment || jiraKeys.isEmpty()) {
            return;
        }
        try {
            SddConfig config = ConfigLoader.load(workspace);
            AtlassianConfig atlassian = config.atlassian();
            if (atlassian == null || atlassian.writeBack() != WriteBack.COMMENT) {
                // Default (or explicit "none"): "nothing is posted and nothing is printed" —
                // brief section 4. Not an error, so this is not inside the catch below.
                return;
            }
            JiraClient client = buildClient(atlassian, diagnostics);
            for (String key : jiraKeys) {
                try {
                    client.comment(key, commentBody);
                    out.println("commented on " + key);
                } catch (RuntimeException e) {
                    // Best-effort PER ISSUE: one issue's Jira failure (deleted, no permission,
                    // transient outage) must not stop the comment attempt on the rest.
                    err.println("  warn: jira comment failed: " + e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            // Config missing/invalid, no atlassian.jira site, an unset ${VAR} credential, a bad
            // TLS truststore — none of that was reachable before Gate 1/2 already wrote its
            // artifact, so it is reported the same way a single failed post is: a warning, not a
            // command failure.
            err.println("  warn: jira comment failed: " + e.getMessage());
        }
    }

    /** Mirrors {@code PlanCommand.atlassianRestClient}'s deferred-credential idiom: an unset
     *  {@code ${VAR}} token does not fail config loading, so it is raised here instead, the point a
     *  {@link RestClient} for Jira is actually about to be built. */
    private static JiraClient buildClient(AtlassianConfig atlassian, DiagnosticWriter diagnostics) {
        AtlassianSite site = atlassian.jira();
        if (site == null) {
            throw new ConfigException(
                    "atlassian.write_back is 'comment' but no atlassian.jira is configured");
        }
        if (site.tokenError() != null) {
            throw new ConfigException(site.tokenError());
        }
        HttpClient httpClient = HttpClients.build(atlassian.tls(), atlassian.proxy());
        RestClient restClient = new RestClient("Jira", site.baseUrl(), site.token(), site.tokenVar(),
                site.timeout(), httpClient, diagnostics);
        return new JiraClient(restClient, site.baseUrl());
    }
}
