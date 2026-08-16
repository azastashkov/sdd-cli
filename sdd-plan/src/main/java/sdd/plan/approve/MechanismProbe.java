package sdd.plan.approve;

import sdd.core.toolchain.Mechanism;
import sdd.core.toolchain.Toolchain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Decides, once per cross-repo edge at Gate 1, how a consumer will be made to build against a
 * changed provider. The answer is frozen into {@code plan.json} so a run does not depend on the
 * machine still answering the same way.
 *
 * <p>The Gradle answer needs a live build, because whether a composite substitution works depends
 * on the two builds' configuration. The npm answer does not: whether the consumer already resolves
 * the provider from live source is a structural fact about {@code node_modules}, readable without
 * running anything. That makes it faster and, more usefully, unable to fail for environmental
 * reasons the way a probe build can.
 */
public final class MechanismProbe {

    private MechanismProbe() {
    }

    /**
     * @param providerPackage the provider's npm package name, or null when it publishes none
     * @param warningsOut     appended to when a fallback is chosen, so the reason reaches the plan
     */
    public static Mechanism decide(String from, Path consumerRepo, Toolchain consumerToolchain,
                                   String to, Path providerRepo, Toolchain providerToolchain,
                                   String providerPackage, String mode,
                                   SmokeRunner gradleSmoke, List<String> warningsOut) {
        if ("COMPOSITE".equals(mode)) {
            return Mechanism.NONE;   // already composed; nothing to substitute
        }
        if (consumerToolchain != providerToolchain) {
            // Nothing can substitute a Java service into a TypeScript build or the reverse. The
            // edge is still real and still orders the run — Scheduler consults every edge whatever
            // its mechanism — it simply injects nothing.
            warn(warningsOut, "edge " + from + "->" + to + ": " + consumerToolchain + " consumer of a "
                    + providerToolchain + " provider — no build substitution is possible, so the "
                    + "consumer builds against the provider's last published artifact");
            return Mechanism.NONE;
        }
        if (consumerToolchain == Toolchain.NPM) {
            return npmMechanism(from, consumerRepo, to, providerRepo, providerPackage, warningsOut);
        }
        SmokeRunner.Result result = gradleSmoke.probe(consumerRepo, providerRepo);
        if (result.ok()) {
            return Mechanism.INCLUDE_BUILD;
        }
        warn(warningsOut, "edge " + from + "->" + to + ": include-build probe failed ("
                + result.detail() + ") — falling back to mavenLocal");
        return Mechanism.MAVEN_LOCAL;
    }

    /**
     * A workspaces monorepo materialises a sibling package as a symlink into its source directory,
     * so a consumer that already resolves the provider that way needs nothing injected. Anything
     * else — a registry install, or no install at all — needs the provider packed and overlaid.
     */
    private static Mechanism npmMechanism(String from, Path consumerRepo, String to,
                                          Path providerRepo, String providerPackage,
                                          List<String> warningsOut) {
        if (providerPackage == null || providerPackage.isBlank()) {
            warn(warningsOut, "edge " + from + "->" + to + ": " + to + " publishes no npm package "
                    + "name, so its changes cannot be substituted into " + from);
            return Mechanism.NONE;
        }
        Path installed = consumerRepo.resolve("node_modules").resolve(providerPackage);
        if (Files.isSymbolicLink(installed)) {
            try {
                if (installed.toRealPath().startsWith(providerRepo.toRealPath())) {
                    return Mechanism.WORKSPACE_LINK;
                }
            } catch (IOException e) {
                // A link that cannot be followed is not a working substitution; fall through.
            }
        }
        return Mechanism.NPM_OVERLAY;
    }

    private static void warn(List<String> warningsOut, String warning) {
        if (!warningsOut.contains(warning)) {
            warningsOut.add(warning);
        }
    }
}
