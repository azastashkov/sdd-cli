package sdd.core.llm;

public class ModelException extends RuntimeException {
    private final int statusCode;

    public ModelException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public ModelException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    public int statusCode() { return statusCode; }
}
