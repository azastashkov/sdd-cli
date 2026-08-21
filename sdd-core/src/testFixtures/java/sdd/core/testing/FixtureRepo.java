package sdd.core.testing;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.TimeZone;

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
        return commit(message, null);
    }

    /**
     * Commit with a fixed author/committer timestamp. The no-arg {@link #commit(String)} stamps
     * {@code now}, which is fine for tests that only compare SHAs against each other but not for
     * anything asserting on rendered history — a {@code git log} line carries the date, so without
     * a fixed clock the expected text changes every run.
     *
     * @param when UTC instant to stamp, or null to take the current time
     */
    public FixtureRepo commit(String message, Instant when) {
        PersonIdent ident = when == null
                ? AUTHOR
                : new PersonIdent(AUTHOR, Date.from(when), TimeZone.getTimeZone("UTC"));
        try (Git git = Git.open(root.toFile())) {
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();   // stage deletions, as RunGit does
            git.commit().setMessage(message).setAuthor(ident).setCommitter(ident).call();
        } catch (Exception e) {
            throw new IllegalStateException("cannot commit in " + root, e);
        }
        return this;
    }

    /** Create {@code name} off the current HEAD and check it out. */
    public FixtureRepo branch(String name) {
        try (Git git = Git.open(root.toFile())) {
            git.checkout().setCreateBranch(true).setName(name).call();
        } catch (Exception e) {
            throw new IllegalStateException("cannot create branch " + name + " in " + root, e);
        }
        return this;
    }

    /** Check out an existing branch or ref. */
    public FixtureRepo checkout(String name) {
        try (Git git = Git.open(root.toFile())) {
            git.checkout().setName(name).call();
        } catch (Exception e) {
            throw new IllegalStateException("cannot checkout " + name + " in " + root, e);
        }
        return this;
    }

    /** Tag the current HEAD. */
    public FixtureRepo tag(String name) {
        try (Git git = Git.open(root.toFile())) {
            git.tag().setName(name).setAnnotated(false).call();
        } catch (Exception e) {
            throw new IllegalStateException("cannot tag " + name + " in " + root, e);
        }
        return this;
    }

    /** Delete {@code relPath} from the working tree; the next {@link #commit} stages the removal. */
    public FixtureRepo delete(String relPath) {
        try {
            Files.delete(root.resolve(relPath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
