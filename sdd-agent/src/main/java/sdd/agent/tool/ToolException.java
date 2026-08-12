package sdd.agent.tool;

/** A well-formed tool call that failed legitimately (file not found, no match, ...). */
public class ToolException extends RuntimeException {
    public ToolException(String message) {
        super(message);
    }
}
