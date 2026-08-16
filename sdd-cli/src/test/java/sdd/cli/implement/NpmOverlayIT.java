package sdd.cli.implement;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real {@code npm pack} and a real tarball. The value of substitution is entirely in whether the
 * consumer ends up resolving the provider's changed code, which nothing but an end-to-end round
 * trip can demonstrate.
 */
@Tag("node-it")
class NpmOverlayIT {
    @TempDir Path tmp;

    private static void requireNpm() {
        Assumptions.assumeTrue(sdd.core.ts.NodeLocator.find(null).isPresent(),
                "node/npm not available on this machine");
    }

    private Path provider(String version, String body) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("provider"));
        Files.writeString(dir.resolve("package.json"), """
                {
                  "name": "@acme/lib",
                  "version": "%s",
                  "main": "./index.js",
                  "files": ["index.js"]
                }
                """.formatted(version));
        Files.writeString(dir.resolve("index.js"), body);
        Files.writeString(dir.resolve("secret.txt"), "not in files[]\n");
        return dir;
    }

    private Path consumerWithInstalled(String body) throws Exception {
        Path consumer = Files.createDirectories(tmp.resolve("consumer"));
        Path installed = Files.createDirectories(
                consumer.resolve("node_modules").resolve("@acme").resolve("lib"));
        Files.writeString(installed.resolve("index.js"), body);
        Files.writeString(installed.resolve("package.json"),
                "{\"name\":\"@acme/lib\",\"version\":\"0.1.0\"}");
        return consumer;
    }

    @Test
    void theConsumerEndsUpWithTheProvidersChangedCodeAndGetsItsOwnBack() throws Exception {
        requireNpm();
        Path provider = provider("0.1.0", "module.exports = 'CHANGED';\n");
        Path consumer = consumerWithInstalled("module.exports = 'PUBLISHED';\n");
        NpmOverlay overlay = new NpmOverlay(null);

        NpmOverlay.PackResult packed = overlay.pack(provider, "0.2.0", tmp.resolve("store"));
        assertThat(packed.ok()).as("%s", packed.log()).isTrue();

        NpmOverlay.Applied applied =
                overlay.apply(consumer, "@acme/lib", packed.tarball(), tmp.resolve("backup"));

        Path installedEntry = consumer.resolve("node_modules/@acme/lib/index.js");
        assertThat(Files.readString(installedEntry)).isEqualTo("module.exports = 'CHANGED';\n");
        // The packed manifest carries the planned version, which is what a consumer resolving by
        // range would have to accept.
        assertThat(Files.readString(consumer.resolve("node_modules/@acme/lib/package.json")))
                .contains("\"version\": \"0.2.0\"");

        overlay.restore(applied);

        assertThat(Files.readString(installedEntry)).isEqualTo("module.exports = 'PUBLISHED';\n");
    }

    @Test
    void theProvidersOwnManifestIsLeftExactlyAsItWas() throws Exception {
        requireNpm();
        Path provider = provider("0.1.0", "module.exports = 1;\n");
        String before = Files.readString(provider.resolve("package.json"));

        new NpmOverlay(null).pack(provider, "9.9.9", tmp.resolve("store"));

        // package.json is tracked, so a version left behind would land in the checkpoint diff as a
        // change nobody asked for.
        assertThat(Files.readString(provider.resolve("package.json"))).isEqualTo(before);
    }

    @Test
    void whatIsOverlaidIsWhatAReleaseWouldShip() throws Exception {
        requireNpm();
        Path provider = provider("0.1.0", "module.exports = 1;\n");
        Path consumer = consumerWithInstalled("old\n");
        NpmOverlay overlay = new NpmOverlay(null);

        NpmOverlay.PackResult packed = overlay.pack(provider, "0.2.0", tmp.resolve("store"));
        overlay.apply(consumer, "@acme/lib", packed.tarball(), tmp.resolve("backup"));

        // npm pack honours files[], so a file the package does not publish must not appear. Using
        // pack rather than copying the directory is what makes the overlay match a real release.
        assertThat(consumer.resolve("node_modules/@acme/lib/secret.txt")).doesNotExist();
        assertThat(consumer.resolve("node_modules/@acme/lib/index.js")).exists();
    }

    @Test
    void aConsumerWithNothingInstalledStillGetsTheProviderAndIsLeftClean() throws Exception {
        requireNpm();
        Path provider = provider("0.1.0", "module.exports = 'X';\n");
        Path consumer = Files.createDirectories(tmp.resolve("consumer"));
        NpmOverlay overlay = new NpmOverlay(null);

        NpmOverlay.PackResult packed = overlay.pack(provider, "0.2.0", tmp.resolve("store"));
        NpmOverlay.Applied applied =
                overlay.apply(consumer, "@acme/lib", packed.tarball(), tmp.resolve("backup"));
        assertThat(consumer.resolve("node_modules/@acme/lib/index.js")).exists();

        overlay.restore(applied);

        // Nothing was there before, so nothing may be there after.
        assertThat(consumer.resolve("node_modules/@acme/lib")).doesNotExist();
    }

    @Test
    void versionRewritingTouchesOnlyTheVersion() {
        String manifest = """
                {
                  "name": "@acme/lib",
                  "version": "0.1.0",
                  "dependencies": { "other": "^1.0.0" }
                }
                """;

        String rewritten = NpmOverlay.withVersion(manifest, "0.2.0");

        assertThat(rewritten).contains("\"version\": \"0.2.0\"")
                .contains("\"name\": \"@acme/lib\"")
                .contains("\"other\": \"^1.0.0\"");
        assertThat(rewritten.lines().count()).isEqualTo(manifest.lines().count());
    }
}
