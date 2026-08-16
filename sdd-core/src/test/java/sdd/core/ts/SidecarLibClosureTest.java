package sdd.core.ts;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lib files the compat gate materializes, recomputed from the files themselves.
 *
 * <p>A hand-maintained list of 45 filenames rots the moment the pinned TypeScript version moves,
 * and it rots INVISIBLY: a missing lib does not fail to install, it makes the type-compatibility
 * probe report breaks in code that is perfectly compatible — and {@code Orchestrator} turns compat
 * drift into a FAILED repo. So the closure is derived here instead of trusted.
 */
class SidecarLibClosureTest {

    /** What the compat probe asks for; everything else is reached from these two. */
    private static final List<String> ROOTS = List.of("lib.es2020.d.ts", "lib.dom.d.ts");

    private static final Pattern REFERENCE =
            Pattern.compile("/// <reference lib=\"([^\"]+)\" />");

    private static String read(String lib) {
        try (InputStream in = TsSidecar.class.getResourceAsStream(TsSidecar.TS_LIB_DIR + lib)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void everyDeclaredLibIsActuallyInThePinnedCompilerJar() {
        List<String> missing = new ArrayList<>();
        for (String lib : TsSidecar.TS_LIBS) {
            if (read(lib) == null) {
                missing.add(lib);
            }
        }
        // install() would throw on the first absent one, which reads as "TypeScript support is
        // broken" rather than as "this list names a file the compiler does not ship".
        assertThat(missing).as("declared lib files absent from typescript %s", TsSidecar.TS_VERSION)
                .isEmpty();
    }

    @Test
    void theDeclaredListIsExactlyTheReferenceClosureOfWhatTheProbeAsksFor() {
        Set<String> closure = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>(ROOTS);
        while (!pending.isEmpty()) {
            String lib = pending.pop();
            if (!closure.add(lib)) {
                continue;
            }
            String text = read(lib);
            assertThat(text).as("%s is referenced but not shipped", lib).isNotNull();
            Matcher matcher = REFERENCE.matcher(text);
            while (matcher.find()) {
                pending.push("lib." + matcher.group(1) + ".d.ts");
            }
        }

        // Equality both ways. A missing entry makes the gate report false breaks; a surplus one is
        // a file that does not exist in a later version and fails the install outright.
        assertThat(TsSidecar.TS_LIBS).containsExactlyInAnyOrderElementsOf(closure);
    }
}
