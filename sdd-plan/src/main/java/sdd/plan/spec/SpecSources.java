package sdd.plan.spec;

import java.util.Locale;

/** Ref-shape dispatch for the SpecSource seam. */
public final class SpecSources {
    private SpecSources() {
    }

    public static boolean isConfluenceExport(String ref) {
        String lower = ref.toLowerCase(Locale.ROOT);
        return lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xhtml");
    }
}
