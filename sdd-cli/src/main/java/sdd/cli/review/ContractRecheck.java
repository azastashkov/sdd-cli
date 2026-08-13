package sdd.cli.review;

import sdd.cli.implement.ContractActualizer;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunGit;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gate-2 contract re-check (design line 66): re-extract each green provider's real interface and
 * diff it against the body the run actualized. Mismatches are WARNINGS a human adjudicates — they
 * never fail the review. Providers that did not go green are skipped: nothing was checkpointed.
 */
public final class ContractRecheck {
    public enum Status { MATCHES, TRUNCATED_MATCH, DRIFTED, MISSING_RECORD, NOT_EXTRACTABLE }

    /**
     * {@code extractedFrom} is the provider's {@code RunGit.currentBranch} at extraction time
     * ({@code "detached:<sha>"} when empty). Under a rebuild pass it's the checkpoint branch;
     * under {@code --no-rebuild} nothing is checked out first, so it's whatever branch the human
     * was standing on — recording it turns an otherwise-inexplicable DRIFTED into something
     * adjudicable.
     */
    public record Finding(String contractId, String provider, String kind, Status status,
                          String detail, String extractedFrom) {
    }

    private ContractRecheck() {
    }

    public static List<Finding> check(PlanModel plan, RunState state, Map<String, Path> repoPaths,
                                      RunStore store, Path runDir) {
        // Group by provider first so a provider with N contracts gets ONE actualize() call
        // (one tree walk/parse) instead of N — actualize() already loops the provided list.
        Map<String, List<PlanModel.PlanContract>> byProvider = new LinkedHashMap<>();
        for (PlanModel.PlanContract contract : plan.contracts()) {
            if (state.stateOf(contract.provider()) == RepoState.SUCCEEDED
                    && repoPaths.get(contract.provider()) != null) {
                byProvider.computeIfAbsent(contract.provider(), p -> new ArrayList<>()).add(contract);
            }
        }
        Map<String, Map<String, String>> freshByProvider = new LinkedHashMap<>();
        Map<String, String> extractedFromByProvider = new LinkedHashMap<>();
        byProvider.forEach((provider, contracts) -> {
            freshByProvider.put(provider, ContractActualizer.actualize(repoPaths.get(provider), contracts));
            extractedFromByProvider.put(provider, currentPosition(repoPaths.get(provider)));
        });

        List<Finding> findings = new ArrayList<>();
        for (PlanModel.PlanContract contract : plan.contracts()) {
            if (state.stateOf(contract.provider()) != RepoState.SUCCEEDED) {
                continue;
            }
            if (repoPaths.get(contract.provider()) == null) {
                findings.add(new Finding(contract.id(), contract.provider(), contract.kind(),
                        Status.NOT_EXTRACTABLE,
                        "provider " + contract.provider() + " has no checkout path in the knowledge base",
                        null));
                continue;
            }
            String extractedFrom = extractedFromByProvider.get(contract.provider());
            String fresh = freshByProvider.get(contract.provider()).get(contract.id());
            String recorded = store.readContract(runDir, contract.id());
            if (fresh == null || fresh.isBlank()) {
                findings.add(new Finding(contract.id(), contract.provider(), contract.kind(),
                        Status.NOT_EXTRACTABLE,
                        "nothing extractable for kind " + contract.kind() + " in " + contract.provider(),
                        extractedFrom));
            } else if (recorded == null) {
                findings.add(new Finding(contract.id(), contract.provider(), contract.kind(),
                        Status.MISSING_RECORD,
                        "no actualized contract was recorded for this provider", extractedFrom));
            } else {
                List<String> freshNorm = normalize(fresh);
                List<String> recordedNorm = normalize(recorded);
                if (freshNorm.equals(recordedNorm)) {
                    // Both sides pass through ContractActualizer's shared 4000-char cap. Equal
                    // truncated bodies don't prove the real interfaces match — a real change
                    // landing past the cut is invisible to this comparison.
                    boolean truncated = endsWithTruncationMarker(freshNorm)
                            && endsWithTruncationMarker(recordedNorm);
                    findings.add(new Finding(contract.id(), contract.provider(), contract.kind(),
                            truncated ? Status.TRUNCATED_MATCH : Status.MATCHES,
                            truncated
                                    ? "bodies match up to the 4000-char actualization cap"
                                            + " — drift beyond the cap cannot be detected"
                                    : "",
                            extractedFrom));
                } else {
                    findings.add(new Finding(contract.id(), contract.provider(), contract.kind(),
                            Status.DRIFTED, summarize(recordedNorm, freshNorm), extractedFrom));
                }
            }
        }
        return findings;
    }

    /** Mirrors the rebuild pass's {@code originalPositions} convention exactly, so a finding's
     *  {@code extractedFrom} reads the same way a restore target would. Unlike the rebuild pass,
     *  this runs against arbitrary KB {@code repo.path} entries that were never guaranteed to be a
     *  live git checkout (a stale/deleted path, a plain directory) — {@code RunGit.currentBranch}
     *  throws for those, so this must degrade the same way {@code ContractActualizer.actualize}
     *  already does for an unreadable root: one benign finding, never an aborted review. */
    private static String currentPosition(Path root) {
        try {
            String branch = RunGit.currentBranch(root);
            return branch.isEmpty() ? "detached:" + RunGit.head(root) : branch;
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static boolean endsWithTruncationMarker(List<String> normalized) {
        return !normalized.isEmpty()
                && normalized.get(normalized.size() - 1).equals(ContractActualizer.TRUNCATION_MARKER);
    }

    /** Header line and blank/trailing whitespace are formatting, not interface content. */
    private static List<String> normalize(String body) {
        List<String> lines = new ArrayList<>();
        for (String line : body.split("\n")) {
            String stripped = line.stripTrailing();
            if (!stripped.isBlank() && !stripped.startsWith("# actualized")) {
                lines.add(stripped);
            }
        }
        return lines;
    }

    private static String summarize(List<String> recorded, List<String> fresh) {
        String added = fresh.stream().filter(l -> !recorded.contains(l)).findFirst().orElse("");
        String removed = recorded.stream().filter(l -> !fresh.contains(l)).findFirst().orElse("");
        StringBuilder detail = new StringBuilder();
        if (!removed.isEmpty()) {
            detail.append("no longer present: ").append(removed.strip());
        }
        if (!added.isEmpty()) {
            detail.append(detail.isEmpty() ? "" : "; ").append("now present: ").append(added.strip());
        }
        return detail.isEmpty() ? "bodies differ" : detail.toString();
    }
}
