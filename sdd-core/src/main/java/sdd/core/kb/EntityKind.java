package sdd.core.kb;

import java.util.Locale;

/** The kinds of KB entities that a free-text or spec-authored value can resolve against. */
public enum EntityKind {
    REPO, ENDPOINT, TOPIC, CLASS, ARTIFACT;

    /**
     * This kind's lower-case name, for prose that reads about the entity rather than about the
     * enum ("Resolved endpoint 'GET /orders/{id}'"). {@link Locale#ROOT}, never the JVM default:
     * under a Turkish default locale {@code "CLASS".toLowerCase()} maps {@code I} to the dotless
     * {@code ı}, so the label a reader sees would depend on the machine that rendered it.
     */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
