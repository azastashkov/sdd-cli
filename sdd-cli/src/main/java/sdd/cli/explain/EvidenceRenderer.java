package sdd.cli.explain;

import sdd.core.contract.Markdown;
import sdd.core.kb.EntityKind;
import sdd.core.kb.Provenance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders {@link Evidence} into the one string that plays two roles at once: the call-2 (narrator)
 * prompt (Task 7) and the printed {@code ## Evidence} section a human reads (Task 8). Those two
 * roles are never allowed to diverge — an answer can only be grounded in facts its reader can also
 * see — so there is exactly one render path here, never a "prompt version" and a "display version".
 * Task 7 pins this identity with a test that compares the call-2 request body against the printed
 * output byte-for-byte.
 *
 * <p>Pure and deterministic: same {@link Evidence} in, same {@code String} out, no {@code Jdbi},
 * no I/O, no wall-clock. That purity is what lets this class be unit-tested without a database.
 *
 * <p>Follows the {@code ReviewReport}/{@code CurationReport} idiom used elsewhere in this codebase:
 * a section with no facts is omitted entirely rather than printed with an empty body. {@code
 * Interpretation} is the one block that is never omitted, even when every {@link Section} and every
 * caveat is empty — a reader must always be able to see what the question was understood to mean and
 * which named things were rejected and why (via {@link RetrievalRequest#notes()}), independent of
 * whether anything was found.
 */
public final class EvidenceRenderer {

    /**
     * Deliberately 12000, not {@code PlanDrafter}'s 4000. {@code PlanDrafter.EVIDENCE_CAP} bounds a
     * per-repo bundle folded into a prompt no human ever reads; this string doubles as the
     * human-facing audit trail a reader checks an answer's claims against, so truncating it as
     * aggressively would hide facts from a person, not just from a model. Per-section limits
     * ({@link Section#DEFAULT_LIMIT}, {@link Section#MEMBER_LIMIT}) are already applied upstream by
     * the collectors, so this backstop is rarely reached in practice — it exists for the case where
     * many sections (e.g. a {@code describe} spanning several repos) stack up past a sane prompt
     * size, not to bound any single section's own content.
     */
    public static final int EVIDENCE_CAP = 12000;

    private EvidenceRenderer() {
    }

    public static String render(Evidence evidence) {
        StringBuilder out = new StringBuilder();
        out.append(renderProvenance(evidence.provenance()));
        out.append(renderInterpretation(evidence.request()));

        List<String> sectionBlocks = new ArrayList<>();
        for (Section section : evidence.sections()) {
            if (!section.facts().isEmpty()) {
                sectionBlocks.add(renderSection(section));
            }
        }
        appendCapped(out, sectionBlocks);

        // Caveats are never subject to the section cap above: AbsenceGuard's "nothing consumes X is
        // not something the KB can assert" warning is short, safety-critical, and produced only for
        // CONSUMERS/IMPACT — dropping it to make room for more describe-style facts would be exactly
        // backwards, so it is always appended in full after whatever sections fit.
        if (!evidence.caveats().isEmpty()) {
            out.append(renderCaveats(evidence.caveats()));
        }
        return out.toString();
    }

    /**
     * Appends as many whole section blocks as fit under {@link #EVIDENCE_CAP}, in order, and never
     * a partial one — a section cut apart mid-fact or (worse) reduced to a bare header would state
     * something the evidence does not actually support. When not every block fits, an explicit
     * marker names how many whole sections were left out, using the same "+N more (showing L of M)"
     * idiom {@link Section#capped} already uses one level down for facts inside a single section, so
     * truncation always reads the same way wherever it happens.
     */
    private static void appendCapped(StringBuilder out, List<String> blocks) {
        int shown = 0;
        for (String block : blocks) {
            if (out.length() + block.length() > EVIDENCE_CAP) {
                break;
            }
            out.append(block);
            shown++;
        }
        int total = blocks.size();
        if (shown < total) {
            int omitted = total - shown;
            out.append("### Evidence truncated\n\n")
                    .append('+').append(omitted).append(" more section").append(omitted == 1 ? "" : "s")
                    .append(" omitted (showing ").append(shown).append(" of ").append(total)
                    .append(") — evidence capped at ").append(EVIDENCE_CAP).append(" characters\n\n");
        }
    }

    private static String renderProvenance(Provenance provenance) {
        StringBuilder sb = new StringBuilder();
        sb.append("Provenance: ").append(provenance.repoCount())
                .append(provenance.repoCount() == 1 ? " repo indexed" : " repos indexed");
        if (provenance.earliestIndexedAt() == null || provenance.latestIndexedAt() == null) {
            sb.append("; no indexed timestamps recorded");
        } else {
            sb.append("; indexed ").append(provenance.earliestIndexedAt())
                    .append(" to ").append(provenance.latestIndexedAt());
        }
        sb.append("\n\n");
        return sb.toString();
    }

    /**
     * {@code restatement} is printed verbatim as {@code Interpreted as: ...} — the cheapest defence
     * against a silently misread question, since a misreading becomes visible instead of becoming a
     * wrong answer. Every note (dropped entity, downgrade, truncation) from call 1's validation is
     * listed too, never silently absorbed.
     */
    private static String renderInterpretation(RetrievalRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Interpretation\n\n");
        sb.append("Interpreted as: ").append(sanitize(request.restatement())).append('\n');
        sb.append("Intent: ").append(request.intent().name().toLowerCase(Locale.ROOT)).append('\n');
        if (!request.entities().isEmpty()) {
            boolean withRole = request.intent() == Intent.DEPENDENCY_PATH;
            List<String> parts = new ArrayList<>();
            for (EntityRef entity : request.entities()) {
                String part = kindLabel(entity.kind()) + " '" + sanitize(entity.value()) + "'";
                if (withRole) {
                    part += entity.object() ? " (object)" : " (subject)";
                }
                parts.add(part);
            }
            sb.append("Entities: ").append(String.join(", ", parts)).append('\n');
        }
        if (!request.searchTerms().isEmpty()) {
            List<String> terms = request.searchTerms().stream()
                    .map(EvidenceRenderer::sanitize)
                    .toList();
            sb.append("Search terms: ").append(String.join(", ", terms)).append('\n');
        }
        if (!request.notes().isEmpty()) {
            sb.append("Notes:\n");
            for (String note : request.notes()) {
                sb.append("- ").append(sanitize(note)).append('\n');
            }
        }
        sb.append('\n');
        return sb.toString();
    }

    private static String renderSection(Section section) {
        StringBuilder sb = new StringBuilder();
        sb.append("### [").append(section.source()).append("] ").append(section.title()).append("\n\n");
        for (Fact fact : section.facts()) {
            sb.append("- ").append(sanitize(fact.text())).append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    private static String renderCaveats(List<String> caveats) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Caveats\n\n");
        for (String caveat : caveats) {
            sb.append("- ").append(sanitize(caveat)).append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    /**
     * {@link Fact}/{@code RetrievalRequest} carry no flag distinguishing deterministic KB text from
     * model-authored prose — a dropped entity's {@code value} in particular never passed
     * {@code KbEntities.resolve}, so unlike a surviving {@link EntityRef} it is exactly as
     * unconstrained as {@code repo_card.card_md}. Rather than track provenance per-string,
     * {@link Markdown#neutralizeFences} — cheap and idempotent — is applied to every piece of free
     * text that flows into the rendered markdown, so the anti-forgery property holds regardless of
     * which collector, note, or entity value produced it.
     */
    private static String sanitize(String text) {
        return Markdown.neutralizeFences(text == null ? "" : text);
    }

    private static String kindLabel(EntityKind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }
}
