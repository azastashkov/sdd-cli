package sdd.plan.spec;

import java.util.Locale;
import java.util.Objects;

/** A KB hint from the spec author — verified against the knowledge base in Phase 3B, never trusted. */
public record Touchpoint(Kind kind, String value) {
    public Touchpoint {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(value);
    }

    public enum Kind {
        REPO, ENDPOINT, TOPIC, CLASS, ARTIFACT;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind fromKey(String key) {
            for (Kind kind : values()) {
                if (kind.key().equals(key)) {
                    return kind;
                }
            }
            return null;
        }
    }
}
