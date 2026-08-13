package sdd.cli.review;

import sdd.cli.implement.ContractActualizer;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gate-2 contract re-check (design line 66): re-extract each green provider's real interface and
 * diff it against the body the run actualized. Mismatches are WARNINGS a human adjudicates — they
 * never fail the review. Providers that did not go green are skipped: nothing was checkpointed.
 */
public final class ContractRecheck {
    public enum Status { MATCHES, DRIFTED, MISSING_RECORD, NOT_EXTRACTABLE }

    public record Finding(String contractId, String provider, String kind, Status status,
                          String detail) {
    }

    private ContractRecheck() {
    }

    public static List<Finding> check(PlanModel plan, RunState state, Map<String, Path> repoPaths,
                                      RunStore store, Path runDir) {
        List<Finding> findings = new ArrayList<>();
        for (PlanModel.PlanContract contract : plan.contracts()) {
            if (state.stateOf(contract.provider()) != RepoState.SUCCEEDED) {
                continue;
            }
            Path root = repoPaths.get(contract.provider());
            if (root == null) {
                continue;
            }
            String fresh = ContractActualizer.actualize(root, List.of(contract)).get(contract.id());
            String recorded = store.readContract(runDir, contract.id());
            if (fresh == null || fresh.isBlank()) {
                findings.add(new Finding(contract.id(), contract.provider(), contract.kind(),
                        Status.NOT_EXTRACTABLE,
                        "nothing extractable for kind " + contract.kind() + " in " + contract.provider()));
            } else if (recorded == null) {
                findings.add(new Finding(contract.id(), contract.provider(), contract.kind(),
                        Status.MISSING_RECORD,
                        "no actualized contract was recorded for this provider"));
            } else if (normalize(fresh).equals(normalize(recorded))) {
                findings.add(new Finding(contract.id(), contract.provider(), contract.kind(),
                        Status.MATCHES, ""));
            } else {
                findings.add(new Finding(contract.id(), contract.provider(), contract.kind(),
                        Status.DRIFTED, summarize(normalize(recorded), normalize(fresh))));
            }
        }
        return findings;
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
