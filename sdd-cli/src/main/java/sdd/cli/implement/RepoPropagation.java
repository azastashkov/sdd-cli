package sdd.cli.implement;

import java.nio.file.Path;
import java.util.List;

/** Precomputed per-repo propagation work (4C-2b): deterministic bump edits applied after every
 *  branch reset, and the run-scoped publish a MAVEN_LOCAL provider owes its consumers on success. */
public record RepoPropagation(List<BumpEdit> bumps, PublishSpec publish,
                              List<PackSpec> packs, List<OverlaySpec> overlays) {
    public record BumpEdit(String group, String name, String oldVersion, String newVersion) {
    }

    /** null publish on the enclosing record = this repo provides no MAVEN_LOCAL edge. */
    public record PublishSpec(String version, Path m2Dir) {
    }

    /**
     * An npm package this repo must pack when it succeeds, so its consumers can build against the
     * change. The npm counterpart of {@link PublishSpec}, and a list rather than a single value
     * because a workspaces repo can publish several packages independently.
     *
     * @param packageDir the package's own directory, which for a workspace member is not the repo
     *                   root — {@code npm pack} has to run where the package.json is
     */
    public record PackSpec(String packageName, Path packageDir, String version, Path storeDir) {
    }

    /** An npm package that must be overlaid into this repo before it builds. */
    public record OverlaySpec(String packageName, String providerRepo, Path storeDir) {
    }

    public RepoPropagation {
        bumps = List.copyOf(bumps);
        packs = List.copyOf(packs);
        overlays = List.copyOf(overlays);
    }

    /** Back-compat for the Gradle-only shape. */
    public RepoPropagation(List<BumpEdit> bumps, PublishSpec publish) {
        this(bumps, publish, List.of(), List.of());
    }

    public static RepoPropagation none() {
        return new RepoPropagation(List.of(), null, List.of(), List.of());
    }
}
