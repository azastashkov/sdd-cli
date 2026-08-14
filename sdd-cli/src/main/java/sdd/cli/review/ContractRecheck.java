package sdd.cli.review;

import sdd.cli.implement.ContractActualizer;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunGit;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;
import sdd.core.contract.DeclaredContract;

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
     * Answers a different question than {@link Status}: not "did the implementation change since
     * the run recorded it" but "does it match what Gate 1 approved". A finding can be
     * {@code DRIFTED} <em>and</em> {@code DIVERGED_FROM_PLAN} at once — the two axes are computed
     * independently and neither short-circuits the other.
     */
    public enum Conformance { DECLARED_MET, DIVERGED_FROM_PLAN, NOT_DECLARED, NOT_COMPARABLE }

    /**
     * {@code extractedFrom} is the provider's {@code RunGit.currentBranch} at extraction time
     * ({@code "detached:<sha>"} when empty). Under a rebuild pass it's the checkpoint branch;
     * under {@code --no-rebuild} nothing is checked out first, so it's whatever branch the human
     * was standing on — recording it turns an otherwise-inexplicable DRIFTED into something
     * adjudicable. {@code missing} is empty unless {@code conformance} is
     * {@code DIVERGED_FROM_PLAN}.
     */
    public record Finding(String contractId, String provider, String kind, Status status,
                          String detail, String extractedFrom,
                          Conformance conformance, List<String> missing) {
        public Finding {
            missing = List.copyOf(missing);
        }
    }

    /** The conformance axis's own verdict plus whatever explanatory text it wants folded into the
     *  finding's shared {@code detail} — kept separate from {@code Finding} itself so {@link #check}
     *  can compose it with the (independently derived) status detail before construction. */
    private record ConformanceResult(Conformance conformance, List<String> missing, String detail) {
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
                // No checkout at all: extraction was never attempted, so there is genuinely no
                // visibility into whether the declared contract is met.
                ConformanceResult conformance = conformanceOf(contract, null, false);
                findings.add(new Finding(contract.id(), contract.provider(), contract.kind(),
                        Status.NOT_EXTRACTABLE,
                        combineDetail("provider " + contract.provider()
                                + " has no checkout path in the knowledge base", conformance.detail()),
                        null, conformance.conformance(), conformance.missing()));
                continue;
            }
            String extractedFrom = extractedFromByProvider.get(contract.provider());
            String fresh = freshByProvider.get(contract.provider()).get(contract.id());
            String recorded = store.readContract(runDir, contract.id());
            if (fresh == null || fresh.isBlank()) {
                // Extraction DID run for this provider (the checkout-missing case above already
                // continued past) — a null/blank fresh body here means it ran and found nothing
                // for this contract, e.g. every declared type was renamed, moved or deleted. That
                // is a real divergence, not a tooling failure, so it must not read as NOT_COMPARABLE.
                ConformanceResult conformance = conformanceOf(contract, fresh, true);
                findings.add(new Finding(contract.id(), contract.provider(), contract.kind(),
                        Status.NOT_EXTRACTABLE,
                        combineDetail("nothing extractable for kind " + contract.kind()
                                + " in " + contract.provider(), conformance.detail()),
                        extractedFrom, conformance.conformance(), conformance.missing()));
            } else if (recorded == null) {
                ConformanceResult conformance = conformanceOf(contract, fresh, true);
                findings.add(new Finding(contract.id(), contract.provider(), contract.kind(),
                        Status.MISSING_RECORD,
                        combineDetail("no actualized contract was recorded for this provider",
                                conformance.detail()),
                        extractedFrom, conformance.conformance(), conformance.missing()));
            } else {
                List<String> freshNorm = normalize(fresh);
                List<String> recordedNorm = normalize(recorded);
                ConformanceResult conformance = conformanceOf(contract, fresh, true);
                if (freshNorm.equals(recordedNorm)) {
                    // Both sides pass through ContractActualizer's shared 4000-char cap. Equal
                    // truncated bodies don't prove the real interfaces match — a real change
                    // landing past the cut is invisible to this comparison.
                    boolean truncated = endsWithTruncationMarker(freshNorm)
                            && endsWithTruncationMarker(recordedNorm);
                    findings.add(new Finding(contract.id(), contract.provider(), contract.kind(),
                            truncated ? Status.TRUNCATED_MATCH : Status.MATCHES,
                            combineDetail(truncated
                                    ? "bodies match up to the 4000-char actualization cap"
                                            + " — drift beyond the cap cannot be detected"
                                    : "", conformance.detail()),
                            extractedFrom, conformance.conformance(), conformance.missing()));
                } else {
                    findings.add(new Finding(contract.id(), contract.provider(), contract.kind(),
                            Status.DRIFTED, combineDetail(summarize(recordedNorm, freshNorm),
                                    conformance.detail()),
                            extractedFrom, conformance.conformance(), conformance.missing()));
                }
            }
        }
        return findings;
    }

    /** Computes the conformance axis independently of {@link Status} — it is purely a function of
     *  what Gate 1 declared and the freshly re-extracted body, never of what the run recorded.
     *  {@code extracted} is {@code false} only when extraction was never attempted at all (no
     *  checkout path in the KB) — that is the sole {@code NOT_COMPARABLE}-for-lack-of-visibility
     *  case. When {@code extracted} is {@code true}, a null or blank {@code fresh} means extraction
     *  ran and found nothing for this contract (e.g. every declared type was renamed, moved or
     *  deleted): that is a genuine divergence, not a tooling failure, so it is normalized to an
     *  empty actual body and run through the same missing-member computation as any other case. */
    private static ConformanceResult conformanceOf(PlanModel.PlanContract contract, String fresh,
                                                    boolean extracted) {
        if (contract.declared().isEmpty()) {
            return new ConformanceResult(Conformance.NOT_DECLARED, List.of(), "");
        }
        DeclaredContract declared = DeclaredContract.parse(contract.kind(), String.join("\n", contract.declared()));
        if (!declared.problems().isEmpty()) {
            // Carried finding: all-malformed declared lines are NOT the same as nothing declared —
            // that would be a quiet lie in the very section this phase adds.
            return new ConformanceResult(Conformance.NOT_COMPARABLE, List.of(),
                    "declared contract is malformed: " + String.join("; ", declared.problems()));
        }
        if (!extracted) {
            return new ConformanceResult(Conformance.NOT_COMPARABLE, List.of(),
                    "no actual body was extracted to compare against the declared contract");
        }
        String actual = fresh == null || fresh.isBlank() ? "" : fresh;
        List<String> missing = declared.missingFrom(actual);
        if (missing.isEmpty()) {
            return new ConformanceResult(Conformance.DECLARED_MET, List.of(), "");
        }
        if (actual.endsWith(ContractActualizer.TRUNCATION_MARKER)) {
            // Same reasoning as TRUNCATED_MATCH on the drift axis: a missing declared member may
            // simply be sitting past the 4000-char cut, so a verdict of divergence would be a lie.
            // An empty actual body (extraction found nothing at all) can never satisfy this — it is
            // never truncated — so this branch cannot swallow the "found nothing" case above.
            return new ConformanceResult(Conformance.NOT_COMPARABLE, List.of(),
                    "declared member(s) not found before the 4000-char actualization cap"
                            + " — divergence beyond the cap cannot be detected");
        }
        return new ConformanceResult(Conformance.DIVERGED_FROM_PLAN, missing,
                "diverges from the declared contract — missing: " + String.join(", ", missing));
    }

    private static String combineDetail(String statusDetail, String conformanceDetail) {
        if (statusDetail.isEmpty()) {
            return conformanceDetail;
        }
        if (conformanceDetail.isEmpty()) {
            return statusDetail;
        }
        return statusDetail + "; " + conformanceDetail;
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
