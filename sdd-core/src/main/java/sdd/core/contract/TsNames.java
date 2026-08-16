package sdd.core.contract;

/**
 * How a TypeScript symbol is addressed in a {@code ts-api} declaration, in one place.
 *
 * <p>Two different names for one export are both correct and easy to transpose. The knowledge base
 * records a symbol as {@code <specifier>.<Export>} — the name a CONSUMER writes, resolved through
 * the package's exports map — because that is what joins two repos. A {@code ts-api} declaration
 * addresses it as {@code <specifier>#<Export>}, because the specifier is the unit the actualizer
 * selects by. The separators are the same two characters in the opposite order, so a renderer that
 * reuses the java-api template ({@code <fqcn>#<member>}) produces something that looks right,
 * validates, and names nothing.
 *
 * <p>That is not hypothetical: {@code PlanDrafter}'s knowledge-base evidence did exactly this, so
 * the one place a model is told to copy declarations from disagreed with the grammar it was asked
 * to emit — and since the prompt tells it to omit rather than guess, ts-api contracts were drafted
 * with no declarations at all, which in turn left Gate 2 with nothing to check.
 */
public final class TsNames {

    /** The synthetic member name the sidecar gives a const's or a non-object alias's written type —
     *  the whole export IS that type, so the member does not get its own dotted suffix. */
    public static final String VALUE_MEMBER = "<value>";

    private TsNames() {
    }

    /** {@code <specifier>.<Export>} → {@code <specifier>#<Export>}. */
    public static String address(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn : fqcn.substring(0, dot) + "#" + fqcn.substring(dot + 1);
    }

    /** The export name out of {@code <specifier>.<Export>}. */
    public static String exportOf(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn : fqcn.substring(dot + 1);
    }

    /**
     * A member's left-hand side, relative to its own export.
     *
     * <p>A function's single member and a const's written type ARE the export, so prefixing would
     * read as {@code login.login(...)}; everything else is a dotted member of it.
     */
    public static String memberLabel(String exportName, String memberName, String signature) {
        if (VALUE_MEMBER.equals(memberName) || exportName.equals(memberName)) {
            return exportName + signature.substring(memberName.length());
        }
        return exportName + "." + signature;
    }

    /** The fully addressed member line's left-hand side: {@code <specifier>#<Export>[.<member>]}. */
    public static String memberAddress(String fqcn, String memberName, String signature) {
        int dot = fqcn.lastIndexOf('.');
        String specifier = dot < 0 ? fqcn : fqcn.substring(0, dot);
        return specifier + "#" + memberLabel(exportOf(fqcn), memberName, signature);
    }
}
