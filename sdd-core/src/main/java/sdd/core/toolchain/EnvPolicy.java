package sdd.core.toolchain;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What environment a build subprocess is given. Named as a policy rather than passed as a map
 * because the choice is a security decision, not a configuration detail: an inherited environment
 * carries every credential the operator's shell happens to hold into a process that runs code from
 * the repository being indexed.
 *
 * <p>The two constants below are not interchangeable, and the difference is deliberate:
 * <ul>
 *   <li>{@link #scrubbedJvm} is what the agent tool, the jar build, the maven-local publish and the
 *       Gate-2 rebuild use. The environment is cleared and repopulated from {@link #JVM_KEEP} plus
 *       a per-repo {@code JAVA_HOME}.</li>
 *   <li>{@link #INHERIT} is used by exactly one caller, the Gate-1 propagation probe. Switching it
 *       to a scrubbed policy changes which mechanism Gate 1 records for every cross-repo edge in
 *       the estate, because the probe is a real Gradle build that can start succeeding or failing
 *       on the strength of an inherited variable. Do not "unify" it without measuring that.</li>
 * </ul>
 *
 * <p>Values are read from {@link System#getenv()} at apply time rather than captured at
 * construction, matching the behavior of the four sites this was extracted from.
 */
public record EnvPolicy(boolean inherit, List<String> keep, Map<String, String> set) {

    /** The only ambient variables a scrubbed build subprocess is allowed to see. */
    public static final List<String> JVM_KEEP = List.of("PATH", "HOME", "LANG", "TMPDIR");

    /** Hands the subprocess the parent's environment untouched. One caller — see class javadoc. */
    public static final EnvPolicy INHERIT = new EnvPolicy(true, List.of(), Map.of());

    public EnvPolicy {
        keep = List.copyOf(keep);
        set = Map.copyOf(set);
    }

    /**
     * PATH/HOME/LANG/TMPDIR only, plus {@code JAVA_HOME} when {@code javaHome} is non-null. A null
     * {@code javaHome} leaves JAVA_HOME unset rather than inheriting one, so a repo with no
     * configured JDK cannot silently build against whichever JDK the operator happened to export.
     */
    public static EnvPolicy scrubbedJvm(Path javaHome) {
        Map<String, String> set = new LinkedHashMap<>();
        if (javaHome != null) {
            set.put("JAVA_HOME", javaHome.toAbsolutePath().toString());
        }
        return new EnvPolicy(false, JVM_KEEP, set);
    }

    /** Rewrites {@code env} in place — i.e. a {@link ProcessBuilder#environment()} map. */
    public void applyTo(Map<String, String> env) {
        if (inherit) {
            return;
        }
        Map<String, String> kept = new HashMap<>();
        for (String name : keep) {
            String value = System.getenv(name);
            if (value != null) {
                kept.put(name, value);
            }
        }
        env.clear();
        env.putAll(kept);
        env.putAll(set);
    }
}
