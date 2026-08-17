package sdd.plan.source;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceBundleTest {

    private static final SourceDoc DOC = new SourceDoc(SourceDoc.Kind.FREE_TEXT, "text-1", null,
            null, null, "text", List.of());

    @Test
    void defensiveCopiesBothLists() {
        List<SourceDoc> docs = new ArrayList<>(List.of(DOC));
        List<String> notes = new ArrayList<>(List.of("note one"));
        SourceBundle bundle = new SourceBundle(docs, notes);
        docs.add(DOC);
        notes.add("note two");

        assertThat(bundle.docs()).containsExactly(DOC);
        assertThat(bundle.notes()).containsExactly("note one");
        assertThatThrownBy(() -> bundle.docs().add(DOC)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> bundle.notes().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }
}
