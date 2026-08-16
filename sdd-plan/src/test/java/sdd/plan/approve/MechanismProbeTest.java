package sdd.plan.approve;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.toolchain.Mechanism;
import sdd.core.toolchain.Toolchain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MechanismProbeTest {
    @TempDir Path ws;

    private final List<String> warnings = new ArrayList<>();

    private static final SmokeRunner PROBE_OK = (c, p) -> new SmokeRunner.Result(true, "");
    private static final SmokeRunner PROBE_FAILS = (c, p) -> new SmokeRunner.Result(false, "boom");

    private Mechanism decide(Path consumer, Toolchain consumerTc, Path provider, Toolchain providerTc,
                             String pkg, SmokeRunner smoke) {
        return MechanismProbe.decide("from", consumer, consumerTc, "to", provider, providerTc,
                pkg, "PINNED", smoke, warnings);
    }

    @Test
    void gradleStillProbesAndFallsBack() {
        assertThat(decide(ws, Toolchain.GRADLE, ws, Toolchain.GRADLE, null, PROBE_OK))
                .isEqualTo(Mechanism.INCLUDE_BUILD);
        assertThat(decide(ws, Toolchain.GRADLE, ws, Toolchain.GRADLE, null, PROBE_FAILS))
                .isEqualTo(Mechanism.MAVEN_LOCAL);
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("falling back to mavenLocal"));
    }

    @Test
    void aCompositeEdgeIsAlreadyComposedWhateverTheToolchain() {
        assertThat(MechanismProbe.decide("from", ws, Toolchain.GRADLE, "to", ws, Toolchain.GRADLE,
                null, "COMPOSITE", PROBE_FAILS, warnings)).isEqualTo(Mechanism.NONE);
        assertThat(warnings).isEmpty();
    }

    @Test
    void anNpmConsumerAlreadyLinkedToItsProviderNeedsNothingInjected() throws Exception {
        Path provider = Files.createDirectories(ws.resolve("provider"));
        Path consumer = Files.createDirectories(ws.resolve("consumer"));
        Path modules = Files.createDirectories(consumer.resolve("node_modules/@acme"));
        Files.createSymbolicLink(modules.resolve("lib"), provider);

        // A workspaces monorepo resolves a sibling to live source, so the consumer is already
        // building what the provider is changing. This is the npm counterpart of a composite.
        assertThat(decide(consumer, Toolchain.NPM, provider, Toolchain.NPM, "@acme/lib", PROBE_FAILS))
                .isEqualTo(Mechanism.WORKSPACE_LINK);
    }

    @Test
    void anNpmConsumerResolvingFromTheRegistryNeedsAnOverlay() throws Exception {
        Path provider = Files.createDirectories(ws.resolve("provider"));
        Path consumer = Files.createDirectories(ws.resolve("consumer"));
        Files.createDirectories(consumer.resolve("node_modules/@acme/lib"));   // a real install

        assertThat(decide(consumer, Toolchain.NPM, provider, Toolchain.NPM, "@acme/lib", PROBE_FAILS))
                .isEqualTo(Mechanism.NPM_OVERLAY);
    }

    @Test
    void aSymlinkPointingSomewhereElseIsNotAWorkspaceLink() throws Exception {
        Path provider = Files.createDirectories(ws.resolve("provider"));
        Path elsewhere = Files.createDirectories(ws.resolve("elsewhere"));
        Path consumer = Files.createDirectories(ws.resolve("consumer"));
        Path modules = Files.createDirectories(consumer.resolve("node_modules/@acme"));
        Files.createSymbolicLink(modules.resolve("lib"), elsewhere);

        // Linked to something, but not to the provider whose change has to reach this consumer.
        assertThat(decide(consumer, Toolchain.NPM, provider, Toolchain.NPM, "@acme/lib", PROBE_FAILS))
                .isEqualTo(Mechanism.NPM_OVERLAY);
    }

    @Test
    void aCrossToolchainEdgeInjectsNothingAndSaysWhy() {
        assertThat(decide(ws, Toolchain.NPM, ws, Toolchain.GRADLE, "@acme/lib", PROBE_OK))
                .isEqualTo(Mechanism.NONE);

        // The edge is still real and still orders the run; it simply cannot be substituted, and a
        // reader of the plan needs to know the consumer builds against the published artifact.
        assertThat(warnings).anySatisfy(w -> assertThat(w)
                .contains("no build substitution is possible")
                .contains("last published artifact"));
    }

    @Test
    void aProviderThatPublishesNoPackageCannotBeSubstituted() {
        assertThat(decide(ws, Toolchain.NPM, ws, Toolchain.NPM, null, PROBE_OK))
                .isEqualTo(Mechanism.NONE);
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("publishes no npm package name"));
    }

    @Test
    void anUnknownMechanismStringReadsAsNone() {
        // A plan.json approved by a newer binary must not make an older one guess at a
        // substitution nobody chose.
        assertThat(Mechanism.of("SOMETHING_NEW")).isEqualTo(Mechanism.NONE);
        assertThat(Mechanism.of(null)).isEqualTo(Mechanism.NONE);
        assertThat(Mechanism.of("NPM_OVERLAY")).isEqualTo(Mechanism.NPM_OVERLAY);
    }
}
