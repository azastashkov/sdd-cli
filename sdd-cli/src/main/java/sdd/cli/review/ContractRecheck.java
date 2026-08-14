package sdd.cli.review;

import sdd.cli.implement.ContractActualizer;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunGit;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;
import sdd.core.contract.ContractKinds;
import sdd.core.contract.DeclaredContract;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
    public enum Conformance { DECLARED_MET, DIVERGED_FROM_PLAN, NOT_DECLARED, NOT_COMPARABLE, NOT_RESOLVED }

    /**
     * {@code extractedFrom} is the provider's {@code RunGit.currentBranch} at extraction time
     * ({@code "detached:<sha>"} when empty). Under a rebuild pass it's the checkpoint branch;
     * under {@code --no-rebuild} nothing is checked out first, so it's whatever branch the human
     * was standing on — recording it turns an otherwise-inexplicable DRIFTED into something
     * adjudicable. {@code missing} is empty unless {@code conformance} is
     * {@code DIVERGED_FROM_PLAN}. {@code unresolved} is the declared members {@code missing}
     * would otherwise have counted, excused because the actual side has a named unresolved entry
     * that could plausibly be them (the 2026-08-14 "unresolved extraction" amendment) — non-empty
     * for both {@code NOT_RESOLVED} (every missing member excused) and {@code DIVERGED_FROM_PLAN}
     * (some excused, some not); empty for every other verdict.
     */
    public record Finding(String contractId, String provider, String kind, Status status,
                          String detail, String extractedFrom,
                          Conformance conformance, List<String> missing, List<String> unresolved) {
        public Finding {
            missing = List.copyOf(missing);
            unresolved = List.copyOf(unresolved);
        }
    }

    /** The conformance axis's own verdict plus whatever explanatory text it wants folded into the
     *  finding's shared {@code detail} — kept separate from {@code Finding} itself so {@link #check}
     *  can compose it with the (independently derived) status detail before construction.
     *
     *  <p>{@code DIVERGED_FROM_PLAN} is the one verdict that carries no prose here: its explanation
     *  <em>is</em> the {@code missing} list, and {@code ReviewReport} renders that list directly as
     *  indented bullets once a finding's conformance is shown separately from {@code detail}
     *  (Phase 5C-1 task 5). A prose summary of the same list folded into {@code detail} would
     *  duplicate it verbatim in the report. Every other verdict (malformed declaration, no body
     *  extracted, truncation) has no structured field to carry its reason, so it keeps explaining
     *  itself in prose here. */
    private record ConformanceResult(Conformance conformance, List<String> missing,
                                     List<String> unresolved, String detail) {
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
                        null, conformance.conformance(), conformance.missing(), conformance.unresolved()));
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
                        extractedFrom, conformance.conformance(), conformance.missing(), conformance.unresolved()));
            } else if (recorded == null) {
                ConformanceResult conformance = conformanceOf(contract, fresh, true);
                findings.add(new Finding(contract.id(), contract.provider(), contract.kind(),
                        Status.MISSING_RECORD,
                        combineDetail("no actualized contract was recorded for this provider",
                                conformance.detail()),
                        extractedFrom, conformance.conformance(), conformance.missing(), conformance.unresolved()));
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
                            extractedFrom, conformance.conformance(), conformance.missing(), conformance.unresolved()));
                } else {
                    findings.add(new Finding(contract.id(), contract.provider(), contract.kind(),
                            Status.DRIFTED, combineDetail(summarize(recordedNorm, freshNorm),
                                    conformance.detail()),
                            extractedFrom, conformance.conformance(), conformance.missing(), conformance.unresolved()));
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
        DeclaredContract declared = DeclaredContract.parse(contract.kind(), String.join("\n", contract.declared()));
        if (declared.members().isEmpty() && declared.problems().isEmpty()) {
            // NOT_DECLARED is decided from the PARSED result, never from the raw list: a block of
            // blank or '#' lines (an emptied ```contract fence, a parked "# TODO") is non-empty as
            // a list yet declares nothing, and containment over zero members is vacuously satisfied
            // — that reported DECLARED_MET, a silent false pass indistinguishable from a genuinely
            // verified contract. A hand-edit must make Gate 2 degrade, not lie.
            return new ConformanceResult(Conformance.NOT_DECLARED, List.of(), List.of(), "");
        }
        if (!declared.problems().isEmpty()) {
            // Carried finding: all-malformed declared lines are NOT the same as nothing declared —
            // that would be a quiet lie in the very section this phase adds.
            return new ConformanceResult(Conformance.NOT_COMPARABLE, List.of(), List.of(),
                    "declared contract is malformed: " + String.join("; ", declared.problems()));
        }
        if (!extracted) {
            return new ConformanceResult(Conformance.NOT_COMPARABLE, List.of(), List.of(),
                    "no actual body was extracted to compare against the declared contract");
        }
        String actual = fresh == null || fresh.isBlank() ? "" : fresh;
        List<String> missing = declared.missingFrom(actual);
        if (missing.isEmpty()) {
            return new ConformanceResult(Conformance.DECLARED_MET, List.of(), List.of(), "");
        }
        if (actual.endsWith(ContractActualizer.TRUNCATION_MARKER)) {
            // Same reasoning as TRUNCATED_MATCH on the drift axis: a missing declared member may
            // simply be sitting past the 4000-char cut, so a verdict of divergence would be a lie.
            // An empty actual body (extraction found nothing at all) can never satisfy this — it is
            // never truncated — so this branch cannot swallow the "found nothing" case above.
            return new ConformanceResult(Conformance.NOT_COMPARABLE, List.of(), List.of(),
                    "declared member(s) not found before the 4000-char actualization cap"
                            + " — divergence beyond the cap cannot be detected");
        }
        // Partition: a missing member is excused only when the actual side names a specific
        // unresolved entry that could plausibly be it (design line 42's amendment — unresolved is
        // not the same as nonexistent). Divergence wins whenever even one missing member has no
        // such excuse; NOT_RESOLVED only when every one of them does.
        List<String> unresolvedActual = declared.unresolvedMembers(actual);
        List<String> stillMissing = new ArrayList<>();
        List<String> excused = new ArrayList<>();
        for (String member : missing) {
            if (explainedByUnresolved(contract.kind(), member, unresolvedActual)) {
                excused.add(member);
            } else {
                stillMissing.add(member);
            }
        }
        if (stillMissing.isEmpty()) {
            return new ConformanceResult(Conformance.NOT_RESOLVED, List.of(), excused, "");
        }
        // No prose: the missing list itself is the explanation, and ReviewReport renders it as
        // indented bullets — see the ConformanceResult javadoc for why this branch is silent.
        return new ConformanceResult(Conformance.DIVERGED_FROM_PLAN, stillMissing, excused, "");
    }

    /** Same kind-specific key as the declared member, ignoring exactly the part the actual side
     *  could not resolve — the partition rule the 2026-08-14 amendment specifies. An unresolved
     *  entry excuses a missing member only when it could plausibly BE that member; sharing the
     *  entry's coarse half (rest's verb, kafka's role) is never enough on its own, or one marked
     *  line would excuse every missing member of the same verb/role and silently turn real
     *  divergence into NOT_RESOLVED. Only the reachable half of each kind's symmetric rule is
     *  implemented: {@code rest}'s "same verb with an empty path" branch is intentionally absent
     *  because {@code ContractActualizer} does not mark that shape at all (see its {@code rest()}
     *  javadoc for why), and kafka's "same topic with an unresolved role" branch can never fire
     *  because a role is a hardcoded literal {@code KafkaExtractor} writes itself, never a
     *  resolved value. */
    private static boolean explainedByUnresolved(String kind, String missingMember, List<String> unresolvedActual) {
        return switch (kind) {
            case ContractKinds.REST -> unresolvedActual.stream().anyMatch(u -> restSamePathAnyVerb(missingMember, u));
            case ContractKinds.KAFKA -> unresolvedActual.stream().anyMatch(u -> kafkaExplains(missingMember, u));
            default -> false; // java-api: no unresolved shape exists
        };
    }

    private static boolean restSamePathAnyVerb(String missingMember, String unresolvedEntry) {
        int space = unresolvedEntry.indexOf(' ');
        if (space < 0 || !"ANY".equals(unresolvedEntry.substring(0, space))) {
            return false;
        }
        String unresolvedPath = unresolvedEntry.substring(space + 1);
        int missingSpace = missingMember.indexOf(' ');
        String missingPath = missingSpace >= 0 ? missingMember.substring(missingSpace + 1) : "";
        return unresolvedPath.equals(missingPath);
    }

    /** The role must match AND the unresolved entry's topic must plausibly cover the missing one.
     *  Role alone is not enough: {@code KafkaExtractor} writes {@code resolution() == "DYNAMIC"}
     *  for EVERY {@code topicPattern} listener, resolved or not, so a perfectly readable
     *  {@code @KafkaListener(topicPattern = "audit.*")} arrives here marked unresolved — and a
     *  role-only rule would let it excuse every missing declared {@code consumes} member on the
     *  contract, reporting NOT_RESOLVED about a surface extraction read exactly. That is the same
     *  false-negative shape the REST {@code pathTemplate == "/"} heuristic was rejected for.
     *
     *  <p>So: an identical topic is the member; a value extraction genuinely could not resolve
     *  ({@code ${…}} / {@code #{…}} left in the raw expression) could have been anything, so it
     *  still excuses; otherwise the entry is treated as the pattern it is and excuses only the
     *  topics it actually matches. That last step is a heuristic over the pattern TEXT — Java
     *  regex semantics, not Spring's own topic-pattern matching — so it is deliberately narrow and
     *  a syntactically invalid pattern excuses nothing rather than everything. */
    private static boolean kafkaExplains(String missingMember, String unresolvedEntry) {
        if (!kafkaSameRole(missingMember, unresolvedEntry)) {
            return false;
        }
        String unresolvedTopic = kafkaTopic(unresolvedEntry);
        String missingTopic = kafkaTopic(missingMember);
        if (unresolvedTopic.equals(missingTopic)) {
            return true;
        }
        if (unresolvedTopic.contains("${") || unresolvedTopic.contains("#{")) {
            return true;
        }
        try {
            return Pattern.compile(unresolvedTopic).matcher(missingTopic).matches();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    private static boolean kafkaSameRole(String missingMember, String unresolvedEntry) {
        int space = unresolvedEntry.indexOf(' ');
        String unresolvedRole = space >= 0 ? unresolvedEntry.substring(0, space) : unresolvedEntry;
        int missingSpace = missingMember.indexOf(' ');
        String missingRole = missingSpace >= 0 ? missingMember.substring(0, missingSpace) : missingMember;
        return unresolvedRole.equals(missingRole);
    }

    /** Everything after the role on a canonical {@code <role> <topic>} line; empty when there is no
     *  topic at all, which can never equal or match a declared member's topic. */
    private static String kafkaTopic(String member) {
        int space = member.indexOf(' ');
        return space >= 0 ? member.substring(space + 1).strip() : "";
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

    /** Header line and blank/trailing whitespace are formatting, not interface content — and so is
     *  {@code UNRESOLVED_MARKER}. This axis compares interfaces (did the tree change since the run
     *  recorded it); whether extraction could resolve a value is the conformance axis's business,
     *  not this one's. Without stripping it, a run recorded before this marker existed would
     *  re-check today as {@code DRIFTED} against unchanged source, purely because the fresh
     *  extraction now annotates a line the recorded body never did. */
    private static List<String> normalize(String body) {
        List<String> lines = new ArrayList<>();
        for (String line : body.split("\n")) {
            String stripped = line.stripTrailing();
            if (stripped.endsWith(ContractActualizer.UNRESOLVED_MARKER)) {
                stripped = stripped.substring(0,
                        stripped.length() - ContractActualizer.UNRESOLVED_MARKER.length());
            }
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
