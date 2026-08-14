package sdd.cli.review;

/**
 * The single definition of the 7-char sha form Gate-2 output uses throughout — reports, warnings,
 * and interactive prompts alike. Null and blank are not error cases here: a sha is frequently
 * absent (no checkpoint recorded, a hand-edited state.json) and the caller's sentence should read
 * naturally around {@code "(none)"} rather than forcing every call site to guard first.
 */
public final class Shas {

    private Shas() {
    }

    public static String shortSha(String sha) {
        if (sha == null || sha.isBlank()) {
            return "(none)";
        }
        return sha.substring(0, Math.min(7, sha.length()));
    }
}
