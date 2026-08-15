package sdd.cli.toolchain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.agent.tool.GradleTool;
import sdd.cli.implement.JarBuilder;
import sdd.cli.implement.MavenLocalPublisher;
import sdd.cli.review.EstateRebuild;
import sdd.plan.approve.GradleSmokeRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization test pinning the EXACT subprocess shape of every site that shells out to the
 * Gradle wrapper: the argument vector, the working directory, and the environment policy.
 *
 * <p>This exists to make the {@code BuildRunner} extraction provably behavior-preserving. It is
 * written against the pre-extraction code and MUST pass unchanged afterwards. If a refactor has to
 * edit an expectation here, that refactor changed observable behavior — Gate-1 mechanism decisions,
 * agent tool output, or verification outcomes — and the change must be justified, not absorbed.
 *
 * <p>Two asymmetries are deliberately pinned rather than smoothed over, because both are real and
 * both would be tempting to "clean up":
 * <ul>
 *   <li>{@link GradleSmokeRunner} is the ONLY site that <em>inherits</em> the environment. The other
 *       four clear it and repopulate from {@code PATH/HOME/LANG/TMPDIR} plus {@code JAVA_HOME}.
 *       Unifying them would change which propagation mechanism Gate 1 picks for every edge in the
 *       estate.</li>
 *   <li>{@link GradleSmokeRunner} is also the only site that omits {@code --no-daemon}.</li>
 * </ul>
 *
 * <p>Argument ORDER is load-bearing everywhere: {@code <task>}, then caller-supplied extra args,
 * then the fixed guardrail flags. The extra-args slot sits between them so orchestrator-injected
 * substitution flags reach Gradle after the task but before the guardrails.
 */
class CommandShapeTest {

    /** Env names the four scrubbing sites are allowed to pass through, plus the one they set. */
    private static final Set<String> SCRUB_ALLOWED = Set.of("PATH", "HOME", "LANG", "TMPDIR", "JAVA_HOME");

    @TempDir Path tmp;

    /**
     * A repo whose {@code gradlew} records, in the working directory it was invoked from, one line
     * per received argument and a full dump of the environment it was given. Writing to relative
     * paths is itself the working-directory assertion: the files can only land in the directory the
     * subprocess actually ran in.
     */
    private Path repoWithRecordingWrapper(String name) throws Exception {
        Path repo = Files.createDirectories(tmp.resolve(name));
        Path gradlew = repo.resolve("gradlew");
        Files.writeString(gradlew, """
                #!/bin/sh
                : > args.out
                for a in "$@"; do printf '%s\\n' "$a" >> args.out; done
                env > env.out
                exit 0
                """);
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        return repo;
    }

    private List<String> recordedArgs(Path repo) throws Exception {
        Path args = repo.resolve("args.out");
        assertThat(args).as("wrapper must have run with its working directory set to %s", repo).exists();
        return Files.readAllLines(args);
    }

    private Set<String> recordedEnvKeys(Path repo) throws Exception {
        return Files.readAllLines(repo.resolve("env.out")).stream()
                .filter(line -> line.contains("="))
                .map(line -> line.substring(0, line.indexOf('=')))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * An environment name this JVM actually has that the scrubbing sites must drop. Chosen at
     * runtime rather than hardcoded, because the test JVM's environment differs between a developer
     * shell and a Gradle worker; returns null when the ambient environment is already so bare that
     * scrubbing is unobservable, in which case the pass/drop assertions are skipped rather than
     * asserted vacuously.
     */
    private static String scrubMarker() {
        return System.getenv().keySet().stream()
                .filter(k -> !SCRUB_ALLOWED.contains(k))
                .filter(k -> k.matches("[A-Za-z_][A-Za-z0-9_]*"))   // must survive `env` round-tripping
                .sorted()
                .findFirst()
                .orElse(null);
    }

    // ---------------------------------------------------------------- the agent's build tool

    @Test
    void gradleToolCommandShape() throws Exception {
        Path repo = repoWithRecordingWrapper("agent-repo");
        Path javaHome = Files.createDirectories(tmp.resolve("jdk21"));

        new GradleTool(repo, javaHome, Duration.ofSeconds(30), List.of("--include-build", "/w/lib"))
                .run("check");

        assertThat(recordedArgs(repo)).containsExactly(
                "check",
                "--include-build", "/w/lib",
                "--no-configuration-cache",
                "--no-daemon",
                "-q");
        assertThat(recordedEnvKeys(repo)).contains("JAVA_HOME");
    }

    @Test
    void gradleToolScrubsTheEnvironment() throws Exception {
        String marker = scrubMarker();
        org.junit.jupiter.api.Assumptions.assumeTrue(marker != null,
                "ambient environment has nothing to scrub, so scrubbing is unobservable here");
        Path repo = repoWithRecordingWrapper("agent-scrub");

        new GradleTool(repo, null, Duration.ofSeconds(30)).run("check");

        assertThat(recordedEnvKeys(repo))
                .as("scrubbing sites must drop ambient variables such as %s", marker)
                .doesNotContain(marker);
    }

    @Test
    void gradleToolOmitsJavaHomeWhenNull() throws Exception {
        Path repo = repoWithRecordingWrapper("agent-nojdk");

        new GradleTool(repo, null, Duration.ofSeconds(30)).run("check");

        assertThat(recordedEnvKeys(repo)).doesNotContain("JAVA_HOME");
    }

    // ---------------------------------------------------------------- japicmp jar production

    @Test
    void jarBuilderCommandShape() throws Exception {
        Path repo = repoWithRecordingWrapper("jar-repo");
        Path javaHome = Files.createDirectories(tmp.resolve("jdk17"));
        Path outDir = tmp.resolve("out");

        new JarBuilder(Duration.ofSeconds(30)).build(repo, javaHome, outDir, List.of("--offline"));

        assertThat(recordedArgs(repo)).containsExactly(
                "assemble",
                "--offline",
                "--no-configuration-cache",
                "--no-daemon",
                "-q");
        assertThat(recordedEnvKeys(repo)).contains("JAVA_HOME");
    }

    // ---------------------------------------------------------------- maven-local propagation

    @Test
    void mavenLocalPublisherCommandShape() throws Exception {
        Path repo = repoWithRecordingWrapper("publish-repo");
        Path javaHome = Files.createDirectories(tmp.resolve("jdk11"));
        Path m2 = tmp.resolve("run").resolve("m2");

        new MavenLocalPublisher(Duration.ofSeconds(30)).publish(repo, javaHome, "1.2.3-SNAPSHOT", m2);

        assertThat(recordedArgs(repo)).containsExactly(
                "publishToMavenLocal",
                "-Pversion=1.2.3-SNAPSHOT",
                "-Dmaven.repo.local=" + m2.toAbsolutePath(),
                "--no-configuration-cache",
                "--no-daemon",
                "-q");
    }

    // ---------------------------------------------------------------- Gate-2 rebuild

    @Test
    void estateRebuildCommandShape() throws Exception {
        Path repo = repoWithRecordingWrapper("rebuild-repo");
        Path javaHome = Files.createDirectories(tmp.resolve("jdk21b"));

        new EstateRebuild(Duration.ofSeconds(30))
                .verify(repo, javaHome, List.of("check"), List.of("--include-build", "/w/dep"));

        assertThat(recordedArgs(repo)).containsExactly(
                "check",
                "--include-build", "/w/dep",
                "--no-configuration-cache",
                "--no-daemon",
                "-q");
        assertThat(recordedEnvKeys(repo)).contains("JAVA_HOME");
    }

    // ---------------------------------------------------------------- Gate-1 mechanism probe

    @Test
    void smokeRunnerCommandShapeOmitsNoDaemon() throws Exception {
        Path consumer = repoWithRecordingWrapper("smoke-consumer");
        Path provider = Files.createDirectories(tmp.resolve("smoke-provider"));

        new GradleSmokeRunner(Duration.ofSeconds(30)).probe(consumer, provider);

        assertThat(recordedArgs(consumer))
                .as("the Gate-1 probe deliberately omits --no-daemon; adding it changes probe outcomes")
                .containsExactly(
                        "help",
                        "--include-build", provider.toAbsolutePath().toString(),
                        "--no-configuration-cache",
                        "-q");
    }

    @Test
    void smokeRunnerInheritsTheEnvironment() throws Exception {
        String marker = scrubMarker();
        org.junit.jupiter.api.Assumptions.assumeTrue(marker != null,
                "ambient environment has nothing to inherit, so inheritance is unobservable here");
        Path consumer = repoWithRecordingWrapper("smoke-env");
        Path provider = Files.createDirectories(tmp.resolve("smoke-env-provider"));

        new GradleSmokeRunner(Duration.ofSeconds(30)).probe(consumer, provider);

        assertThat(recordedEnvKeys(consumer))
                .as("GradleSmokeRunner is the ONE site that inherits the environment — see class javadoc")
                .contains(marker);
    }

    // ---------------------------------------------------------------- shared refusal

    @Test
    void everySiteRefusesARepoWithNoExecutableWrapper() throws Exception {
        Path bare = Files.createDirectories(tmp.resolve("no-wrapper"));

        assertThat(new JarBuilder(Duration.ofSeconds(5)).build(bare, null, tmp.resolve("o"), List.of()).log())
                .isEqualTo("no gradle wrapper in " + bare);
        assertThat(new MavenLocalPublisher(Duration.ofSeconds(5)).publish(bare, null, "1", tmp.resolve("m2")).log())
                .isEqualTo("no gradle wrapper in " + bare);
        assertThat(new GradleSmokeRunner(Duration.ofSeconds(5)).probe(bare, tmp).detail())
                .isEqualTo("no gradle wrapper in " + bare);
    }
}
