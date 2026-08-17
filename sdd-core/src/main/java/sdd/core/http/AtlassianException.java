package sdd.core.http;

/**
 * Transport and auth failures talking to Jira, Confluence or Bitbucket. One exception per bounded
 * context, no common base class — see {@code sdd.core.config.ConfigException} and
 * {@code sdd.core.llm.ModelException}, which this repo keeps deliberately separate rather than
 * unifying under a shared {@code SddException}, so that a {@code catch} clause names the failure
 * domain it actually handles instead of "anything sdd could throw".
 */
public class AtlassianException extends RuntimeException {
    public AtlassianException(String message) { super(message); }
    public AtlassianException(String message, Throwable cause) { super(message, cause); }
}
