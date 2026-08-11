package sdd.core.testing;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FixtureRepo {
    private static final PersonIdent AUTHOR = new PersonIdent("sdd-fixture", "fixture@sdd.local");

    private final Path root;

    private FixtureRepo(Path root) { this.root = root; }

    public static FixtureRepo in(Path parentDir, String name) {
        Path root = parentDir.resolve(name);
        try {
            Files.createDirectories(root);
            Git.init().setDirectory(root.toFile()).setInitialBranch("main").call().close();
        } catch (Exception e) {
            throw new IllegalStateException("cannot init fixture repo " + root, e);
        }
        return new FixtureRepo(root);
    }

    public FixtureRepo file(String relPath, String content) {
        try {
            Path target = root.resolve(relPath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    public FixtureRepo commit(String message) {
        try (Git git = Git.open(root.toFile())) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage(message).setAuthor(AUTHOR).setCommitter(AUTHOR).call();
        } catch (Exception e) {
            throw new IllegalStateException("cannot commit in " + root, e);
        }
        return this;
    }

    public Path path() { return root; }

    public String headSha() {
        try (Git git = Git.open(root.toFile())) {
            return git.getRepository().resolve("HEAD").name();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
