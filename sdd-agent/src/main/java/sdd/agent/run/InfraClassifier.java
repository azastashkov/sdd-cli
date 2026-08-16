package sdd.agent.run;

import sdd.core.toolchain.Toolchain;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Classifies a RAW (pre-compaction) build log as an infrastructure failure — dependency
 * resolution, network, daemon, Docker, disk, or the subprocess timeout — per design line 63. Deliberately matched on the raw log: the compacted output may have dropped the
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

    /**
     * npm failures that are genuinely about the machine or the network.
     *
     * <p>The same precision rule the Gradle list follows applies here, and it excludes more than it
     * includes. {@code npm ERR! 404}, {@code ERESOLVE} and {@code ETARGET} all mean the registry
     * ANSWERED — the package or version simply is not there, usually because the agent wrote a name
     * or a range that does not exist. {@code Cannot find module} is an import the agent got wrong.
     * Every one of those is a build error the verify-fail cycle should feed back so it can be
     * fixed, not an infra pause that stops the run and waits for a human who has nothing to do.
     */
    private static final List<String> NPM_PATTERNS = List.of(
            // network
            "enotfound", "eai_again", "econnrefused", "econnreset", "etimedout",
            "esockettimedout", "network timeout", "socket hang up",
            // credentials the registry rejected, and machine limits
            "npm err! code e401", "npm err! code e403",
            "eacces", "eperm", "enospc",
            // node itself ran out of room to work in
            "javascript heap out of memory", "fatal error: reached heap limit");

    private InfraClassifier() {
    }

    /** Gradle logs; kept so existing callers read unchanged. */
    public static boolean isInfra(String rawLog) {
        return isInfra(rawLog, Toolchain.GRADLE);
    }

    public static boolean isInfra(String rawLog, Toolchain toolchain) {
        if (rawLog == null || rawLog.isEmpty()) {
            return false;
        }
        if (rawLog.startsWith("timed out after")) {   // the build tools' shared timeout marker
            return true;
        }
        String lower = rawLog.toLowerCase(Locale.ROOT);
        if (toolchain == Toolchain.NPM) {
            return NPM_PATTERNS.stream().anyMatch(lower::contains);
        }
        boolean standalone = STANDALONE_PATTERNS.stream().anyMatch(lower::contains);
        boolean resolutionWithNetworkCause = RESOLUTION_PATTERNS.stream().anyMatch(lower::contains)
                && NETWORK_PATTERNS.stream().anyMatch(lower::contains);
        return standalone || resolutionWithNetworkCause;
    }
}
