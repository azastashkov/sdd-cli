package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JapicmpCheckTest {
    @TempDir Path dir;

    @Test
    void aChangedReturnTypeIsBinaryIncompatible() throws Exception {
        Path baseline = TestJars.jar(dir, "lib-1.0.jar", "Api",
                "public class Api { public int f(int x) { return x; } }");
        Path candidate = TestJars.jar(dir, "lib-1.1.jar", "Api",
                "public class Api { public long f(int x) { return x; } }");

        JapicmpCheck.Verdict verdict = JapicmpCheck.compare(baseline, candidate);

        assertThat(verdict.binaryCompatible()).isFalse();
        assertThat(verdict.report()).contains("Api");
    }

    @Test
    void anAddedMethodIsBinaryCompatible() throws Exception {
        Path baseline = TestJars.jar(dir, "lib-1.0.jar", "Api",
                "public class Api { public int f(int x) { return x; } }");
        Path candidate = TestJars.jar(dir, "lib-1.1.jar", "Api",
                "public class Api { public int f(int x) { return x; } public int g() { return 1; } }");

        JapicmpCheck.Verdict verdict = JapicmpCheck.compare(baseline, candidate);

        assertThat(verdict.binaryCompatible()).isTrue();
    }

    @Test
    void aClassReferencingAMissingThirdPartyTypeDoesNotThrow() throws Exception {
        // Reproduces the live real-estate-run failure: real jars have a class whose *superclass*
        // (e.g. groovy.lang.Closure) lives in neither the baseline nor the candidate archive.
        Map<String, String> sources = Map.of(
                "Api", "public class Api extends Helper { public int f(int x) { return x; } }",
                "Helper", "public class Helper { }");
        Path baseline = TestJars.jarOmitting(dir, "lib-1.0.jar", sources, "Helper");
        Path candidate = TestJars.jarOmitting(dir, "lib-1.1.jar", sources, "Helper");

        JapicmpCheck.Verdict verdict = JapicmpCheck.compare(baseline, candidate);

        assertThat(verdict.binaryCompatible()).isTrue();
    }
}
