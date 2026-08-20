package sdd.agent.tool;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What an explorer run established, held in process and read by the caller after the loop returns.
 *
 * <p><b>Why not a slot on {@code AgentOutcome}.</b> {@code done} carries prose, and every other
 * terminal state carries none at all. Keeping the notebook here means a run that hits its turn
 * budget, wedges, or has its context exhausted still hands back everything it had found by then —
 * so <em>every</em> terminal state produces a reviewable proposal rather than only the tidy one.
 * {@code RepoStepRunner} already reads {@code fileTools.appliedEdits()} the same way.
 */
public final class Notebook {

    /**
     * One claim the explorer is making, and the line it read to support it.
     *
     * <p>{@code citedLine} is never supplied by the model — {@link ExploreTools} re-reads the file
     * and copies it. A model that could type the quoted text could type a quotation that does not
     * exist, and a citation nobody can trust is worse than no citation.
     */
    public record Finding(String claim, String citation, String citedLine) {
    }

    /** A touchpoint the explorer proposes; already resolved against the KB when it was recorded. */
    public record Proposal(String kind, String value, String resolvedAs) {
    }

    /**
     * A question the explorer asked a human, and what they said.
     *
     * <p>{@code key} is the normalized form used to recognise a repeat; {@code question} is what
     * the person was actually shown. They are separate because the digest is pinned back into the
     * model's context, and echoing a lowercased, whitespace-collapsed key there would degrade the
     * one record of the exchange that survives eviction.
     *
     * <p>Lives here rather than only in the tool result because a tool result is evictable: the
     * EXPLORE retention policy protects {@code record_finding} by name and nothing else, and
     * {@code evictAll()} — the HTTP-400 recovery path — stubs every result there is. An answer that
     * lived only in a tool result would be one the run loses and then asks a human for a second
     * time, which is the specific annoyance this feature exists to remove. The notebook is pinned
     * after every call and survives both.
     */
    public record Clarification(String key, String question, String answer) {
    }

    private final List<Finding> findings = new ArrayList<>();
    private final Set<String> proposalKeys = new LinkedHashSet<>();
    private final List<Proposal> proposals = new ArrayList<>();
    private final List<Clarification> clarifications = new ArrayList<>();

    /** @return false when this exact claim+citation was already recorded */
    boolean addFinding(Finding finding) {
        for (Finding existing : findings) {
            if (existing.claim().equals(finding.claim())
                    && existing.citation().equals(finding.citation())) {
                return false;
            }
        }
        findings.add(finding);
        return true;
    }

    /** @return false when this kind+value was already proposed */
    boolean addProposal(Proposal proposal) {
        if (!proposalKeys.add(proposal.kind() + ":" + proposal.value())) {
            return false;
        }
        proposals.add(proposal);
        return true;
    }

    /** @return false when this question was already answered — the key is the caller's normalized
     *  form, so a cosmetically reworded repeat is caught too */
    boolean addClarification(Clarification clarification) {
        for (Clarification existing : clarifications) {
            if (existing.key().equals(clarification.key())) {
                return false;
            }
        }
        clarifications.add(clarification);
        return true;
    }

    /** The recorded answer to a question already asked, or null. */
    String answerTo(String key) {
        for (Clarification existing : clarifications) {
            if (existing.key().equals(key)) {
                return existing.answer();
            }
        }
        return null;
    }

    public List<Clarification> clarifications() {
        return List.copyOf(clarifications);
    }

    public List<Finding> findings() {
        return List.copyOf(findings);
    }

    public List<Proposal> proposals() {
        return List.copyOf(proposals);
    }

    public boolean isEmpty() {
        return findings.isEmpty() && proposals.isEmpty() && clarifications.isEmpty();
    }

    /**
     * The running digest pinned into the context window — what the agent has established, in the
     * one form that survives eviction and an HTTP 400.
     */
    public String digest() {
        StringBuilder out = new StringBuilder("## What you have established so far\n");
        if (proposals.isEmpty()) {
            out.append("\nTouchpoints proposed: none yet.\n");
        } else {
            out.append("\nTouchpoints proposed:\n");
            for (Proposal p : proposals) {
                out.append("- ").append(p.kind()).append(": ").append(p.value()).append('\n');
            }
        }
        if (findings.isEmpty()) {
            out.append("\nFindings recorded: none yet.\n");
        } else {
            out.append("\nFindings recorded:\n");
            for (Finding f : findings) {
                out.append("- ").append(f.claim()).append(" — ").append(f.citation()).append('\n');
            }
        }
        if (!clarifications.isEmpty()) {
            out.append("\nAnswers from the human (do not ask these again):\n");
            for (Clarification c : clarifications) {
                out.append("- ").append(c.question()).append(" → ").append(c.answer()).append('\n');
            }
        }
        return out.toString();
    }
}
