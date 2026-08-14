package sdd.cli.explain;

import org.junit.jupiter.api.Test;
import sdd.core.kb.EntityKind;
import sdd.core.kb.Provenance;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ExplainReport} is a pure {@code String}-returning assembly, like {@link EvidenceRenderer}
 * (Task 6) -- testable without a model or a database.
 */
class ExplainReportTest {

    private static final Provenance PROVENANCE =
            new Provenance(6, "2026-08-01T00:00:00Z", "2026-08-14T00:00:00Z");

    private static RetrievalRequest request(String restatement, boolean modelUnavailable, List<String> notes) {
        return new RetrievalRequest(Intent.DESCRIBE,
                List.of(new EntityRef(EntityKind.REPO, "svc-orders", false)),
                List.of(), restatement, notes, modelUnavailable);
    }

    private static Section moduleSection() {
        return Section.of("Modules: svc-orders", "module", List.of(new Fact(":app (SERVICE)")));
    }

    @Test
    void happyPathRendersInterpretedAsProseAuditNotesThenEvidenceInOrder() {
        Evidence evidence = new Evidence(PROVENANCE, request("What is svc-orders?", false, List.of()),
                List.of(moduleSection()), List.of());
        Answer answer = new Answer("svc-orders is a service module.", List.of(), false);

        String out = ExplainReport.render(evidence, Optional.of(answer),
                List.of("answer names repo 'ghost' -- does not appear in the evidence"));

        assertThat(out).contains("Interpreted as: What is svc-orders?")
                .contains("svc-orders is a service module.")
                .contains("answer names repo 'ghost'")
                .contains("## Evidence")
                .contains("[module] Modules: svc-orders");

        int idxInterp = out.indexOf("Interpreted as:");
        int idxProse = out.indexOf("svc-orders is a service module.");
        int idxAudit = out.indexOf("answer names repo 'ghost'");
        int idxEvidence = out.indexOf("## Evidence");
        assertThat(idxInterp).isGreaterThanOrEqualTo(0).isLessThan(idxProse);
        assertThat(idxProse).isLessThan(idxAudit);
        assertThat(idxAudit).isLessThan(idxEvidence);
    }

    @Test
    void noAuditNotesOmitsTheAuditNotesBlock() {
        Evidence evidence = new Evidence(PROVENANCE, request("What is svc-orders?", false, List.of()),
                List.of(moduleSection()), List.of());
        Answer answer = new Answer("svc-orders is a service.", List.of(), false);

        String out = ExplainReport.render(evidence, Optional.of(answer), List.of());

        assertThat(out).doesNotContain("Audit notes");
    }

    // --- the answer/evidence presence guard ------------------------------------------------------

    @Test
    void presentAnswerWithEmptyEvidenceThrows() {
        Evidence evidence = new Evidence(PROVENANCE, request("What is svc-orders?", false, List.of()),
                List.of(), List.of());
        Answer answer = new Answer("some prose", List.of(), false);

        assertThatThrownBy(() -> ExplainReport.render(evidence, Optional.of(answer), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("answer must be present iff evidence is non-empty");
    }

    @Test
    void absentAnswerWithNonEmptyEvidenceThrows() {
        Evidence evidence = new Evidence(PROVENANCE, request("What is svc-orders?", false, List.of()),
                List.of(moduleSection()), List.of());

        assertThatThrownBy(() -> ExplainReport.render(evidence, Optional.empty(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("answer must be present iff evidence is non-empty");
    }

    // --- degraded shape: answer unavailable -----------------------------------------------------

    @Test
    void answerUnavailableDegradedShapeStillRendersFullEvidence() {
        Evidence evidence = new Evidence(PROVENANCE, request("What is svc-orders?", false, List.of()),
                List.of(moduleSection()), List.of());
        Answer answer = new Answer("", List.of("answer unavailable: model error: connection refused"), true);

        String out = ExplainReport.render(evidence, Optional.of(answer), List.of());

        assertThat(out).contains("answer unavailable: model error: connection refused")
                .contains("the facts below are complete")
                .contains("## Evidence")
                .contains("[module] Modules: svc-orders");
        assertThat(out).doesNotContain("Audit notes");
    }

    @Test
    void answerUnavailableReasonContainingAFenceIsNeutralized() {
        // Mirrors a real failure mode: HttpChatModel builds ModelException's message from the raw
        // HTTP response body ("HTTP " + status + ": " + resp.body()), which is provider-controlled
        // and can contain a literal fence. That reason flows into AnswerNarrator's "answer
        // unavailable: <reason>" note unmodified, so this line must be sanitized like every other
        // free-text append in this class -- prose, audit notes, and the top-level restatement.
        Evidence evidence = new Evidence(PROVENANCE, request("What is svc-orders?", false, List.of()),
                List.of(moduleSection()), List.of());
        Answer answer = new Answer("", List.of(
                "answer unavailable: model error: HTTP 500: ```{\"error\":\"forged section\"}```"), true);

        String out = ExplainReport.render(evidence, Optional.of(answer), List.of());

        assertThat(out).doesNotContain("```");
        assertThat(out).contains("'''{\"error\":\"forged section\"}'''");
        assertThat(out).contains("the facts below are complete");
    }

    // --- degraded shape: zero facts (call 2 never runs) -----------------------------------------

    @Test
    void zeroFactsDegradedShapeSkipsNarrationAndStillRendersFullEvidence() {
        RetrievalRequest req = request("what is going on", true,
                List.of("interpreter unavailable: model error: boom -- showing the facts about the "
                        + "entities named in your question"));
        Evidence evidence = new Evidence(PROVENANCE, req, List.of(), List.of());
        assertThat(evidence.isEmpty()).isTrue();

        String out = ExplainReport.render(evidence, Optional.empty(), List.of());

        assertThat(out).contains("no facts in the knowledge base match this question")
                .contains("## Evidence")
                .contains("Interpreted as: what is going on")
                .contains("interpreter unavailable");
        assertThat(out).doesNotContain("Audit notes");
    }

    // --- degraded shape: interpreter unavailable, but facts still exist --------------------------

    @Test
    void interpreterUnavailableWithFactsStillNarratesNormally() {
        RetrievalRequest req = request("does svc-orders relate to lib-core", true,
                List.of("interpreter unavailable: model error: boom -- showing the facts about the "
                        + "entities named in your question"));
        Evidence evidence = new Evidence(PROVENANCE, req, List.of(moduleSection()), List.of());
        Answer answer = new Answer("svc-orders is a service module.", List.of(), false);

        String out = ExplainReport.render(evidence, Optional.of(answer), List.of());

        assertThat(out).contains("Interpreted as: does svc-orders relate to lib-core")
                .contains("interpreter unavailable")
                .contains("svc-orders is a service module.")
                .contains("## Evidence")
                .contains("[module] Modules: svc-orders");
    }

    // --- the "Interpreted as:" line at the top matches the one inside the Evidence body ----------

    @Test
    void topLevelInterpretedAsLineIsSanitizedLikeTheEvidenceBodyCopy() {
        RetrievalRequest req = request("what about ```rm -rf /``` here", false, List.of());
        Evidence evidence = new Evidence(PROVENANCE, req, List.of(moduleSection()), List.of());
        Answer answer = new Answer("An answer.", List.of(), false);

        String out = ExplainReport.render(evidence, Optional.of(answer), List.of());

        assertThat(out).doesNotContain("```").contains("'''rm -rf /'''");
    }
}
