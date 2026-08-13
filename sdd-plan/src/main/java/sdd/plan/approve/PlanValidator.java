package sdd.plan.approve;

import org.jdbi.v3.core.Jdbi;
import sdd.plan.gen.ExecutionOrder;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Approve-time semantics (design M5): problems block, warnings surface. Structure is
 * PlanMdParser's job; this judges the human-approved content against the spec, the KB
 * graph, and live git state.
 */
public final class PlanValidator {
    private static final Set<String> VERSION_ACTIONS = Set.of("none", "patch", "minor", "major");
    private static final Pattern TOKEN = Pattern.compile("[^A-Za-z0-9/{}.-]+");

    public record Verdict(List<String> problems, List<String> warnings) {
        public Verdict {
            problems = List.copyOf(problems);
            warnings = List.copyOf(warnings);
        }
    }

    private PlanValidator() {
    }

    public static Verdict validate(Jdbi jdbi, PlanDocument plan, NormalizedSpec spec,
                                   Map<String, LiveGit.State> liveStates) {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (!plan.specId().equals(spec.id())) {
            problems.add("spec id mismatch: plan says '" + plan.specId()
                    + "' but the spec is '" + spec.id() + "'");
        }
        for (PlanDocument.PlanQuestion question : plan.questions()) {
            if (question.blocking()
                    && (question.resolution() == null || question.resolution().isBlank())) {
                problems.add("Q" + question.number() + " [blocking] has no resolution");
            }
        }

        Set<String> covered = new LinkedHashSet<>();
        Set<String> affectedNames = new LinkedHashSet<>();
        for (PlanDocument.PlanRepo repo : plan.affected()) {
            if (!affectedNames.add(repo.repo())) {
                problems.add("repo '" + repo.repo() + "' appears more than once in Affected Repos");
            }
        }
        Set<String> contractIds = new LinkedHashSet<>();
        for (PlanDocument.PlanContract contract : plan.contracts()) {
            if (!contractIds.add(contract.id())) {
                problems.add("duplicate contract id '" + contract.id() + "'");
            }
            if (contract.compat() != null && !"java-api".equals(contract.kind())) {
                problems.add("contract '" + contract.id() + "': compat is only valid on java-api contracts");
            }
        }
        Map<String, PlanDocument.PlanStep> stepByRepo = new HashMap<>();
        for (PlanDocument.PlanStep step : plan.steps()) {
            covered.addAll(step.covers());
            if (stepByRepo.put(step.repo(), step) != null) {
                problems.add("duplicate step for repo '" + step.repo() + "'");
            }
            if (!affectedNames.contains(step.repo())) {
                problems.add("step repo '" + step.repo() + "' is not in Affected Repos");
            }
            if (!VERSION_ACTIONS.contains(step.versionAction())) {
                problems.add("version_action '" + step.versionAction() + "' on step "
                        + step.repo() + " is not one of none|patch|minor|major");
            }
            for (String id : step.provides()) {
                if (!contractIds.contains(id)) {
                    problems.add("step " + step.repo() + " references undefined contract '" + id + "'");
                }
            }
            for (String id : step.consumes()) {
                if (!contractIds.contains(id)) {
                    problems.add("step " + step.repo() + " references undefined contract '" + id + "'");
                }
            }
        }
        for (SpecItem requirement : spec.requirements()) {
            if (!covered.contains(requirement.id())) {
                problems.add("no step covers " + requirement.id());
            }
        }
        for (PlanDocument.PlanContract contract : plan.contracts()) {
            PlanDocument.PlanStep providerStep = stepByRepo.get(contract.provider());
            if (providerStep == null || !providerStep.provides().contains(contract.id())) {
                problems.add("contract '" + contract.id() + "': provider '"
                        + contract.provider() + "' has no step providing it");
            }
            for (String consumer : contract.consumers()) {
                if (!affectedNames.contains(consumer)) {
                    problems.add("contract '" + contract.id() + "' names consumer '" + consumer
                            + "' that is not in Affected Repos");
                    continue;
                }
                PlanDocument.PlanStep consumerStep = stepByRepo.get(consumer);
                if (consumerStep == null) {
                    warnings.add("contract '" + contract.id() + "' lists consumer '" + consumer
                            + "' which has no step — rebuild-only dependent?");
                } else if (!consumerStep.consumes().contains(contract.id())) {
                    warnings.add("contract '" + contract.id() + "' lists consumer '" + consumer
                            + "' but " + consumer + "'s step does not consume it");
                }
            }
        }

        Map<String, Integer> position = new HashMap<>();
        Set<String> orderNames = new LinkedHashSet<>();
        for (int i = 0; i < plan.order().size(); i++) {
            for (String member : plan.order().get(i)) {
                if (position.put(member, i) != null) {
                    problems.add("repo '" + member + "' appears more than once in Execution Order");
                }
                orderNames.add(member);
            }
        }
        if (!orderNames.equals(affectedNames)) {
            Set<String> diff = new TreeSet<>();
            for (String name : orderNames) {
                if (!affectedNames.contains(name)) {
                    diff.add(name);
                }
            }
            for (String name : affectedNames) {
                if (!orderNames.contains(name)) {
                    diff.add(name);
                }
            }
            problems.add("execution order and Affected Repos disagree: " + String.join(", ", diff));
        }
        for (String[] edge : ExecutionOrder.edges(jdbi, affectedNames)) {
            Integer provider = position.get(edge[0]);
            Integer consumer = position.get(edge[1]);
            if (provider != null && consumer != null && provider > consumer) {
                problems.add("execution order violates dependency: " + edge[1]
                        + " runs before its provider " + edge[0]);
            }
        }

        Map<String, String> kbHeads = new HashMap<>();
        jdbi.useHandle(h -> h.createQuery("SELECT name, head_commit FROM repo").mapToMap()
                .forEach(row -> kbHeads.put(String.valueOf(row.get("name")),
                        row.get("head_commit") == null ? "" : String.valueOf(row.get("head_commit")))));
        for (String name : affectedNames) {
            String kb = kbHeads.getOrDefault(name, "");
            LiveGit.State live = liveStates.get(name);
            boolean stale = live == null || !kb.equals(live.head()) || !live.clean();
            if (stale) {
                String dirtyNote = live != null && !live.clean() ? ", dirty" : "";
                problems.add("repo " + name + " is stale or dirty (kb " + shortSha(kb)
                        + " live " + shortSha(live == null ? "" : live.head()) + dirtyNote
                        + ") — re-run sdd index and regenerate the plan");
            }
        }

        conflictWarnings(spec, plan, warnings);
        return new Verdict(problems, warnings);
    }

    private static void conflictWarnings(NormalizedSpec spec, PlanDocument plan,
                                         List<String> warnings) {
        for (SpecItem constraint : spec.constraints()) {
            Set<String> constraintTokens = tokens(constraint.text());
            for (PlanDocument.PlanContract contract : plan.contracts()) {
                for (String token : tokens(contract.body())) {
                    if (constraintTokens.contains(token)) {
                        warnings.add("constraint " + constraint.id() + " and contract '"
                                + contract.id() + "' both mention '" + token + "' — verify no conflict");
                        break;
                    }
                }
            }
        }
    }

    private static Set<String> tokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String raw : TOKEN.split(text)) {
            String token = raw.replaceAll("[.]+$", "");   // sentence-ending periods are not type dots
            if (token.length() >= 4 && (token.contains("/") || token.contains("."))) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static String shortSha(String sha) {
        return sha == null || sha.length() < 8 ? "?" : sha.substring(0, 8);
    }
}
