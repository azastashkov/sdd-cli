package sdd.plan.approve;

/** Structural plan.md error, pinned to a 1-based line number. */
public class PlanParseException extends RuntimeException {
    private final int line;

    public PlanParseException(int line, String message) {
        super("line " + line + ": " + message);
        this.line = line;
    }

    public int line() {
        return line;
    }
}
