package sdd.core.ts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Finds the {@code node} executable, or explains why it could not.
 *
 * <p>Resolution order is explicit-before-ambient so a machine with several Node installations gives
 * the same answer to sdd as it does to the humans working on the estate: {@code node_home} from
 * {@code sdd.yml}, then {@code SDD_NODE}, then {@code PATH}. The {@code node_home} lever exists for
 * the same reason {@code jdk_homes} does — these repos pin their Node version with {@code .nvmrc},
 * and under nvm the executable lives somewhere that is on an interactive shell's PATH but not
 * necessarily on the PATH a launcher or a cron job inherits.
 */
public final class NodeLocator {

    /** Node 18 is the first release with a stable global {@code fetch}, and every LTS since. */
    public static final int MINIMUM_MAJOR = 18;

    private NodeLocator() {
    }

    /**
     * @param nodeHome the configured {@code node_home}, or null. When set, only
     *                 {@code <nodeHome>/bin/node} is considered — a configured Node that is missing
     *                 is an error worth reporting, not a reason to quietly use a different one.
     */
    public static Optional<Path> find(Path nodeHome) {
        if (nodeHome != null) {
            Path candidate = nodeHome.resolve("bin").resolve("node");
            return Files.isExecutable(candidate) ? Optional.of(candidate) : Optional.empty();
        }
        String fromEnv = System.getenv("SDD_NODE");
        if (fromEnv != null && !fromEnv.isBlank()) {
            Path candidate = Path.of(fromEnv);
            return Files.isExecutable(candidate) ? Optional.of(candidate) : Optional.empty();
        }
        String pathVar = System.getenv("PATH");
        if (pathVar == null) {
            return Optional.empty();
        }
        for (String dir : pathVar.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir).resolve("node");
            if (Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * The {@code npm} to invoke: an absolute path under {@code node_home} when one is configured
     * and present, otherwise the bare name for a PATH lookup.
     *
     * <p>Resolving this rather than relying on the child's PATH is necessary, not tidiness.
     * {@link ProcessBuilder} looks a command up on the PARENT process's PATH and ignores the PATH
     * in the environment it is handed, so a configured {@code node_home} would otherwise be
     * silently disregarded when choosing which npm runs — the machine's default would win, with
     * nothing to say so.
     */
    public static String npmExecutable(Path nodeHome) {
        if (nodeHome != null) {
            Path candidate = nodeHome.toAbsolutePath().resolve("bin").resolve("npm");
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return "npm";
    }

    /** Where a reader should look when {@link #find} comes back empty. */
    public static String notFoundHint(Path nodeHome) {
        if (nodeHome != null) {
            return "no executable node at " + nodeHome.resolve("bin").resolve("node")
                    + " (from node_home in sdd.yml)";
        }
        return "node not found on PATH — set node_home in sdd.yml or export SDD_NODE";
    }

    /** Parses {@code v22.1.0} into 22. Empty when the output is not a version string. */
    public static Optional<Integer> majorOf(String versionOutput) {
        if (versionOutput == null) {
            return Optional.empty();
        }
        var matcher = java.util.regex.Pattern.compile("v?(\\d+)\\.").matcher(versionOutput.trim());
        return matcher.lookingAt()
                ? Optional.of(Integer.parseInt(matcher.group(1)))
                : Optional.empty();
    }

    /** Kept next to the resolution order it documents, so the two cannot drift apart. */
    public static List<String> searchOrderDescription() {
        return List.of("node_home in sdd.yml", "$SDD_NODE", "node on PATH");
    }
}
