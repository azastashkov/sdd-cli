package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JarBuilderTest {
    @TempDir Path ws;

    private Path repoWith(String script) throws Exception {
        Path repo = Files.createDirectories(ws.resolve("lib"));
        Path gradlew = repo.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        return repo;
    }

    @Test
    void buildsAndCollectsJarsExcludingSourcesAndJavadoc() throws Exception {
        Path repo = repoWith("mkdir -p build/libs; touch build/libs/lib-1.0.jar "
                + "build/libs/lib-1.0-sources.jar build/libs/lib-1.0-javadoc.jar; echo \"$*\" > args; exit 0");
        Path out = ws.resolve("baseline");

        JarBuilder.Result result = new JarBuilder().build(repo, null, out, List.of());

        assertThat(result.ok()).isTrue();
        assertThat(result.jars()).containsExactly(out.resolve("lib-1.0.jar"));
        assertThat(Files.readString(repo.resolve("args"))).contains("assemble");
    }

    @Test
    void aFailedAssembleReportsNotOk() throws Exception {
        Path repo = repoWith("echo 'boom'; exit 1");

        JarBuilder.Result result = new JarBuilder().build(repo, null, ws.resolve("out"), List.of());

        assertThat(result.ok()).isFalse();
        assertThat(result.log()).startsWith("exit 1");
    }

    @Test
    void extraArgsLandBetweenAssembleAndTheFixedFlags() throws Exception {
        Path repo = repoWith("mkdir -p build/libs; touch build/libs/lib-1.0.jar; echo \"$*\" > args; exit 0");
        Path out = ws.resolve("out");

        JarBuilder.Result result = new JarBuilder().build(repo, null, out,
                List.of("--include-build", "../other", "--init-script", "/tmp/init.gradle"));

        assertThat(result.ok()).isTrue();
        assertThat(Files.readString(repo.resolve("args")))
                .contains("assemble --include-build ../other --init-script /tmp/init.gradle "
                        + "--no-configuration-cache --no-daemon -q");
    }

    @Test
    void collidingModuleJarsAreDisambiguatedByModuleDirName() throws Exception {
        Path repo = repoWith("mkdir -p mod-a/build/libs mod-b/build/libs; "
                + "printf 'from-a' > mod-a/build/libs/foo-1.0.jar; "
                + "printf 'from-b' > mod-b/build/libs/foo-1.0.jar; exit 0");
        Path out = ws.resolve("out");

        JarBuilder.Result result = new JarBuilder().build(repo, null, out, List.of());

        assertThat(result.ok()).isTrue();
        assertThat(result.jars()).containsExactlyInAnyOrder(
                out.resolve("foo-1.0.jar"), out.resolve("mod-b-foo-1.0.jar"));
        assertThat(Files.readString(out.resolve("foo-1.0.jar"))).isEqualTo("from-a");
        assertThat(Files.readString(out.resolve("mod-b-foo-1.0.jar"))).isEqualTo("from-b");
    }

    @Test
    void jarCollectionFailureAfterSuccessfulBuildDoesNotMaskBuildLog() throws Exception {
        Path repo = repoWith("mkdir -p build/libs; touch build/libs/lib-1.0.jar; echo done; exit 0");
        Path out = ws.resolve("blocked-out");
        Files.writeString(out, "not a directory");

        JarBuilder.Result result = new JarBuilder().build(repo, null, out, List.of());

        assertThat(result.ok()).isFalse();
        assertThat(result.log()).contains("jar collection failed after exit 0");
    }

    @Test
    void missingWrapperFailsWithoutRunningAnything() {
        JarBuilder.Result result = new JarBuilder().build(ws.resolve("nowhere"), null, ws.resolve("out"),
                List.of());

        assertThat(result.ok()).isFalse();
        assertThat(result.log()).contains("no gradle wrapper");
    }
}
