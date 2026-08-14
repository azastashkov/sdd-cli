package sdd.cli.explain;

import java.util.List;

/**
 * Call 2's result: {@code prose} is the narrator's free-text answer over the evidence string
 * (see {@link AnswerNarrator}) — plain prose, not JSON, unlike every other model call in this
 * codebase. When {@code unavailable} is true, {@code prose} is empty and {@code notes} carries
 * exactly one "answer unavailable: <reason>" sentence, the same never-silent-drop convention
 * {@link RetrievalRequest#notes()} uses for call 1. {@code notes} is empty when available.
 */
public record Answer(String prose, List<String> notes, boolean unavailable) {
    public Answer {
        notes = List.copyOf(notes);
    }
}
