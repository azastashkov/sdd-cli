package sdd.agent.run;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Classifies a RAW (pre-compaction) Gradle log as an infrastructure failure — dependency
 * resolution, network, daemon, Docker, disk, or the GradleTool subprocess timeout — per design
 * line 63. Deliberately matched on the raw log: the compacted output may have dropped the
 * telltale line. Patterns are matched case-insensitively anywhere in the log.
 *
 * <p><b>Precision rule for dependency-resolution failures:</b> a live-smoke run misclassified an
 * agent-induced error as infra — the agent had bumped a dependency to a nonexistent version, and
 * Gradle's {@code Could not resolve ... > Could not find <artifact>} response means the
 * repository ANSWERED and the artifact is simply absent. That is a build/config error the
 * verify-fail cycle should feed back to the agent, not an infra pause. So the RESOLUTION
 * patterns below ({@code could not resolve}, {@code could not download}, {@code could not get
 * 'http}, {@code could not head}) are bare/ambiguous on their own and count as infra ONLY when
 * the log ALSO contains a NETWORK pattern establishing a genuine connectivity cause. The NETWORK
 * family, plus the daemon, Docker, disk, and process-timeout markers, remain standalone triggers.
 */
public final class InfraClassifier {
    private static final List<String> NETWORK_PATTERNS = List.of(
            "unknownhostexception", "connection refused", "connection reset",
            "connect timed out", "read timed out", "no route to host", "received status code 5");

    private static final List<String> RESOLUTION_PATTERNS = List.of(
            "could not resolve", "could not download", "could not get 'http", "could not head");

    private static final List<String> STANDALONE_PATTERNS = Stream.concat(
            NETWORK_PATTERNS.stream(),
            Stream.of(
                    // gradle daemon
                    "gradle build daemon disappeared", "unable to start the daemon process",
                    "timeout waiting to lock",
                    // docker / disk
                    "cannot connect to the docker daemon", "no space left on device"))
            .toList();

    private InfraClassifier() {
    }

    public static boolean isInfra(String rawGradleLog) {
        if (rawGradleLog == null || rawGradleLog.isEmpty()) {
            return false;
        }
        if (rawGradleLog.startsWith("timed out after")) {   // GradleTool's process-timeout marker
            return true;
        }
        String lower = rawGradleLog.toLowerCase(Locale.ROOT);
        boolean standalone = STANDALONE_PATTERNS.stream().anyMatch(lower::contains);
        boolean resolutionWithNetworkCause = RESOLUTION_PATTERNS.stream().anyMatch(lower::contains)
                && NETWORK_PATTERNS.stream().anyMatch(lower::contains);
        return standalone || resolutionWithNetworkCause;
    }
}
