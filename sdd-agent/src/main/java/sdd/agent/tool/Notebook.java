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

    private final List<Finding> findings = new ArrayList<>();
    private final Set<String> proposalKeys = new LinkedHashSet<>();
    private final List<Proposal> proposals = new ArrayList<>();

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

    public List<Finding> findings() {
        return List.copyOf(findings);
    }

    public List<Proposal> proposals() {
        return List.copyOf(proposals);
    }

    public boolean isEmpty() {
        return findings.isEmpty() && proposals.isEmpty();
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
        return out.toString();
    }
}
