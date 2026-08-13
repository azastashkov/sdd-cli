package sdd.cli.review;

/** A repo's Gate-2 human verdict for one run. PENDING is the implicit default for any repo not
 *  yet recorded — see {@link Decisions#of}. */
public enum Decision {
    PENDING, APPROVED, REJECTED, REDO
}
