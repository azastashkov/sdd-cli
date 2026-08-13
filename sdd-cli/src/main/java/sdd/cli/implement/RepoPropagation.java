package sdd.cli.implement;

import java.nio.file.Path;
import java.util.List;

/** Precomputed per-repo propagation work (4C-2b): deterministic bump edits applied after every
 *  branch reset, and the run-scoped publish a MAVEN_LOCAL provider owes its consumers on success. */
public record RepoPropagation(List<BumpEdit> bumps, PublishSpec publish) {
    public record BumpEdit(String group, String name, String oldVersion, String newVersion) {
    }

    /** null publish on the enclosing record = this repo provides no MAVEN_LOCAL edge. */
    public record PublishSpec(String version, Path m2Dir) {
    }

    public RepoPropagation {
        bumps = List.copyOf(bumps);
    }

    public static RepoPropagation none() {
        return new RepoPropagation(List.of(), null);
    }
}
