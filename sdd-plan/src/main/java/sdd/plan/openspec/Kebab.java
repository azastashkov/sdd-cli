package sdd.plan.openspec;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one definition of OpenSpec's identifier grammar, and the only place that coerces a string
 * into it.
 *
 * <p>OpenSpec validates change ids and every capability-path segment against
 * {@code /^[a-z0-9]+(?:-[a-z0-9]+)*$/u} — its own {@code KEBAB_ID_REGEX}, shared by change ids and
 * store ids. A value that fails it is rejected outright, so every id this package emits is coerced
 * here and asserted against {@link #VALID} by construction.
 *
 * <p>Coerce rather than reject: sdd's spec ids ({@code SPEC-101}, {@code SPEC_TIER_SPREADS}) and
 * contract ids are human-chosen and predate any knowledge of OpenSpec. Refusing to export because a
 * spec id has an underscore would make the export fail for a reason the author cannot act on from
 * inside sdd. A coerced id that round-trips is strictly better than no export, and the caller
 * records the coercion where a human sees it.
 */
public final class Kebab {

    /** OpenSpec's own grammar, transcribed. Verified against Fission-AI/OpenSpec v1.10.0. */
    public static final Pattern VALID = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    /**
     * What an input that coerces to nothing becomes. Reachable from a spec id of only punctuation
     * or only non-ASCII — improbable, but a generator that can emit an empty path segment produces
     * {@code openspec/changes//proposal.md}, which is a worse failure than a placeholder somebody
     * can see and rename.
     */
    static final String FALLBACK = "sdd-change";

    private Kebab() {
    }

    /**
     * Lowercases, replaces every run of non-{@code [a-z0-9]} with a single hyphen, and trims
     * leading and trailing hyphens.
     *
     * <p>Non-ASCII is dropped rather than transliterated. Transliteration is locale-dependent and
     * would make the id a function of the machine that ran the plan, which is the one property this
     * package cannot have — the export's idempotence rule compares bytes.
     */
    public static String of(String raw) {
        if (raw == null) {
            return FALLBACK;
        }
        String kebab = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        return kebab.isEmpty() ? FALLBACK : kebab;
    }

    /** Whether a value is already a legal OpenSpec id, i.e. {@code of(v).equals(v)}. */
    public static boolean isValid(String value) {
        return value != null && VALID.matcher(value).matches();
    }
}
