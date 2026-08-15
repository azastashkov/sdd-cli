package sdd.core.ts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import sdd.core.toolchain.EnvPolicy;
import sdd.core.toolchain.Subprocess;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Reads TypeScript by asking the TypeScript compiler, through a small script run under
 * {@code node}. sdd is a Java program and TypeScript's semantics live in one implementation; asking
 * it is the only way to get answers that agree with what the repo's own build believes.
 *
 * <p>Nothing about a repo is read from {@code node_modules}, and nothing is fetched at run time.
 * The compiler ships as an ordinary jar dependency and is unpacked next to the script, mirroring
 * how the Gradle extractor materialises its init script.
 *
 * <p>Failure is always visible. A missing {@code node}, a crash, a timeout or a malformed response
 * produces a {@link Result} that says so; none of them is reported as "this repo contains nothing".
 */
public final class TsSidecar {
    /** Protocol version, matched on both sides so a stale materialised script cannot go unnoticed. */
    static final int PROTOCOL_VERSION = 1;

    private static final String SCRIPT_RESOURCE = "/sdd/ts/sdd-ts-extract.cjs";

    /**
     * Where the compiler sits inside {@code org.webjars.npm:typescript}. The version is part of the
     * path, so it is pinned here AND in {@code gradle/libs.versions.toml}. {@code SidecarAssetsTest}
     * loads this exact resource, so the two drifting apart fails the build rather than surfacing as
     * "TypeScript support silently unavailable".
     */
    static final String TS_VERSION = "5.5.4";
    private static final String TS_RESOURCE =
            "/META-INF/resources/webjars/typescript/" + TS_VERSION + "/lib/typescript.js";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    /** A sidecar call's outcome. {@code json} is null unless {@code ok}. */
    public record Result(boolean ok, JsonNode json, String error) {
        public static Result of(JsonNode json) {
            return new Result(true, json, null);
        }

        public static Result failed(String error) {
            return new Result(false, null, error);
        }
    }

    private final Path nodeExecutable;
    private final Duration timeout;
    private volatile Path installDir;

    private TsSidecar(Path nodeExecutable, Duration timeout) {
        this.nodeExecutable = nodeExecutable;
        this.timeout = timeout;
    }

    /**
     * @return empty when node cannot be found; the caller decides whether that is fatal. It is not
     *         fatal for indexing (an npm repo's build model needs no node at all) and it is not
     *         fatal for the agent's edit gate (which fails open, loudly), so this deliberately
     *         reports absence rather than throwing.
     */
    public static Optional<TsSidecar> create(Path nodeHome) {
        return create(nodeHome, DEFAULT_TIMEOUT);
    }

    public static Optional<TsSidecar> create(Path nodeHome, Duration timeout) {
        return NodeLocator.find(nodeHome).map(node -> new TsSidecar(node, timeout));
    }

    public Path nodeExecutable() {
        return nodeExecutable;
    }

    /** Proves node runs, the compiler loaded and the protocol matches, without needing a repo. */
    public Result ping() {
        return call(MAPPER.createObjectNode()
                .put("version", PROTOCOL_VERSION)
                .put("mode", "ping"));
    }

    /**
     * Parses one file in isolation and reports the first syntax error, or none. The exact analogue
     * of the Java side's JavaParser gate: no program, no type checking, no module resolution, so it
     * costs about a millisecond and cannot be affected by anything outside the file.
     */
    public Result syntaxCheck(String fileName, String text) {
        return call(MAPPER.createObjectNode()
                .put("version", PROTOCOL_VERSION)
                .put("mode", "syntax")
                .put("file", fileName)
                .put("text", text));
    }

    private Result call(JsonNode request) {
        Path requestFile = null;
        Path responseFile = null;
        try {
            Path dir = install();
            requestFile = Files.createTempFile("sdd-ts-req", ".json");
            responseFile = Files.createTempFile("sdd-ts-res", ".json");
            Files.writeString(requestFile, MAPPER.writeValueAsString(request), StandardCharsets.UTF_8);

            Subprocess.Outcome outcome = Subprocess.run(
                    List.of(nodeExecutable.toString(), dir.resolve("sdd-ts-extract.cjs").toString(),
                            "--request", requestFile.toString(), "--out", responseFile.toString()),
                    dir, EnvPolicy.scrubbedJvm(null), timeout,
                    Subprocess.KillPolicy.PROCESS_TREE, "sdd-ts");

            if (outcome.timedOut()) {
                return Result.failed("ts sidecar timed out after " + timeout.toSeconds() + "s");
            }
            if (outcome.exitCode() != 0) {
                return Result.failed("ts sidecar exited " + outcome.exitCode() + ": "
                        + tail(outcome.output()));
            }
            JsonNode response = MAPPER.readTree(Files.readString(responseFile, StandardCharsets.UTF_8));
            int version = response.path("version").asInt(-1);
            if (version != PROTOCOL_VERSION) {
                return Result.failed("ts sidecar spoke protocol " + version
                        + ", expected " + PROTOCOL_VERSION);
            }
            return Result.of(response);
        } catch (IOException e) {
            return Result.failed("ts sidecar io failure: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed("ts sidecar interrupted");
        } finally {
            deleteQuietly(requestFile);
            deleteQuietly(responseFile);
        }
    }

    /**
     * Unpacks the script and the compiler into a directory named after their combined content
     * hash, so an upgrade lands in a new directory and a half-written one can never be mistaken for
     * a complete one. Written to a temp sibling and renamed, so two concurrent runs cannot see a
     * partial install.
     */
    private Path install() throws IOException {
        Path cached = installDir;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (installDir != null) {
                return installDir;
            }
            byte[] script = resource(SCRIPT_RESOURCE);
            byte[] compiler = resource(TS_RESOURCE);
            Path target = Path.of(System.getProperty("java.io.tmpdir"))
                    .resolve("sdd-ts-sidecar-" + shortHash(script, compiler));
            if (!Files.isRegularFile(target.resolve("sdd-ts-extract.cjs"))
                    || !Files.isRegularFile(target.resolve("typescript.js"))) {
                Path staging = Files.createTempDirectory("sdd-ts-staging");
                Files.write(staging.resolve("sdd-ts-extract.cjs"), script);
                Files.write(staging.resolve("typescript.js"), compiler);
                try {
                    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException alreadyThere) {
                    // Another process won the race and its content is identical by construction —
                    // the directory name IS the content hash.
                    deleteRecursively(staging);
                }
            }
            installDir = target;
            return target;
        }
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = TsSidecar.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("missing classpath resource " + name
                        + " — the TypeScript compiler dependency is not on the runtime classpath");
            }
            return in.readAllBytes();
        }
    }

    private static String shortHash(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) {
                digest.update(part);
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String tail(String s) {
        String trimmed = s == null ? "" : s.strip();
        return trimmed.length() <= 2000 ? trimmed : trimmed.substring(trimmed.length() - 2000);
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // best-effort temp cleanup
            }
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        } catch (IOException | UncheckedIOException ignored) {
            // best-effort
        }
    }
}
