package sdd.plan.source;

import org.junit.jupiter.api.Test;
import sdd.plan.confluence.SpecNormalizationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceBudgetTest {

    private static SourceDoc doc(SourceDoc.Kind kind, String id, String title, int chars) {
        return new SourceDoc(kind, id, "https://example.local/" + id, title, null, "x".repeat(chars),
                List.of());
    }

    @Test
    void totalWithinBudgetDropsNothingAndKeepsExistingNotesVerbatim() {
        SourceBundle bundle = new SourceBundle(
                List.of(doc(SourceDoc.Kind.FREE_TEXT, "f1", "F", 1_000)),
                List.of("pre-existing note"));

        SourceBundle capped = SourceBudget.apply(bundle);

        assertThat(capped.docs()).containsExactly(bundle.docs().get(0));
        assertThat(capped.notes()).containsExactly("pre-existing note");
    }

    @Test
    void dropsByPriorityNotByListPositionAndNeverTruncatesAKeptDocument() {
        // CONFLUENCE_PAGE is listed FIRST but FREE_TEXT outranks it — the higher-priority
        // document must survive whole (untruncated) even though it is listed second
        SourceDoc page = doc(SourceDoc.Kind.CONFLUENCE_PAGE, "c1", "Page", 150_000);
        SourceDoc text = doc(SourceDoc.Kind.FREE_TEXT, "f1", "Text", 200_000);
        SourceBundle bundle = new SourceBundle(List.of(page, text), List.of());

        SourceBundle capped = SourceBudget.apply(bundle);

        assertThat(capped.docs()).containsExactly(text);
        assertThat(capped.docs().get(0).text()).hasSize(200_000);
        assertThat(capped.notes()).hasSize(1);
        assertThat(capped.notes().get(0)).contains("Page").contains("https://example.local/c1");
    }

    @Test
    void tiedPriorityBreaksTowardsTheLaterDocumentInListOrder() {
        SourceDoc keep1 = doc(SourceDoc.Kind.FREE_TEXT, "f1", "Keep one", 100_000);
        SourceDoc keep2 = doc(SourceDoc.Kind.CONFLUENCE_PAGE, "c1", "Keep two", 90_000);
        SourceDoc drop1 = doc(SourceDoc.Kind.JIRA_COMMENT, "j1", "Comment one", 90_000);
        SourceDoc drop2 = doc(SourceDoc.Kind.JIRA_COMMENT, "j2", "Comment two", 90_000);
        SourceBundle bundle = new SourceBundle(List.of(keep1, keep2, drop1, drop2), List.of());

        SourceBundle capped = SourceBudget.apply(bundle);

        assertThat(capped.docs()).containsExactly(keep1, keep2, drop1);
        assertThat(capped.notes()).hasSize(1);
        assertThat(capped.notes().get(0)).contains("Comment two");
    }

    @Test
    void dropsMultipleWholeDocumentsUntilTheTotalFits() {
        SourceDoc keep = doc(SourceDoc.Kind.FREE_TEXT, "f1", "Keep", 200_000);
        SourceDoc dropA = doc(SourceDoc.Kind.JIRA_COMMENT, "j1", "Comment A", 150_000);
        SourceDoc dropB = doc(SourceDoc.Kind.JIRA_COMMENT, "j2", "Comment B", 150_000);
        SourceBundle bundle = new SourceBundle(List.of(keep, dropA, dropB), List.of());

        SourceBundle capped = SourceBudget.apply(bundle);

        assertThat(capped.docs()).containsExactly(keep);
        assertThat(capped.notes()).hasSize(2);
        assertThat(capped.notes().get(0)).contains("Comment B");
        assertThat(capped.notes().get(1)).contains("Comment A");
    }

    @Test
    void aSingleDocumentOverTheBudgetFailsLoudlyInsteadOfEmptyingTheBundle() {
        // FREE_TEXT never passes through ConfluenceExtract, so nothing else caps its size before
        // it reaches here — dropping it silently would leave zero documents and send the model
        // an empty prompt
        SourceDoc oversized = doc(SourceDoc.Kind.FREE_TEXT, "text-1", "Huge requirement", 300_001);
        SourceBundle bundle = new SourceBundle(List.of(oversized), List.of());

        assertThatThrownBy(() -> SourceBudget.apply(bundle))
                .isInstanceOf(SpecNormalizationException.class)
                .hasMessageContaining("Huge requirement")
                .hasMessageContaining("300001")
                .hasMessageContaining("300000");
    }

    @Test
    void anOversizedDocumentFailsLoudlyEvenAlongsideDocumentsThatWouldOtherwiseFit() {
        // the oversized document can never fit no matter what else is dropped, so this must
        // fail before any dropping happens rather than quietly discarding the small, valid doc
        SourceDoc oversized = doc(SourceDoc.Kind.FREE_TEXT, "text-1", "Huge requirement", 400_000);
        SourceDoc small = doc(SourceDoc.Kind.FREE_TEXT, "text-2", "Small note", 1_000);
        SourceBundle bundle = new SourceBundle(List.of(oversized, small), List.of());

        assertThatThrownBy(() -> SourceBudget.apply(bundle))
                .isInstanceOf(SpecNormalizationException.class)
                .hasMessageContaining("Huge requirement");
    }

    @Test
    void aConfluenceDocumentAtExactlyTheCapIsNotRejected() {
        // ConfluenceExtract itself rejects anything over MAX_TEXT_CHARS (300_000), so a
        // confluence document arriving here at exactly the boundary must still be accepted
        SourceDoc atCap = doc(SourceDoc.Kind.CONFLUENCE_PAGE, "c1", "At the cap", 300_000);
        SourceBundle bundle = new SourceBundle(List.of(atCap), List.of());

        SourceBundle capped = SourceBudget.apply(bundle);

        assertThat(capped.docs()).containsExactly(atCap);
    }
}
