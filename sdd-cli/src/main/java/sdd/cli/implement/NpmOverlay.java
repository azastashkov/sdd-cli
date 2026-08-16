package sdd.cli.implement;

import sdd.core.toolchain.EnvPolicy;
import sdd.core.toolchain.Subprocess;
import sdd.core.ts.NodeLocator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Makes a consumer build against a provider's CHANGED source, the npm way: the provider is packed
 * exactly as {@code npm publish} would ship it, and the tarball is unpacked over the consumer's
 * {@code node_modules/<package>} for the duration of the run.
 *
 * <p>Three alternatives were considered and rejected, all for the same reason — they leave state
 * behind that a run cannot reliably undo.
 * <ul>
 *   <li>{@code npm link} writes into the machine's global node prefix, outside every repository, so
 *       it has no run scope and no dependable restore.</li>
 *   <li>A {@code file:} override edits a TRACKED file, so it lands in the checkpoint diff as a
 *       change a human must remember never to merge.</li>
 *   <li>{@code npm install <tarball>} rewrites {@code package.json} and, depending on the npm
 *       version, the lockfile.</li>
 * </ul>
 * Unpacking over {@code node_modules} is what {@code npm install} would do to that directory anyway,
 * minus the resolver. {@code node_modules} is gitignored in every repo of this shape, so the
 * checkpoint diff stays clean, and the original is moved aside and moved back.
 *
 * <p>{@code npm pack} is the right producer because it honours {@code files} and runs
 * {@code prepack}, so what is overlaid is byte-for-byte what a release would publish — the same
 * fidelity argument that makes {@code publishToMavenLocal} the right Gradle producer.
 *
 * <p><b>Known limitation, reported rather than hidden:</b> an overlay installs the provider's own
 * code, not its runtime dependencies. A provider whose {@code dependencies} are not already present
 * in the consumer's tree will fail at import time, so Gate 1 warns when a provider that will be
 * overlaid declares any.
 */
public final class NpmOverlay {

    private static final Duration PACK_TIMEOUT = Duration.ofMinutes(10);

    /** One consumer's substitution, and how to undo it. */
    public record Applied(Path installed, Path backup) {
    }

    private final Path nodeHome;

    public NpmOverlay(Path nodeHome) {
        this.nodeHome = nodeHome;
    }

    public record PackResult(boolean ok, Path tarball, String log) {
    }

    /**
     * Packs {@code packageDir} at {@code version} into {@code storeDir}.
     *
     * <p>The version is set by writing it into {@code package.json} for the duration of the pack and
     * restoring it afterwards, because {@code npm pack} has no {@code -Pversion} equivalent. The
     * file is tracked, so leaving it modified would put a version change into the checkpoint diff
     * that the bump machinery is responsible for making deliberately, if at all.
     */
    public PackResult pack(Path packageDir, String version, Path storeDir) {
        Path manifest = packageDir.resolve("package.json");
        String original;
        try {
            Files.createDirectories(storeDir);
            original = Files.readString(manifest);
        } catch (IOException e) {
            return new PackResult(false, null, "cannot read " + manifest + ": " + e.getMessage());
        }
        try {
            Files.writeString(manifest, withVersion(original, version));
            Subprocess.Outcome outcome = Subprocess.run(
                    List.of(NodeLocator.npmExecutable(nodeHome), "pack",
                            "--pack-destination", storeDir.toAbsolutePath().toString()),
                    packageDir, EnvPolicy.scrubbedNode(nodeHome), PACK_TIMEOUT,
                    Subprocess.KillPolicy.PROCESS_TREE, "sdd-npm-pack");
            if (outcome.timedOut()) {
                return new PackResult(false, null, "timed out after " + PACK_TIMEOUT.toSeconds() + "s");
            }
            if (outcome.exitCode() != 0) {
                return new PackResult(false, null, "exit " + outcome.exitCode() + "\n" + outcome.output());
            }
            Path tarball = newestTarball(storeDir);
            return tarball == null
                    ? new PackResult(false, null, "npm pack produced no tarball in " + storeDir)
                    : new PackResult(true, tarball, "exit 0\n" + outcome.output());
        } catch (IOException e) {
            return new PackResult(false, null, "pack failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new PackResult(false, null, "interrupted");
        } finally {
            try {
                Files.writeString(manifest, original);
            } catch (IOException ignored) {
                // Best effort; the checkout is reset from git between attempts regardless.
            }
        }
    }

    /**
     * Unpacks {@code tarball} over {@code consumerRepo}'s copy of {@code packageName}, moving any
     * existing copy aside so {@link #restore} can put it back.
     */
    public Applied apply(Path consumerRepo, String packageName, Path tarball, Path backupRoot)
            throws IOException {
        Path installed = consumerRepo.resolve("node_modules").resolve(packageName);
        Path backup = null;
        if (Files.exists(installed, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            backup = backupRoot.resolve(consumerRepo.getFileName().toString())
                    .resolve(packageName.replace('/', '~'));
            Files.createDirectories(backup.getParent());
            deleteRecursively(backup);
            Files.move(installed, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.createDirectories(installed);
        // Every npm tarball roots its content at `package/`; that prefix is stripped so the
        // contents land where the package's own files were.
        untarStripping(tarball, installed);
        return new Applied(installed, backup);
    }

    /** Puts back exactly what was there, whether or not the run succeeded. */
    public void restore(Applied applied) {
        try {
            deleteRecursively(applied.installed());
            if (applied.backup() != null) {
                Files.createDirectories(applied.installed().getParent());
                Files.move(applied.backup(), applied.installed(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not restore " + applied.installed(), e);
        }
    }

    /** Rewrites only the top-level {@code "version"} value, leaving the rest of the file alone. */
    static String withVersion(String manifestJson, String version) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\"version\"\\s*:\\s*\")([^\"]*)(\")").matcher(manifestJson);
        return m.find()
                ? manifestJson.substring(0, m.start(2)) + version + manifestJson.substring(m.end(2))
                : manifestJson;
    }

    /**
     * The tarball {@code npm pack} produced for a package, or null when the provider never packed.
     *
     * <p>npm names a tarball by flattening the package name — {@code @acme/lib} becomes
     * {@code acme-lib-<version>.tgz} — so the name is reconstructed the same way rather than
     * guessed at from a version the caller may not know.
     */
    public static Path findTarball(Path storeDir, String packageName) {
        String prefix = packageName.startsWith("@")
                ? packageName.substring(1).replace('/', '-') : packageName;
        if (!Files.isDirectory(storeDir)) {
            return null;
        }
        try (Stream<Path> files = Files.list(storeDir)) {
            return files.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(prefix + "-") && name.endsWith(".tgz");
                    })
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static Path newestTarball(Path storeDir) throws IOException {
        try (Stream<Path> files = Files.list(storeDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".tgz"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
        }
    }

    /**
     * Extracts a gzipped tar, dropping the leading {@code package/} component.
     *
     * <p>Uses the platform {@code tar} rather than adding an archive library: it is present
     * everywhere npm is, and the alternative is a dependency carried solely to read one file format.
     */
    private void untarStripping(Path tarball, Path target) throws IOException {
        try {
            Subprocess.Outcome outcome = Subprocess.run(
                    List.of("tar", "-xzf", tarball.toAbsolutePath().toString(),
                            "-C", target.toAbsolutePath().toString(), "--strip-components", "1"),
                    target, EnvPolicy.scrubbedNode(nodeHome), Duration.ofMinutes(2),
                    Subprocess.KillPolicy.PROCESS_TREE, "sdd-npm-untar");
            if (outcome.timedOut() || outcome.exitCode() != 0) {
                throw new IOException("could not unpack " + tarball + ": " + outcome.output());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted unpacking " + tarball);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(dir)) {
            Files.delete(dir);
            return;
        }
        List<Path> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(entries::add);
        }
        for (Path entry : entries) {
            Files.deleteIfExists(entry);
        }
    }
}
