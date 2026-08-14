package sdd.cli.explain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AnswerAudit} is a cheap, deterministic hallucination smoke alarm, not a correctness
 * check: it can only catch a bare NAME the narrator introduced that the evidence never showed
 * it. It cannot catch an invented relationship between two names that are each independently
 * present -- see {@link AnswerAudit}'s class Javadoc.
 */
class AnswerAuditTest {
    @TempDir Path ws;
    private Database db;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        ExplainFixture.seed(db.jdbi());
    }

    @Test
    void flagsARepoNamedInTheAnswerButAbsentFromTheEvidence() {
        String answer = "This behavior also touches " + ExplainFixture.SVC_BILLING + " somehow.";
        String evidence = "Provenance: 6 repos indexed\n\n### Interpretation\n\nIntent: describe\n\n";

        List<String> notes = AnswerAudit.check(answer, evidence, db.jdbi());

        assertThat(notes).anySatisfy(n -> assertThat(n).contains(ExplainFixture.SVC_BILLING));
    }

    @Test
    void doesNotFlagARepoPresentInBothAnswerAndEvidence() {
        String answer = "This describes " + ExplainFixture.LIB_CORE + " directly.";
        String evidence = "Facts about " + ExplainFixture.LIB_CORE + " are listed below.";

        List<String> notes = AnswerAudit.check(answer, evidence, db.jdbi());

        assertThat(notes).noneMatch(n -> n.contains(ExplainFixture.LIB_CORE));
    }

    @Test
    void flagsATopicNamedInTheAnswerButAbsentFromTheEvidence() {
        String answer = "This is unrelated to " + ExplainFixture.ORDERS_TOPIC + ".";
        String evidence = "no topic mentioned in this evidence at all";

        List<String> notes = AnswerAudit.check(answer, evidence, db.jdbi());

        assertThat(notes).anySatisfy(n -> assertThat(n).contains(ExplainFixture.ORDERS_TOPIC));
    }

    @Test
    void doesNotFlagATopicPresentInBoth() {
        String answer = "svc-orders produces " + ExplainFixture.ORDERS_TOPIC + ".";
        String evidence = "Kafka topic " + ExplainFixture.ORDERS_TOPIC + " has producer svc-orders.";

        List<String> notes = AnswerAudit.check(answer, evidence, db.jdbi());

        assertThat(notes).isEmpty();
    }

    @Test
    void noNamesInTheAnswerProducesNoNotes() {
        String answer = "The evidence above does not describe any repos or topics.";
        String evidence = "irrelevant";

        List<String> notes = AnswerAudit.check(answer, evidence, db.jdbi());

        assertThat(notes).isEmpty();
    }

    // --- word-boundary matching: reduces (does not eliminate) false positives ------------------

    @Test
    void aNameEmbeddedInsideALongerWordInTheAnswerIsNotFlagged() {
        // "platform" is one of this fixture's repo names AND an ordinary English word -- the known
        // false-positive risk documented on AnswerAudit. Whole-word matching at least keeps a
        // longer word that merely CONTAINS "platform" (e.g. "platformer") from tripping the check.
        String answer = "This uses a platformer analogy and names no repo directly.";
        String evidence = "no repo names appear in this evidence";

        List<String> notes = AnswerAudit.check(answer, evidence, db.jdbi());

        assertThat(notes).noneMatch(n -> n.contains("'" + ExplainFixture.PLATFORM + "'"));
    }
}
