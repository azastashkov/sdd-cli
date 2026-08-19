package sdd.core.kb;

import java.util.Locale;

/** The kinds of KB entities that a free-text or spec-authored value can resolve against. */
public enum EntityKind {
    REPO, ENDPOINT, TOPIC, CLASS, ARTIFACT,

    /**
     * A TypeScript export, addressed by the specifier a consumer imports —
     * {@code @acme/web-sdk.Tick}.
     *
     * <p>Separate from {@link #CLASS} even though both live in {@code java_type}, because
     * {@code CLASS} resolves a dotless name by SUFFIX ({@code fqcn LIKE '%.' || :v}) and that rule
     * is a Java package convention. Sharing one kind would make the bare name {@code HttpClient}
     * resolve to both {@code com.trading.http.HttpClient} and {@code @acme/web-sdk.HttpClient} in
     * the same citation — a silent cross-language conflation in the one place a reader trusts
     * absolutely. {@code SYMBOL} is exact-match only, so a name either is the published one or
     * does not resolve.
     */
    SYMBOL,

    /**
     * A Spring configuration property, addressed by its key — {@code pricing.tier.refresh-interval},
     * or a prefix of one.
     *
     * <p>{@code config_property} has been written by {@code sdd index} for every Spring module since
     * V1 and, until this kind existed, was read by nothing in the planning pipeline. That made
     * config keys a category of thing a human task routinely names and the planner could not see —
     * not because the fact was missing, but because nothing asked for it.
     *
     * <p>Unlike the other kinds this one resolves a PREFIX as well as an exact key, anchored at a
     * segment boundary: a reader who writes {@code pricing.tier} means the sub-tree, and requiring
     * the leaf would make the kind useless for the "which repos own this config area" question it
     * exists to answer. The boundary anchor is what stops a typo ({@code pricing.tie}) resolving to
     * something real, which would be worse than missing.
     */
    CONFIG;

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
