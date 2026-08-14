package sdd.cli.explain;

import sdd.core.kb.EntityKind;

/**
 * A named thing the question refers to, already validated against the KB by
 * {@link QuestionInterpreter} — {@code value} is exactly what a later call to
 * {@code KbEntities.resolve(jdbi, kind, value)} will resolve again. {@code object} orients
 * {@link Intent#DEPENDENCY_PATH} ("why does A depend on B"): {@code true} means this entity is
 * B, {@code false} (the default the model's "subject" role maps to) means A. Every other intent
 * ignores it.
 */
public record EntityRef(EntityKind kind, String value, boolean object) {
}
