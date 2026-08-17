package sdd.plan.source;

import java.util.List;

/**
 * The documents backing one spec-ingestion run, plus operator-facing notes about the bundle
 * itself (a document dropped for budget, a future fetcher's partial failure, ...). A one-page
 * Confluence export is the degenerate one-document case — {@code ConfluenceExportSource} wraps
 * it in a {@code SourceBundle} rather than {@link sdd.plan.confluence.ConfluenceNormalizer}
 * keeping two ingestion shapes.
 */
public record SourceBundle(List<SourceDoc> docs, List<String> notes) {
    public SourceBundle {
        docs = List.copyOf(docs);
        notes = List.copyOf(notes);
    }
}
