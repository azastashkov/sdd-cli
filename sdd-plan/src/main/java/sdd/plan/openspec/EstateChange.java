package sdd.plan.openspec;

import sdd.plan.gen.ExecutionOrder;
import sdd.plan.gen.PlanDrafter;
import sdd.plan.gen.Question;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;
import sdd.plan.spec.Touchpoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole estate change as one OpenSpec change directory, for the WORKSPACE root.
 *
 * <p>{@link OpenSpecChange} renders one repository's slice, into that repository. This renders the
 * change a human is actually making — every affected repo, the order between them, every capability
 * — into {@code <workspace>/openspec/changes/<id>/}. Until now that view existed nowhere: each repo
 * saw its own slice plus a sentence saying others exist.
 *
 * <p>The spec deltas are NOT rendered here. They come from {@link OpenSpecChange} per repo, because
 * that grammar is the one the real OpenSpec CLI validates and a transcribed second copy would drift
 * from the checked one. Only the estate-wide documents — proposal, design, tasks — are written here,
 * and those are the ones with no per-repo equivalent.
 *
 * <p>Pure, like its sibling: no clock, no filesystem, no database. Rendering the same inputs twice
 * must produce the same bytes, because the writer decides "ours, unchanged" from "a human edited it"
 * by comparing them.
 */
public final class EstateChange {

    /** OpenSpec rejects a Why under 50 characters and warns over 1000. */
    private static final int MIN_WHY = 50;
    private static final int MAX_WHY = 1000;

    private EstateChange() {
    }

    /**
     * Every file of the root change, as {@code <workspace-relative path> -> contents}.
     *
     * <p>Insertion-ordered and with the deltas sorted by capability, so the map itself is part of
     * the determinism contract rather than incidental to it.
     */
    public static Map<String, String> render(NormalizedSpec spec, sdd.plan.impact.ImpactResult result,
            List<ExecutionOrder.Unit> order, List<Question> detectorQuestions,
            PlanDrafter.Draft draft, int planVersion, List<OpenSpecInput> inputs) {
        String changeId = ChangeId.of(spec.id(), planVersion);
        String base = "openspec/changes/" + changeId + "/";
        Map<String, String> deltas = deltas(inputs);

        Map<String, String> out = new LinkedHashMap<>();
        out.put(base + ".openspec.yaml", openSpecYaml(deltas.isEmpty()));
        out.put(base + "proposal.md", proposal(spec, result, order, draft, changeId, planVersion,
                deltas.keySet()));
        out.put(base + "design.md", design(spec, draft, detectorQuestions, changeId));
        out.put(base + "tasks.md", tasks(spec, order, draft));
        new java.util.TreeMap<>(deltas).forEach((capability, body) ->
                out.put(base + "specs/" + capability + "/spec.md", body));
        return out;
    }

    /**
     * One delta per capability, taken from each repo's own render.
     *
     * <p>Two repos naming the same capability would otherwise silently lose one of them — last
     * write wins in a map — so a collision is disambiguated by repo rather than resolved. It is
     * deterministic because the inputs are in step order.
     */
    private static Map<String, String> deltas(List<OpenSpecInput> inputs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (OpenSpecInput in : inputs) {
            for (Map.Entry<String, String> delta : OpenSpecChange.render(in, false).deltas().entrySet()) {
                String capability = delta.getKey();
                if (out.containsKey(capability)) {
                    capability = capability + "-" + Kebab.of(in.repo());
                }
                out.put(capability, delta.getValue());
            }
        }
        return out;
    }

    private static String openSpecYaml(boolean noDeltas) {
        // Same two keys, and the same reason for the second: a change with no delta is an error
        // unless it says it meant to have none. An estate change with no drafted step is exactly
        // that — impact analysis found repos, drafting produced nothing to specify.
        return "schema: spec-driven\n" + (noDeltas ? "skip_specs: true\n" : "");
    }

    private static String proposal(NormalizedSpec spec, sdd.plan.impact.ImpactResult result,
            List<ExecutionOrder.Unit> order, PlanDrafter.Draft draft, String changeId,
            int planVersion, java.util.Set<String> capabilities) {
        StringBuilder md = new StringBuilder("# ").append(inline(spec.title())).append("\n\n");
        md.append("## Why\n").append(why(spec, result, changeId)).append('\n');

        md.append("\n## What Changes\n");
        md.append(inline(draft.summary().isBlank()
                ? "Impact analysis for '" + spec.title() + "': " + result.affected().size()
                        + " repositories affected."
                : draft.summary())).append('\n');
        if (!draft.steps().isEmpty()) {
            md.append('\n');
            for (PlanDrafter.DraftStep step : draft.steps()) {
                md.append("- `").append(step.repo()).append("`: ")
                        .append(step.covers().isEmpty() ? "rebuild only"
                                : "covers " + String.join(", ", step.covers()))
                        .append(versionSuffix(step)).append(".\n");
            }
        }

        if (!capabilities.isEmpty()) {
            md.append("\n## Capabilities\n\n### New Capabilities\n");
            new java.util.TreeSet<>(capabilities).forEach(capability ->
                    md.append("- `").append(capability).append("`: one repository's behaviour area "
                            + "in this change. Generated from sdd specification `")
                            .append(spec.id()).append("`; rename it to a durable behaviour area "
                            + "before applying.\n"));
        }

        md.append("\n## Impact\n");
        md.append("- Repositories: ").append(repoList(result)).append(".\n");
        if (!order.isEmpty()) {
            md.append("- Execution order: ").append(orderLine(order)).append(".\n");
        }
        for (PlanDrafter.DraftContract contract : draft.contracts()) {
            md.append("- `").append(contract.provider()).append("` provides `")
                    .append(contract.id()).append("` (").append(contract.kind()).append(") to ")
                    .append(contract.consumers().isEmpty() ? "no declared consumer"
                            : "`" + String.join("`, `", contract.consumers()) + "`").append(".\n");
        }
        md.append("- Generated from sdd specification `").append(spec.id())
                .append("`, plan version ").append(planVersion).append(".\n");
        // Accurate about WHEN, not just where: at plan time this directory has no estate.yaml —
        // approve writes it, after the checks that make it meaningful. A document asserting a file
        // that is not there yet is a small lie that costs the rest of the document its credibility.
        md.append("- `sdd plan approve` writes the machine-readable estate — affected set, "
                + "execution order, dependency edges, contracts — to `estate.yaml` in this "
                + "directory. OpenSpec has nowhere to put any of it.\n");
        for (String note : draft.notes()) {
            md.append("- ").append(inline(note)).append('\n');
        }
        return md.toString();
    }

    /**
     * {@code ## Why} is validated: under 50 characters is an error, over 1000 a warning.
     *
     * <p>A spec's goal can be one short sentence, so the estate sentence is appended ALWAYS rather
     * than only when the goal is short. It is true of every estate change, it is the fact a reader
     * most needs, and it makes the length floor unreachable by construction instead of by a check
     * that would occasionally fire on a legitimate spec.
     */
    private static String why(NormalizedSpec spec, sdd.plan.impact.ImpactResult result,
            String changeId) {
        StringBuilder why = new StringBuilder(prose(spec.goal()));
        if (!spec.background().isBlank()) {
            why.append("\n\n").append(prose(spec.background()));
        }
        why.append("\n\nThis change spans ").append(result.affected().size())
                .append(result.affected().size() == 1 ? " repository" : " repositories")
                .append(", tracked under the shared change id `").append(changeId)
                .append("`. Each affected repository receives its own change directory with that "
                        + "same id when the plan is implemented.");
        String text = why.toString().strip();
        if (text.length() > MAX_WHY) {
            text = text.substring(0, MAX_WHY - 1).strip() + "…";
        }
        return text.length() < MIN_WHY ? text + " (see the specification for detail.)" : text;
    }

    private static String design(NormalizedSpec spec, PlanDrafter.Draft draft,
            List<Question> detectorQuestions, String changeId) {
        StringBuilder md = new StringBuilder("## Context\n");
        md.append("The estate-wide view of `").append(changeId).append("`.\n");
        if (!spec.touchpoints().isEmpty()) {
            md.append("\nTouchpoints the specification named, resolved against the knowledge base:\n");
            for (Touchpoint touchpoint : spec.touchpoints()) {
                md.append("- ").append(touchpoint.kind().key()).append(": `")
                        .append(inline(touchpoint.value())).append("`\n");
            }
        }
        if (!spec.evidence().isEmpty()) {
            md.append("\nEvidence:\n");
            for (String evidence : spec.evidence()) {
                md.append("- ").append(inline(evidence)).append('\n');
            }
        }

        md.append("\n## Goals / Non-Goals\n\n**Goals:**\n");
        for (SpecItem requirement : spec.requirements()) {
            md.append("- ").append(requirement.id()).append(": ")
                    .append(inline(requirement.text())).append('\n');
        }
        if (!spec.outOfScope().isEmpty()) {
            md.append("\n**Non-Goals:**\n");
            for (String item : spec.outOfScope()) {
                md.append("- ").append(inline(item)).append('\n');
            }
        }

        if (!draft.contracts().isEmpty()) {
            md.append("\n## Decisions\n");
            for (PlanDrafter.DraftContract contract : draft.contracts()) {
                md.append("\n### `").append(contract.id()).append("` — ").append(contract.kind());
                if (contract.compat() != null && !contract.compat().isBlank()) {
                    md.append(", ").append(contract.compat());
                }
                md.append(", provided by `").append(contract.provider()).append("`\n\n");
                md.append("```\n").append(sdd.core.contract.Markdown.neutralizeFences(contract.body())
                        .strip()).append("\n```\n");
                if (!contract.declared().isEmpty()) {
                    md.append("\nDeclared members:\n");
                    for (String member : contract.declared()) {
                        md.append("- `").append(inline(member)).append("`\n");
                    }
                }
            }
        }

        if (!spec.constraints().isEmpty()) {
            md.append("\n## Risks / Trade-offs\n");
            for (SpecItem constraint : spec.constraints()) {
                md.append("- ").append(constraint.id()).append(": ")
                        .append(inline(constraint.text())).append('\n');
            }
        }

        // OpenSpec does not read this section, so the blocking marker and the human's resolution
        // line are sdd's own convention living somewhere the validator will not object to them.
        md.append("\n## Open Questions\n");
        List<Question> questions = new ArrayList<>(detectorQuestions);
        questions.addAll(draft.questions());
        if (questions.isEmpty() && spec.openQuestions().isEmpty()) {
            md.append("- None recorded.\n");
        }
        for (SpecItem question : spec.openQuestions()) {
            md.append("- ").append(question.id()).append(": ").append(inline(question.text()))
                    .append('\n');
        }
        for (int i = 0; i < questions.size(); i++) {
            md.append("- Q").append(i + 1).append(questions.get(i).blocking() ? " [blocking]: " : ": ")
                    .append(inline(questions.get(i).text())).append('\n');
        }
        return md.toString();
    }

    /**
     * One numbered group per repository, in execution order.
     *
     * <p>OpenSpec parses these checkboxes to track progress, and only `- [ ] X.Y ` is tracked. The
     * grouping is by repo rather than by kind of work because at the root the repo is the axis the
     * per-repo exports cannot show: a reader here wants to know what happens where, and in what
     * order.
     */
    private static String tasks(NormalizedSpec spec, List<ExecutionOrder.Unit> order,
            PlanDrafter.Draft draft) {
        Map<String, PlanDrafter.DraftStep> steps = new LinkedHashMap<>();
        draft.steps().forEach(step -> steps.put(step.repo(), step));
        Map<String, String> requirementText = new LinkedHashMap<>();
        spec.requirements().forEach(r -> requirementText.put(r.id(), r.text()));

        StringBuilder md = new StringBuilder();
        int group = 0;
        for (ExecutionOrder.Unit unit : order) {
            for (String repo : unit.repos()) {
                PlanDrafter.DraftStep step = steps.get(repo);
                List<String> items = new ArrayList<>();
                if (step != null) {
                    step.providesContracts().forEach(id ->
                            items.add("Provide `" + id + "` for the repositories that consume it"));
                    step.consumesContracts().forEach(id ->
                            items.add("Consume `" + id + "` once its provider has landed"));
                    step.covers().forEach(id ->
                            items.add(id + ": " + inline(requirementText.getOrDefault(id, id))));
                    step.files().forEach(file -> items.add("Change `" + file + "`"));
                    step.verification().forEach(task -> items.add("Run `" + task + "`"));
                    if (!"none".equals(step.versionAction())) {
                        items.add("Apply a `" + step.versionAction() + "` version bump");
                    }
                }
                if (items.isEmpty()) {
                    items.add("Rebuild against the updated dependencies and verify");
                }
                group++;
                md.append(group == 1 ? "" : "\n").append("## ").append(group).append(". ")
                        .append(repo).append("\n\n");
                for (int i = 0; i < items.size(); i++) {
                    md.append("- [ ] ").append(group).append('.').append(i + 1).append(' ')
                            .append(items.get(i)).append('\n');
                }
            }
        }
        return md.length() == 0 ? "## 1. No repositories\n\n- [ ] 1.1 Nothing to do\n" : md.toString();
    }

    private static String versionSuffix(PlanDrafter.DraftStep step) {
        return "none".equals(step.versionAction()) ? ""
                : ", publishing a `" + step.versionAction() + "` bump";
    }

    private static String repoList(sdd.plan.impact.ImpactResult result) {
        List<String> names = result.affected().stream().map(r -> "`" + r.repo() + "`").toList();
        return names.isEmpty() ? "none" : String.join(", ", names);
    }

    private static String orderLine(List<ExecutionOrder.Unit> order) {
        List<String> units = new ArrayList<>();
        for (ExecutionOrder.Unit unit : order) {
            units.add("`" + String.join("` + `", unit.repos()) + "`");
        }
        return String.join(" -> ", units);
    }

    /** Single-line text may never forge a heading or front matter. */
    private static String inline(String value) {
        return sdd.core.contract.Markdown.neutralizeFences(
                value.replaceAll("(?U)\\s+", " ").strip().replaceAll("^#+\\s*", ""));
    }

    /** Prose keeps its lines but loses the structural markers this renderer owns. */
    private static String prose(String value) {
        return sdd.core.contract.Markdown.neutralizeFences(
                value.replaceAll("(?m)^\\s*#+\\s*", "").replaceAll("(?m)^---\\s*$", "—")).strip();
    }
}
