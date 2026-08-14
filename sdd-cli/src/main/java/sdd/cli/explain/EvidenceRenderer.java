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
 *
 * <p><b>What {@link #EVIDENCE_CAP} actually bounds:</b> {@link Section} blocks only — see
 * {@link #appendCapped}. {@code Provenance} and {@code Interpretation} always render in full, and
 * {@code Caveats} always renders in full once non-empty (see {@link #render}'s comment on why). For
 * the bound to hold against any input despite that, every field of {@link RetrievalRequest} that can
 * carry model-authored, upstream-unbounded text has its own independent cap here:
 * <ul>
 *   <li>{@code restatement} — length-capped by {@link #capField} at {@link #RESTATEMENT_CAP}.</li>
 *   <li>{@code notes} — each entry length-capped at {@link #NOTE_CAP}, and the list itself
 *       count-capped at {@link #NOTES_LIMIT}. Both are necessary: {@code QuestionInterpreter}'s
 *       {@code MAX_ENTITIES} only caps how many entities *survive* validation, but one drop-note is
 *       appended per rejected entity *before* that truncation runs, so a response naming a thousand
 *       entities produces a thousand notes regardless of how few ultimately survive.</li>
 *   <li>{@code searchTerms} — each term length-capped at {@link #SEARCH_TERM_CAP}, composing with
 *       {@code QuestionInterpreter}'s {@code MAX_TERMS} count cap exactly the way length and count
 *       compose for {@code notes}: {@code MAX_TERMS} bounds how many terms survive, never how long
 *       one term is, since a term is unvalidated model text (never checked against
 *       {@code KbEntities.resolve}).</li>
 *   <li>{@code entities} carries no separate cap here — deliberately exempt, not overlooked: every
 *       surviving {@link EntityRef#value()} already passed {@code KbEntities.resolve}, so unlike
 *       {@code restatement}/{@code notes}/{@code searchTerms} it is a real KB identifier, not
 *       attacker-chosen text, and {@code MAX_ENTITIES = 4} already bounds how many render.</li>
 * </ul>
 * A renderer that advertises a cap but trusts part of its input to stay short (or few) is not
 * actually enforcing one — this list exists so the next field added to {@link RetrievalRequest} is
 * checked against it rather than becoming a fifth undiscovered path around {@link #EVIDENCE_CAP}.
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

    /** Per-field cap on {@code restatement} — see the class Javadoc's note on why this exists. */
    private static final int RESTATEMENT_CAP = 500;

    /** Per-entry cap on each {@code notes()} string — see the class Javadoc's note on why this exists. */
    private static final int NOTE_CAP = 300;

    /**
     * Cap on how many {@code notes()} entries render at all — see the class Javadoc's note on why
     * this exists alongside {@link #NOTE_CAP}. Modest on purpose: notes exist to explain drops, not
     * to enumerate every one, so a reader who wants to know "were things dropped and roughly why"
     * is served by the first several just as well as by all one thousand.
     */
    private static final int NOTES_LIMIT = 20;

    /** Per-term cap on each {@code searchTerms()} entry — see the class Javadoc's note on why this exists. */
    private static final int SEARCH_TERM_CAP = 100;

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
     * listed too, up to {@link #NOTES_LIMIT} of them, each capped at {@link #NOTE_CAP} characters —
     * the two caps compose, so neither an unusually long note nor an unusually large number of them
     * can defeat the other. Never silently absorbed either way: both truncations are stated. Search
     * terms are length-capped per term the same way (see the class Javadoc's field-by-field list).
     */
    private static String renderInterpretation(RetrievalRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Interpretation\n\n");
        sb.append("Interpreted as: ").append(capField(request.restatement(), RESTATEMENT_CAP)).append('\n');
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
                    .map(term -> capField(term, SEARCH_TERM_CAP))
                    .toList();
            sb.append("Search terms: ").append(String.join(", ", terms)).append('\n');
        }
        if (!request.notes().isEmpty()) {
            sb.append("Notes:\n");
            List<String> notes = request.notes();
            int shown = Math.min(notes.size(), NOTES_LIMIT);
            for (int i = 0; i < shown; i++) {
                sb.append("- ").append(capField(notes.get(i), NOTE_CAP)).append('\n');
            }
            if (shown < notes.size()) {
                int omitted = notes.size() - shown;
                sb.append("- +").append(omitted).append(" more note").append(omitted == 1 ? "" : "s")
                        .append(" omitted (showing ").append(shown).append(" of ").append(notes.size())
                        .append(")\n");
            }
        }
        sb.append('\n');
        return sb.toString();
    }

    private static String renderSection(Section section) {
        StringBuilder sb = new StringBuilder();
        sb.append("### [").append(section.source()).append("] ").append(sanitize(section.title())).append("\n\n");
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

    /**
     * Sanitizes, then truncates to {@code limit} characters with a stated marker — the character-
     * level counterpart to {@link Section#capped}'s fact-list truncation, for every
     * {@code Interpretation} field ({@code restatement}, each {@code notes} entry, each
     * {@code searchTerms} entry) that is model-authored prose with no upstream length bound (see the
     * class Javadoc's field-by-field list). Sanitizing before truncating, never after, so a cut can
     * never land inside an unneutralized {@code ```} and leave a fence fragment at the boundary.
     */
    private static String capField(String text, int limit) {
        String s = sanitize(text);
        if (s.length() <= limit) {
            return s;
        }
        return s.substring(0, limit) + " [truncated to " + limit + " of " + s.length() + " chars]";
    }

    private static String kindLabel(EntityKind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }
}
