package sdd.core.config;

/**
 * A human's statement that {@code hostRepo} loads {@code moduleRepo}'s bundle at run time, under
 * the manifest name {@code remote}.
 *
 * <p>Declared rather than derived: the link between a manifest entry and the repo that builds its
 * bundle lives in a bundler configuration the indexer does not read, so inferring it from a
 * filename would put a guess in the knowledge base wearing the same clothes as a parsed fact. The
 * shape mirrors {@link ManualEdge}, which exists for the same reason on the REST side.
 */
public record RuntimeEdge(String hostRepo, String remote, String moduleRepo) {
}
