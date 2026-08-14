package sdd.cli.explain;

import java.util.List;

/**
 * The validated output of the interpret step: which intent, which KB entities (each already
 * resolved to at least one repo), and which free-text search terms. {@code restatement} is the
 * model's paraphrase of the question, printed verbatim as {@code Interpreted as: ...} so a
 * misread question is visible rather than silently answered. {@code notes} carries every
 * rejection and downgrade the validator made, human-readable, never a silent drop. No SQL, no
 * table names, no limits ever appear here — retrieval itself is entirely the deterministic
 * collectors' job.
 */
public record RetrievalRequest(Intent intent, List<EntityRef> entities, List<String> searchTerms,
                                String restatement, List<String> notes, boolean modelUnavailable) {
    public RetrievalRequest {
        entities = List.copyOf(entities);
        searchTerms = List.copyOf(searchTerms);
        notes = List.copyOf(notes);
    }
}
