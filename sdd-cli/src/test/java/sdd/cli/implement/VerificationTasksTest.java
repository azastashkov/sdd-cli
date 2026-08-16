package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.toolchain.Toolchain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationTasksTest {
    @TempDir Path repo;

    private void packageJson(String scripts) throws Exception {
        Files.writeString(repo.resolve("package.json"), "{\"name\":\"x\",\"scripts\":{" + scripts + "}}");
    }

    // ------------------------------------------------------------------ Gradle, unchanged

    @Test
    void gradleDefaultsToCheck() {
        assertThat(VerificationTasks.resolve(Toolchain.GRADLE, repo, List.of(), List.of()))
                .containsExactly("check");
    }

    @Test
    void gradleKeepsOnlyRunnableTasksFromThePlan() {
        assertThat(VerificationTasks.resolve(Toolchain.GRADLE, repo,
                List.of("test", "the blotter still reconciles"), List.of()))
                .containsExactly("test");
    }

    @Test
    void aProseOnlyVerificationListStillVerifiesNormally() {
        // The subtle one. Acceptance criteria written for humans leave nothing runnable, and
        // reading that as "run nothing" would silently disable verification for every repo whose
        // plan was written the way plans are meant to be written.
        assertThat(VerificationTasks.resolve(Toolchain.GRADLE, repo,
                List.of("the blotter still reconciles"), List.of()))
                .containsExactly("check");
    }

    @Test
    void exclusionsAreSubtractedLast() {
        assertThat(VerificationTasks.resolve(Toolchain.GRADLE, repo, List.of(), List.of("check")))
                .isEmpty();
    }

    // ------------------------------------------------------------------ npm

    @Test
    void anAppPrefersTypecheckOverItsBundlerBuild() throws Exception {
        packageJson("\"build\":\"vite build\",\"typecheck\":\"tsc --noEmit\",\"test\":\"vitest run\"");

        // vite build strips types with esbuild and never checks them, so it can succeed on code
        // that does not type-check. Where a repo offers a real type gate, that is the gate.
        assertThat(VerificationTasks.resolve(Toolchain.NPM, repo, List.of(), List.of()))
                .containsExactly("typecheck", "test");
    }

    @Test
    void aLibraryWithNoTypecheckScriptFallsBackToItsBuild() throws Exception {
        packageJson("\"build\":\"tsc -p tsconfig.json\",\"test\":\"vitest run\"");

        // For a library the build usually IS tsc, so it is a type gate under another name.
        assertThat(VerificationTasks.resolve(Toolchain.NPM, repo, List.of(), List.of()))
                .containsExactly("build", "test");
    }

    @Test
    void aRepoWithNothingRunnableVerifiesNothingRatherThanInventingAScript() throws Exception {
        packageJson("\"dev\":\"vite\"");

        // Reported as "not locally verified" downstream. Inventing a `check` script the humans do
        // not have would be a lie that fails opaquely at the first npm invocation.
        assertThat(VerificationTasks.resolve(Toolchain.NPM, repo, List.of(), List.of())).isEmpty();
    }

    @Test
    void anNpmPlanListIsNarrowedToScriptsTheRepoActuallyDefines() throws Exception {
        packageJson("\"test\":\"vitest run\"");

        assertThat(VerificationTasks.resolve(Toolchain.NPM, repo,
                List.of("test", "lint", "and the chart still renders"), List.of()))
                .containsExactly("test");
    }


    @Test
    void aDangerousScriptIsNeverReachableEvenIfAPlanNamesIt() throws Exception {
        packageJson("\"release\":\"npm publish\",\"test\":\"vitest run\"");

        assertThat(VerificationTasks.resolve(Toolchain.NPM, repo, List.of("release"), List.of()))
                .containsExactly("test");   // falls back to the default; release is not runnable
    }
}
