package sdd.cli.explain;

import sdd.core.contract.Markdown;

import java.util.List;
import java.util.Optional;

/**
 * Assembles the final printed output of {@code sdd explain}: {@code Interpreted as: <restatement>},
 * the narrator's prose, any {@link AnswerAudit} notes, then the full {@code ## Evidence} section
 * (Task 6's {@link EvidenceRenderer#render(Evidence)}, unmodified — the same string call 2 was
 * shown). Pure and deterministic, like {@link EvidenceRenderer}: no {@code Jdbi}, no
 * {@code ChatModel}, no I/O, so the assembly itself is testable without either.
 *
 * <p>Handles the three degraded shapes a caller (Task 8's {@code ExplainCommand}) can be in, and
 * in every one of them still renders the full {@code ## Evidence} — a human is never left without
 * the facts just because the prose above them is missing or was never produced:
 * <ul>
 *   <li><b>Zero facts</b> — {@link Evidence#isEmpty()}: call 2 was never made (there was nothing
 *       to narrate), so {@code answer} is {@link Optional#empty()}. Prints a fixed message
 *       instead of prose.</li>
 *   <li><b>Answer unavailable</b> — {@code answer.get().unavailable()}: call 2 failed or returned
 *       nothing usable. Prints {@code answer.notes()}'s reason plus a note that the evidence below
 *       is complete regardless.</li>
 *   <li><b>Interpreter unavailable</b> — {@code evidence.request().modelUnavailable()}: call 1
 *       fell back to literal matching. This needs no special branch here — the fallback's
 *       restatement and notes are already ordinary fields on {@link RetrievalRequest} and render
 *       exactly like any other request, both in the top line and inside {@code ## Evidence}.</li>
 * </ul>
 */
public final class ExplainReport {

    private ExplainReport() {
    }

    /**
     * @param answer must be {@link Optional#empty()} iff {@code evidence.isEmpty()} — the signal
     *               that call 2 was skipped entirely because there was nothing to narrate. Any
     *               other call must pass a present {@link Answer} (available or
     *               {@code unavailable()}). Compiler-enforced by the {@code Optional} type; a
     *               caller that gets the two out of sync gets an {@link IllegalArgumentException}
     *               naming the contract, not a bare NPE.
     * @param auditNotes {@link AnswerAudit#check}'s output; ignored (never rendered) unless
     *                   {@code answer} is present and available, since there is no prose to audit
     *                   in the other two shapes.
     */
    public static String render(Evidence evidence, Optional<Answer> answer, List<String> auditNotes) {
        if (evidence.isEmpty() == answer.isPresent()) {
            throw new IllegalArgumentException(
                    "answer must be present iff evidence is non-empty: evidence.isEmpty()=" + evidence.isEmpty()
                            + " but answer.isPresent()=" + answer.isPresent()
                            + " — pass Optional.empty() only when call 2 was skipped because there were no facts");
        }

        StringBuilder out = new StringBuilder();
        out.append("Interpreted as: ")
                .append(EvidenceRenderer.restatementLine(evidence.request()))
                .append("\n\n");

        if (evidence.isEmpty()) {
            out.append("no facts in the knowledge base match this question\n\n");
        } else {
            Answer a = answer.get();
            if (a.unavailable()) {
                String reason = a.notes().isEmpty() ? "answer unavailable" : a.notes().get(0);
                out.append(Markdown.neutralizeFences(reason)).append(" — the facts below are complete\n\n");
            } else {
                out.append(Markdown.neutralizeFences(a.prose())).append("\n\n");
                if (!auditNotes.isEmpty()) {
                    out.append("Audit notes:\n");
                    for (String note : auditNotes) {
                        out.append("- ").append(Markdown.neutralizeFences(note)).append('\n');
                    }
                    out.append('\n');
                }
            }
        }

        out.append("## Evidence\n\n");
        out.append(EvidenceRenderer.render(evidence));
        return out.toString();
    }
}
