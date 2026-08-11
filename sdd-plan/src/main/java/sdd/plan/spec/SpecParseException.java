package sdd.plan.spec;

/** Structural spec error, pinned to a 1-based line number. */
public class SpecParseException extends RuntimeException {
    private final int line;

    public SpecParseException(int line, String message) {
        super("line " + line + ": " + message);
        this.line = line;
    }

    public int line() {
        return line;
    }
}
