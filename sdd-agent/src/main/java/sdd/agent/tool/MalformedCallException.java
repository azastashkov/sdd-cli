package sdd.agent.tool;

/** Unparseable arguments, unknown tool, or a missing required argument — a malformed strike. */
public class MalformedCallException extends RuntimeException {
    public MalformedCallException(String message) {
        super(message);
    }
}
