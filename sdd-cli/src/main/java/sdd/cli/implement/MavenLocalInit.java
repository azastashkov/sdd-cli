package sdd.cli.implement;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The run-scoped init script that injects {@code <runDir>/m2} as a maven repository into every
 * project of every build in a Gradle invocation. An init script is the one channel that reaches
 * included builds inside a composite too, which per-project injection cannot.
 */
public final class MavenLocalInit {
    static final String FILE_NAME = "maven-local-init.gradle";

    private MavenLocalInit() {
    }

    public static Path scriptPath(Path runDir) {
        return runDir.resolve(FILE_NAME);
    }

    public static Path write(Path runDir) {
        String script = """
                // sdd: run-scoped mavenLocal injection (design line 61). Appended repository, so it
                // only serves artifacts other repositories cannot — the planned versions published
                // into this run's m2.
                allprojects {
                    repositories {
                        maven { url = uri('%s') }
                    }
                }
                """.formatted(runDir.resolve("m2").toAbsolutePath());
        try {
            Files.writeString(scriptPath(runDir), script);
            return scriptPath(runDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
