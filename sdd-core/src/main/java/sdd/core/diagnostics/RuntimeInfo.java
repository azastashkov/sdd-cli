package sdd.core.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort environment facts for {@link DiagnosticHeader} — "best-effort" because neither is
 * guaranteed to be available in every build/runtime shape this CLI ships as (a plain classpath run
 * from Gradle, a distribution jar with a manifest, a future packaging this repo does not have yet),
 * and B3 itself only asks for "sdd version / git commit IF AVAILABLE". Deliberately thin and
 * untested beyond what compiles: every branch here is a direct JDK/OS query with a fixed "unknown"
 * fallback, the same shape {@code DoctorCommand} already uses raw ({@code Runtime.version().feature()})
 * without a wrapper — there is nothing here worth mocking a clock or a filesystem for.
 */
final class RuntimeInfo {
    private RuntimeInfo() {
    }

    /** The {@code Implementation-Version} from this jar's manifest, when the CLI is run from a
     *  built distribution that sets one — {@code "unknown"} otherwise (the common case running
     *  straight off Gradle's test/run classpath, which has no manifest at all). */
    static String sddVersion() {
        String v = RuntimeInfo.class.getPackage().getImplementationVersion();
        return v != null ? v : "unknown";
    }

    /** {@code git rev-parse --short=12 HEAD} run against the current working directory — which is
     *  the WORKSPACE {@code sdd} was invoked against, not necessarily the checkout {@code sdd}
     *  itself was built from (Gate review minor). {@link DiagnosticHeader#render} labels this
     *  "workspace git commit:" for exactly that reason — do not rename it back to a bare "git
     *  commit:" without also fixing the meaning, or a remote reader will misread it as sdd's own
     *  build sha every time. A short timeout so a missing/broken {@code git} on a closed network
     *  never delays a command by more than a fraction of a second — this is a "nice to have"
     *  diagnostic fact, never something worth blocking on. */
    static String gitCommit() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--short=12", "HEAD")
                    .redirectErrorStream(true).start();
            if (!p.waitFor(300, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return "unknown";
            }
            if (p.exitValue() != 0) {
                return "unknown";
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return out.isBlank() ? "unknown" : out;
        } catch (IOException e) {
            return "unknown";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }
}
