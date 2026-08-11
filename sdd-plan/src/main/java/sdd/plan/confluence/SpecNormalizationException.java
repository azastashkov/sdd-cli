package sdd.plan.confluence;

/** Spec ingestion failed before a normalized spec could be produced — rerun after fixing the cause. */
public class SpecNormalizationException extends RuntimeException {
    public SpecNormalizationException(String message) {
        super(message);
    }

    public SpecNormalizationException(String message, Throwable cause) {
        super(message, cause);
    }
}
