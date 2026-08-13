package sdd.core.llm;

public class ModelException extends RuntimeException {
    private final int statusCode;
    private final long tokensSoFar;

    public ModelException(String message, int statusCode) {
        this(message, statusCode, 0L);
    }

    public ModelException(String message, int statusCode, long tokensSoFar) {
        super(message);
        this.statusCode = statusCode;
        this.tokensSoFar = tokensSoFar;
    }

    public ModelException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.tokensSoFar = 0L;
    }

    public int statusCode() {
        return statusCode;
    }

    /** Prompt+completion tokens the failed run had already spent when this was thrown (best effort). */
    public long tokensSoFar() {
        return tokensSoFar;
    }

    /** A copy carrying the caller's running token total, chaining this exception as the cause. */
    public ModelException withTokens(long total) {
        ModelException copy = new ModelException(getMessage(), statusCode, total);
        copy.initCause(this);
        return copy;
    }
}
