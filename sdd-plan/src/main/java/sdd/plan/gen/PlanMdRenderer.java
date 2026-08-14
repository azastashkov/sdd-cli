package sdd.plan.gen;

import sdd.core.contract.Markdown;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactResult;
import sdd.plan.impact.Seed;
import sdd.plan.spec.NormalizedSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the Gate-1 plan.md. Deterministic given its inputs; the drafted sections degrade
 * to "- none (drafting unavailable)" while every deterministic section always renders.
 * Phase 3C-2's parser pins this exact layout.
 */
public final class PlanMdRenderer {

    private PlanMdRenderer() {
    }

    public static String render(NormalizedSpec spec, ImpactResult result,
                                List<ExecutionOrder.Unit> order, List<Question> detectorQuestions,
                                PlanDrafter.Draft draft) {
        return render(spec, result, order, detectorQuestions, draft, 1);
    }

    public static String render(NormalizedSpec spec, ImpactResult result,
                                List<ExecutionOrder.Unit> order, List<Question> detectorQuestions,
                                PlanDrafter.Draft draft, int planVersion) {
        StringBuilder md = new StringBuilder();
        md.append("---\nspec: ").append(spec.id()).append("\nplan_version: ").append(planVersion)
                .append("\n---\n");

        md.append("\n## Summary\n");
        String summary = inline(draft.summary());
        md.append(summary.isBlank()
                ? "Impact analysis for '" + spec.title() + "': " + result.affected().size() + " repos affected."
                : summary).append('\n');

        md.append("\n## Open Questions\n");
        List<Question> questions = new ArrayList<>(detectorQuestions);
        questions.addAll(draft.questions());
        if (questions.isEmpty()) {
            md.append("- none\n");
        } else {
            for (int i = 0; i < questions.size(); i++) {
                Question q = questions.get(i);
                md.append("- Q").append(i + 1).append(q.blocking() ? " [blocking]: " : ": ")
                        .append(inline(q.text())).append('\n');
            }
        }

        md.append("\n## Affected Repos\n");
        for (AffectedRepo repo : result.affected()) {
            md.append("- ").append(repo.repo()).append(" — ").append(repo.role()).append('/')
                    .append(repo.annotation())
                    .append(" — covers: ").append(repo.covers().isEmpty() ? "-" : String.join(",", repo.covers()))
                    .append(" — why: ").append(inline(String.join("; ", repo.reasons()))).append('\n');
        }

        md.append("\n## Excluded Candidates\n");
        if (result.excluded().isEmpty()) {
            md.append("- none\n");
        } else {
            for (Seed seed : result.excluded()) {
                md.append("- ").append(seed.repo()).append(" — ").append(inline(seed.detail())).append('\n');
            }
        }

        md.append("\n## Execution Order\n");
        for (int i = 0; i < order.size(); i++) {
            ExecutionOrder.Unit unit = order.get(i);
            md.append(i + 1).append(". ").append(String.join(" + ", unit.repos()));
            if (unit.repos().size() > 1) {
                md.append(" (co-scheduled)");
            }
            md.append('\n');
        }

        md.append("\n## Interface Contracts\n");
        if (draft.contracts().isEmpty()) {
            md.append(draft.unavailable() ? "- none (drafting unavailable)\n" : "- none\n");
        } else {
            for (PlanDrafter.DraftContract contract : draft.contracts()) {
                md.append("\n### ").append(inline(contract.id())).append(" (").append(contract.kind());
                if (contract.compat() != null) {
                    md.append(", ").append(contract.compat());
                }
                md.append(") — ").append(contract.provider());
                if (!contract.consumers().isEmpty()) {
                    md.append(" -> ").append(String.join(", ", contract.consumers()));
                }
                md.append('\n');
                md.append("```yaml\n").append(Markdown.neutralizeFences(contract.body()))
                        .append("\n```\n");
                if (!contract.declared().isEmpty()) {
                    md.append("\n```contract\n").append(declaredFence(contract.declared()))
                            .append("\n```\n");
                }
            }
        }

        md.append("\n## Repo Steps\n");
        if (draft.steps().isEmpty()) {
            md.append(draft.unavailable() ? "- none (drafting unavailable)\n" : "- none\n");
        } else {
            for (PlanDrafter.DraftStep step : draft.steps()) {
                md.append("\n### ").append(step.repo()).append('\n');
                md.append("- covers: ").append(step.covers().isEmpty() ? "-" : String.join(",", step.covers())).append('\n');
                md.append("- version_action: ").append(step.versionAction()).append('\n');
                md.append("- provides: ").append(step.providesContracts().isEmpty() ? "-" : String.join(",", step.providesContracts())).append('\n');
                md.append("- consumes: ").append(step.consumesContracts().isEmpty() ? "-" : String.join(",", step.consumesContracts())).append('\n');
                bullets(md, "files", step.files());
                bullets(md, "verification", step.verification());
                String subSpec = prose(step.subSpec());
                if (!subSpec.isBlank()) {
                    md.append('\n').append(subSpec).append('\n');
                }
            }
        }

        md.append("\n## Generation Notes\n");
        List<String> notes = new ArrayList<>(draft.notes());
        notes.addAll(result.warnings());
        if (notes.isEmpty()) {
            md.append("- none\n");
        } else {
            for (String note : notes) {
                md.append("- ").append(inline(note)).append('\n');
            }
        }
        return md.toString();
    }

    private static void bullets(StringBuilder md, String label, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        md.append("- ").append(label).append(":\n");
        for (String value : values) {
            md.append("  - ").append(inline(value)).append('\n');
        }
    }

    /** Declared members ride their own ```` ```contract ```` fence, exactly as the prose body
     *  rides its ```` ```yaml ```` fence above; a member carrying a literal ``` would otherwise
     *  close that fence early, same as an unescaped body. Escaped per line so a legitimate
     *  multi-member declared list still joins one member per line. */
    private static String declaredFence(List<String> declared) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < declared.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(Markdown.neutralizeFences(declared.get(i)));
        }
        return sb.toString();
    }

    /** Drafter-controlled single-line text may never forge headings or front matter. */
    private static String inline(String value) {
        String collapsed = value.replaceAll("(?U)\\s+", " ").strip();
        return collapsed.replaceAll("^#+\\s*", "");
    }

    /** Sub-spec prose keeps its lines but loses structural markers the renderer owns. */
    private static String prose(String value) {
        return Markdown.neutralizeFences(value.replaceAll("(?m)^\\s*#+\\s*", "")
                .replaceAll("(?m)^---\\s*$", "—"))
                .strip();
    }
}
