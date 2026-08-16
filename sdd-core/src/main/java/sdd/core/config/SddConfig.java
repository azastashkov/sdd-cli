package sdd.core.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * No {@code retrieval} field: {@code sdd.yml}'s {@code retrieval} key is still parsed and validated
 * by {@link ConfigLoader#load}, via the private {@code rejectUnimplementedRetrieval} — but only to
 * reject {@code embeddings} (no {@code EmbeddingsRetriever} exists; every command retrieves with
 * SQLite FTS5 regardless of this key) and anything other than {@code fts}. There is nothing to
 * store: FTS is the only backend, so a stored value could never drive a choice. Keeping the
 * validation without a field is deliberate — dropping it too would let snakeyaml silently swallow
 * {@code retrieval: embeddings} and revive the exact false promise this record removed the field to
 * stop making.
 */
public record SddConfig(
        Path workspace,
        Map<String, ModelEndpoint> models,
        Map<Integer, Path> jdkHomes,
        /**
         * Where {@code node} lives, or null to take it from PATH. The direct analogue of
         * {@code jdkHomes}, and needed for the same reason: these repos pin their Node version with
         * a {@code .nvmrc}, and under nvm the executable sits somewhere an interactive shell's PATH
         * knows about but a launcher's or a cron job's does not.
         */
        Path nodeHome,
        List<String> excludes,
        Map<String, String> artifactOverrides,
        List<ManualEdge> manualEdges,
        /**
         * Human-declared runtime composition: which repo builds a bundle a host loads at run
         * time. Not derivable — see {@link RuntimeEdge}.
         */
        List<RuntimeEdge> runtimeEdges,
        RunSettings run,
        Map<String, List<String>> verificationExclusions) {}
