package sdd.plan.openspec;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hands the golden export to the real OpenSpec CLI.
 *
 * <p>{@code OpenSpecChangeTest} proves conformance to rules <em>we transcribed</em>; only this
 * proves conformance to the validator. It is the difference between "we believe every ADDED
 * requirement needs a scenario" and "the tool that will read these files accepts them".
 *
 * <p>Gated on {@code SDD_OPENSPEC_VALIDATE}, never in the default test task: it needs Node and a
 * network fetch, and sdd itself must never require either. Re-run it whenever
 * {@link OpenSpecChange#TARGET_VERSION} changes — a format the tool has moved on from is exactly
 * the failure the transcription cannot catch.
 *
 * <pre>
 * SDD_OPENSPEC_VALIDATE=1 ./gradlew :sdd-plan:test --tests '*OpenSpecValidateHarness' --rerun-tasks
 * </pre>
 */
@Tag("measure")
@EnabledIfEnvironmentVariable(named = "SDD_OPENSPEC_VALIDATE", matches = ".+")
class OpenSpecValidateHarness {

    private static final Path GOLDEN = Path.of("src/test/resources/golden/openspec");
    private static final String PACKAGE = "@fission-ai/openspec@" + OpenSpecChange.TARGET_VERSION;

    @TempDir Path work;

    private record Run(int exitCode, String output) {
    }

    private Run npx(Path cwd, String... args) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>(List.of("npx", "--yes", PACKAGE));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(cwd.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            Assumptions.abort("npx did not finish within five minutes: " + output);
        }
        return new Run(process.exitValue(), output);
    }

    /** Copies one golden repo's tree into a scratch directory the CLI can be pointed at. */
    private Path stage(String repo) throws IOException {
        Path source = GOLDEN.resolve(repo);
        Path target = work.resolve(repo);
        try (var walk = Files.walk(source)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                Files.createDirectories(destination.getParent());
                Files.copy(path, destination);
            }
        }
        return target;
    }

    @Test
    void theGoldenExportIsAcceptedByTheRealValidator() throws Exception {
        for (String repo : List.of("pricing-core", "svc-orders")) {
            Path staged = stage(repo);

            Run run = npx(staged, "validate", "--changes", "--strict");

            assertThat(run.exitCode())
                    .as("openspec validate --strict on %s:%n%s", repo, run.output())
                    .isZero();
        }
    }

    @Test
    void aScenariolessRequirementIsRejected() throws Exception {
        // The negative control. Without it this harness proves only that npx ran: a validator that
        // silently accepted everything would look identical to a passing suite.
        Path staged = stage("pricing-core");
        Path delta = staged.resolve("openspec/changes/spec-tier-invalidation-v1/specs/"
                + "tier-resolution/spec.md");
        String broken = delta.toString();
        Files.writeString(delta, """
                # tier-resolution

                ## Purpose
                A purpose long enough to clear the brevity warning that OpenSpec emits under fifty.

                ## ADDED Requirements

                ### Requirement: Something with no scenario at all
                The system SHALL do a thing, and this requirement deliberately has no scenario.
                """, StandardCharsets.UTF_8);

        Run run = npx(staged, "validate", "--changes", "--strict");

        // Asserting on the reported failure, not merely on a non-zero exit: a bad invocation also
        // exits non-zero, and an earlier version of this test "passed" that way while proving
        // nothing at all.
        assertThat(run.exitCode())
                .as("a requirement with no scenario must be rejected (%s):%n%s", broken, run.output())
                .isNotZero();
        assertThat(run.output())
                .as("must fail on the CHANGE, not on how the CLI was invoked")
                .contains("0 passed, 1 failed");
    }

    /**
     * The ROOT change — the estate-wide one written into the workspace, not into a repository.
     *
     * <p>Rendered here rather than staged from a golden, because the root renderer is new and the
     * question is whether what it produces TODAY validates. A golden would only prove the bytes
     * still match bytes a previous run of the same code produced.
     *
     * <p>Also writes openspec/config.yaml, because a workspace is a real OpenSpec project and the
     * CLI expects one — unlike a foreign repository, where sdd deliberately never creates it.
     */
    @Test
    void theEstateChangeAtTheWorkspaceRootIsAcceptedByTheRealValidator() throws Exception {
        Path root = work.resolve("workspace");
        Files.createDirectories(root.resolve("openspec"));
        Files.writeString(root.resolve("openspec/config.yaml"), "schema: spec-driven\n");
        for (var file : EstateChangeFixture.rendered().entrySet()) {
            Path target = root.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }

        Run run = npx(root, "validate", "--changes", "--strict");

        assertThat(run.exitCode())
                .as("openspec validate --changes --strict on the workspace root:%n%s", run.output())
                .isZero();
    }
}
