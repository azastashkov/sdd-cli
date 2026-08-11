package sdd.plan.spec;

import java.util.Objects;

/** One ID-prefixed spec bullet: R1/A1/C1/Q1 plus its text. */
public record SpecItem(String id, String text) {
    public SpecItem {
        Objects.requireNonNull(id);
        Objects.requireNonNull(text);
    }
}
