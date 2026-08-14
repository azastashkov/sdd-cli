package sdd.core.kb;

/** Summary of how much and how current the indexed KB is. Timestamps are null when repo has none. */
public record Provenance(int repoCount, String earliestIndexedAt, String latestIndexedAt) {
}
