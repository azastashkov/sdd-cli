package sdd.plan.source;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceDocTest {

    @Test
    void defensiveCopiesAttachmentsSoCallerMutationCannotLeak() {
        List<String> attachments = new ArrayList<>(List.of("a.png"));
        SourceDoc doc = new SourceDoc(SourceDoc.Kind.FREE_TEXT, "id-1", null, null, null, "text",
                attachments);
        attachments.add("b.png");

        assertThat(doc.attachments()).containsExactly("a.png");
        assertThatThrownBy(() -> doc.attachments().add("c.png"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void urlTitleAndVersionMayBeNullFreeTextHasNoUrl() {
        SourceDoc doc = new SourceDoc(SourceDoc.Kind.FREE_TEXT, "id-1", null, null, null, "text",
                List.of());
        assertThat(doc.url()).isNull();
        assertThat(doc.title()).isNull();
        assertThat(doc.version()).isNull();
    }

    @Test
    void kindIdAndTextAreRequired() {
        assertThatThrownBy(() -> new SourceDoc(null, "id-1", null, null, null, "text", List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SourceDoc(SourceDoc.Kind.FREE_TEXT, null, null, null, null,
                "text", List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SourceDoc(SourceDoc.Kind.FREE_TEXT, "id-1", null, null, null,
                null, List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void labelPrefersTitleFallsBackToIdOmitsUrlWhenAbsent() {
        SourceDoc titled = new SourceDoc(SourceDoc.Kind.CONFLUENCE_PAGE, "spec-order-api",
                "https://confluence.corp.local/pages/1", "Order API spec", "v7", "text", List.of());
        assertThat(titled.label()).isEqualTo("Order API spec (https://confluence.corp.local/pages/1)");

        SourceDoc untitledNoUrl = new SourceDoc(SourceDoc.Kind.FREE_TEXT, "text-1", null, null,
                null, "text", List.of());
        assertThat(untitledNoUrl.label()).isEqualTo("text-1");

        SourceDoc blankTitle = new SourceDoc(SourceDoc.Kind.JIRA_ISSUE, "PROJ-123",
                "https://jira.corp.local/browse/PROJ-123", "  ", null, "text", List.of());
        assertThat(blankTitle.label()).isEqualTo("PROJ-123 (https://jira.corp.local/browse/PROJ-123)");
    }
}
