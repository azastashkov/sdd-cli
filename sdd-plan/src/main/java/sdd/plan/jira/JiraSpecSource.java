package sdd.plan.jira;

import sdd.core.http.AtlassianException;
import sdd.core.llm.ChatModel;
import sdd.plan.confluence.ConfluenceNormalizer;
import sdd.plan.source.ConfluencePages;
import sdd.plan.source.LinkHarvester;
import sdd.plan.source.SourceBudget;
import sdd.plan.source.SourceBullet;
import sdd.plan.source.SourceBundle;
import sdd.plan.source.SourceDoc;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecSource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The Jira/Confluence-REST adapter the {@code SpecSource} javadoc has named "Confluence REST API"
 * as a planned seam since v1 (design amendment 2026-08-11). {@link #load} is the "human runs
 * {@code sdd plan PROJ-123}" case: fetch the root issue, follow subtasks/blocking links one level,
 * follow same-host Confluence links up to {@code follow_depth}, assemble the whole thing into one
 * {@link SourceBundle}, and hand it to {@link ConfluenceNormalizer} — same as every other spec
 * source, this produces a reviewable {@code .spec.md} and stops; it never runs impact analysis
 * itself, preserving the Gate-1 human review the Task 3 brief requires.
 *
 * <p>{@link #fetch} and {@link #assemble} are exposed separately (not just folded into
 * {@link #load}) so {@code sdd.cli.PlanCommand}'s general multi-ref path can combine a Jira ref
 * with {@code --text}/Confluence-export refs into ONE bundle — mirroring how
 * {@code ConfluenceExportSource.loadDoc} is public for exactly the same reason.
 */
public final class JiraSpecSource implements SpecSource {
    private final JiraClient jiraClient;
    private final ConfluencePages confluencePages;   // null when atlassian.confluence is not configured
    private final String confluenceHost;              // null iff confluencePages is null
    private final int followDepth;
    private final int maxPages;
    private final int maxLinkedIssues;
    private final ChatModel planner;
    private final String modelName;
    private final int maxTokens;

    public JiraSpecSource(JiraClient jiraClient, ConfluencePages confluencePages, String confluenceHost,
            int followDepth, int maxPages, int maxLinkedIssues, ChatModel planner, String modelName, int maxTokens) {
        this.jiraClient = jiraClient;
        this.confluencePages = confluencePages;
        this.confluenceHost = confluenceHost;
        this.followDepth = followDepth;
        this.maxPages = maxPages;
        this.maxLinkedIssues = maxLinkedIssues;
        this.planner = planner;
        this.modelName = modelName;
        this.maxTokens = maxTokens;
    }

    /** Everything {@link #fetch} pulled in: Jira issue/comment docs (root(s), then linked issues,
     *  each followed immediately by its own comments) and any Confluence pages link-harvested
     *  from them, in fetch order — plus every note earned along the way (a linked issue that
     *  404'd, a link that could not be followed). */
    public record Fetched(List<SourceDoc> docs, List<String> notes) {
    }

    @Override
    public NormalizedSpec load(String ref) {
        Fetched fetched = fetch(List.of(ref));
        return assemble(fetched.docs(), fetched.notes(), ref);
    }

    /**
     * Fetches every root key, then subtasks/blocking-linked issues one level deep (deduped
     * against the roots and each other, capped by {@code max_linked_issues}), then — when
     * Confluence is configured — every same-host Confluence page reachable from any of that
     * Jira material within {@code follow_depth}.
     *
     * <p>A 404 on a root key is a clean user error ({@link AtlassianException}, uncaught): the
     * human asked for that issue by name and it does not exist or is not visible to the PAT. A
     * 404 on a linked issue instead becomes a note — the brief's own reasoning: lacking
     * permission on one child must not make the whole spec unusable.
     */
    public Fetched fetch(List<String> rootKeys) {
        List<SourceDoc> docs = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<String> remoteLinkUrls = new ArrayList<>();
        Set<String> claimed = new LinkedHashSet<>(rootKeys);

        List<JiraClient.Issue> rootIssues = new ArrayList<>();
        for (String key : rootKeys) {
            JiraClient.Issue issue = fetchRoot(key);
            rootIssues.add(issue);
            addIssue(docs, remoteLinkUrls, issue);
        }

        List<String> linkedKeys = new ArrayList<>();
        for (JiraClient.Issue issue : rootIssues) {
            for (String key : subtasksThenLinks(issue)) {
                if (claimed.add(key)) {
                    linkedKeys.add(key);
                }
            }
        }
        for (int i = 0; i < linkedKeys.size(); i++) {
            String key = linkedKeys.get(i);
            if (i >= maxLinkedIssues) {
                notes.add("linked issue not fetched, over the max_linked_issues cap of "
                        + maxLinkedIssues + ": " + key);
                continue;
            }
            try {
                addIssue(docs, remoteLinkUrls, jiraClient.fetchIssue(key));
            } catch (AtlassianException e) {
                notes.add("linked issue " + key + " could not be fetched: " + e.getMessage());
            }
        }

        if (confluencePages != null) {
            LinkHarvester.Result harvested =
                    new LinkHarvester(confluencePages, confluenceHost, followDepth, maxPages)
                            .harvest(docs, remoteLinkUrls);
            docs.addAll(harvested.pages());
            notes.addAll(harvested.notes());
        }
        return new Fetched(docs, notes);
    }

    /**
     * Budget-caps {@code docs}, renders a Sources bullet for every kept document that was
     * actually fetched (never for {@code FREE_TEXT} — the operator typed that, it has no
     * provenance), then delegates to {@link ConfluenceNormalizer}. {@code SourceBudget.apply} is
     * called here AND again inside {@code ConfluenceNormalizer.normalize} — deliberately: applying
     * it to an already-capped bundle is a no-op (see its own javadoc: "if drops.isEmpty() return
     * bundle"), and this is the only way to know which documents actually reached the model, so
     * the Sources list — provenance for what the human is about to review — matches reality
     * instead of listing a document budget-dropped before the model ever saw it.
     */
    public NormalizedSpec assemble(List<SourceDoc> docs, List<String> notes, String fallbackId) {
        SourceBundle capped = SourceBudget.apply(new SourceBundle(docs, notes));
        List<String> sources = new ArrayList<>();
        for (SourceDoc doc : capped.docs()) {
            if (doc.kind() != SourceDoc.Kind.FREE_TEXT) {
                sources.add(SourceBullet.render(doc));
            }
        }
        NormalizedSpec normalized = ConfluenceNormalizer.normalize(capped, planner, modelName, maxTokens, fallbackId);
        return new NormalizedSpec(normalized.id(), normalized.title(), normalized.owner(), normalized.status(),
                normalized.goal(), normalized.background(), normalized.requirements(), normalized.acceptance(),
                normalized.constraints(), normalized.touchpoints(), normalized.outOfScope(),
                normalized.openQuestions(), normalized.attachments(), sources);
    }

    private JiraClient.Issue fetchRoot(String key) {
        try {
            return jiraClient.fetchIssue(key);
        } catch (AtlassianException e) {
            if (isNotFound(e)) {
                throw new AtlassianException("Jira issue " + key + " not found");
            }
            throw e;
        }
    }

    private static void addIssue(List<SourceDoc> docs, List<String> remoteLinkUrls, JiraClient.Issue issue) {
        docs.add(issue.issueDoc());
        docs.addAll(issue.commentDocs());
        remoteLinkUrls.addAll(issue.remoteLinkUrls());
    }

    private static List<String> subtasksThenLinks(JiraClient.Issue issue) {
        List<String> all = new ArrayList<>(issue.subtaskKeys());
        all.addAll(issue.linkedIssueKeys());
        return all;
    }

    /** {@code RestClient}'s {@code AtlassianException} carries no status-code field (see its own
     *  javadoc: one exception per bounded context, no richer shape) — so a 404 is recognised the
     *  same way {@code AtlassianProbe} recognises an SSL failure: inspecting what {@code RestClient}
     *  is documented to put in the message, " HTTP " + status + ": ", rather than adding a status
     *  field to a class outside this task's scope for one caller. */
    private static boolean isNotFound(AtlassianException e) {
        String message = e.getMessage();
        return message != null && message.contains(" HTTP 404:");
    }
}
