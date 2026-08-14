package sdd.cli.explain;

import java.util.Objects;

/**
 * One deterministic, KB-grounded statement inside a {@link Section}. A {@code Fact} never
 * carries a table name or a citation on its own — that context lives on the enclosing
 * {@link Section} ({@code title}/{@code source}) so every fact in the section shares it.
 */
public record Fact(String text) {
    public Fact {
        Objects.requireNonNull(text);
    }
}
