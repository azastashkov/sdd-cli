package sdd.plan.spec;

/**
 * The spec-ingestion seam (design amendment 2026-08-11): implementations are selected by ref
 * shape. v1: MarkdownSpecSource (canonical passthrough) and ConfluenceExportSource. Future
 * adapters (canonical SDD format, Confluence REST API) plug in here.
 */
public interface SpecSource {
    NormalizedSpec load(String ref);
}
