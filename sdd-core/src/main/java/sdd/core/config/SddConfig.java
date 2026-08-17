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
        /**
         * Where a fallback Gradle installation lives ({@code <gradleHome>/bin/gradle}), or null to
         * take it from {@code $SDD_GRADLE} then PATH. Only consulted for a repo that has no
         * executable {@code gradlew}: a wrapper pins the Gradle version its repo was written for,
         * and this estate spans several, so a wrapper always wins. See {@code GradleLauncher}.
         */
        Path gradleHome,
        List<String> excludes,
        Map<String, String> artifactOverrides,
        List<ManualEdge> manualEdges,
        /**
         * Human-declared runtime composition: which repo builds a bundle a host loads at run
         * time. Not derivable — see {@link RuntimeEdge}.
         */
        List<RuntimeEdge> runtimeEdges,
        RunSettings run,
        Map<String, List<String>> verificationExclusions,
        /**
         * The optional {@code atlassian:} block (Jira/Confluence ingestion, Bitbucket source
         * control) — null when {@code sdd.yml} has no {@code atlassian:} key. See
         * {@link AtlassianConfig}'s javadoc for why it is opt-in rather than defaulted.
         */
        AtlassianConfig atlassian) {

    /** Pre-{@code atlassian} 10-argument shape, kept so every existing construction site (main and
     *  test) keeps compiling untouched: {@code atlassian} defaults to null, i.e. no
     *  {@code atlassian:} block configured. Mirrors {@link ModelEndpoint}'s own 7-argument legacy
     *  constructor, added for the same reason when {@code apiKeyError} was introduced. */
    public SddConfig(Path workspace, Map<String, ModelEndpoint> models, Map<Integer, Path> jdkHomes,
            Path nodeHome, List<String> excludes, Map<String, String> artifactOverrides,
            List<ManualEdge> manualEdges, List<RuntimeEdge> runtimeEdges, RunSettings run,
            Map<String, List<String>> verificationExclusions) {
        this(workspace, models, jdkHomes, nodeHome, excludes, artifactOverrides, manualEdges,
                runtimeEdges, run, verificationExclusions, null);
    }

    /** Pre-{@code gradle_home} shape, kept so existing construction sites compile untouched. */
    public SddConfig(Path workspace, Map<String, ModelEndpoint> models, Map<Integer, Path> jdkHomes,
            Path nodeHome, List<String> excludes, Map<String, String> artifactOverrides,
            List<ManualEdge> manualEdges, List<RuntimeEdge> runtimeEdges, RunSettings run,
            Map<String, List<String>> verificationExclusions, AtlassianConfig atlassian) {
        this(workspace, models, jdkHomes, nodeHome, null, excludes, artifactOverrides, manualEdges,
                runtimeEdges, run, verificationExclusions, atlassian);
    }
}
