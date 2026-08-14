package sdd.cli.explain;

/**
 * What an {@code sdd explain} question is asking for. Chosen by {@link QuestionInterpreter} and
 * consumed by the deterministic collectors (Tasks 4-5) — this enum never carries a fact, only a
 * shape of lookup.
 */
public enum Intent { DESCRIBE, CONSUMERS, DEPENDENCY_PATH, IMPACT, SEARCH }
