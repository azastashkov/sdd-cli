package sdd.index.testing;

import sdd.core.testing.FixtureRepo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

/** Builds tiny, actually-buildable Gradle git repos for integration tests. */
public final class FixtureGradleRepo {
    private final FixtureRepo repo;
    private final Path root;

    private FixtureGradleRepo(FixtureRepo repo) {
        this.repo = repo;
        this.root = repo.path();
    }

    public static FixtureGradleRepo in(Path parentDir, String name, String gradleVersion) {
        FixtureRepo base = FixtureRepo.in(parentDir, name);
        FixtureGradleRepo g = new FixtureGradleRepo(base);
        Path projectRoot = locateSddProjectRoot();
        try {
            Files.createDirectories(g.root.resolve("gradle/wrapper"));
            Files.copy(projectRoot.resolve("gradlew"), g.root.resolve("gradlew"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.copy(projectRoot.resolve("gradle/wrapper/gradle-wrapper.jar"),
                    g.root.resolve("gradle/wrapper/gradle-wrapper.jar"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.getFileAttributeView(g.root.resolve("gradlew"),
                            java.nio.file.attribute.PosixFileAttributeView.class)
                    .setPermissions(EnumSet.of(
                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                            PosixFilePermission.OTHERS_EXECUTE));
            Files.writeString(g.root.resolve("gradle/wrapper/gradle-wrapper.properties"), """
                    distributionBase=GRADLE_USER_HOME
                    distributionPath=wrapper/dists
                    distributionUrl=https\\://services.gradle.org/distributions/gradle-%s-bin.zip
                    zipStoreBase=GRADLE_USER_HOME
                    zipStorePath=wrapper/dists
                    """.formatted(gradleVersion));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return g;
    }

    private static Path locateSddProjectRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("gradle/wrapper/gradle-wrapper.jar"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("cannot locate sdd project root with wrapper jar");
        }
        return dir;
    }

    public FixtureGradleRepo withSettings(String content) { return withFile("settings.gradle", content); }

    public FixtureGradleRepo withBuildFile(String content) { return withFile("build.gradle", content); }

    public FixtureGradleRepo withFile(String relPath, String content) {
        repo.file(relPath, content);
        return this;
    }

    public Path commit() {
        repo.commit("fixture");
        return root;
    }
}
