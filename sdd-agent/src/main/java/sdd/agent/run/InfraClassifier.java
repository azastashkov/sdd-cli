package sdd.agent.run;

import java.util.List;
import java.util.Locale;

/**
 * Classifies a RAW (pre-compaction) Gradle log as an infrastructure failure — dependency
 * resolution, network, daemon, Docker, disk, or the GradleTool subprocess timeout — per design
 * line 63. Deliberately matched on the raw log: the compacted output may have dropped the
 * telltale line. Patterns are matched case-insensitively anywhere in the log.
 */
public final class InfraClassifier {
    private static final List<String> PATTERNS = List.of(
            // dependency resolution / repository access
            "could not resolve", "could not download", "could not get 'http", "could not head",
            // network
            "unknownhostexception", "connection refused", "connection reset",
            "connect timed out", "read timed out", "no route to host",
            // gradle daemon
            "gradle build daemon disappeared", "unable to start the daemon process",
            "timeout waiting to lock",
            // docker / disk
            "cannot connect to the docker daemon", "no space left on device");

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
        for (String pattern : PATTERNS) {
            if (lower.contains(pattern)) {
                return true;
            }
        }
        return false;
    }
}
