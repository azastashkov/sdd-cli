package sdd.cli.implement;

import sdd.index.npm.NpmExtractor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies one planned bump with whichever edit its ecosystem needs, and says what it did.
 *
 * <p>The dispatch is the point. {@link VersionBump} walks {@code build.gradle(.kts)} and
 * {@code libs.versions.toml} and nothing else, so an {@code ("npm", "@scope/pkg")} coordinate — the
 * shape every npm dependency is recorded under — matched no file and was reported as "no
 * declaration found". The consumer's pin therefore never moved, and the agent, whose sub-spec told
 * it to update the dependency, was left to invent a version: observed live picking {@code ^0.4.0}
 * against a planned {@code 0.3.0}, pinning the consumer to something nothing publishes.
 *
 * <p>An npm no-op is reported differently from a Gradle one on purpose. Most npm declarations are
 * ranges, and a range that already admits the planned version SHOULD be left alone —
 * {@link NpmVersionBump} says so in detail — so "left unedited" there is the expected outcome
 * rather than the missing-declaration problem the Gradle wording describes.
 */
public final class BumpEdits {

    private BumpEdits() {
    }

    /** @return the events to record for this bump; never empty. */
    public static List<String> apply(Path repoRoot, RepoPropagation.BumpEdit bump) {
        String coordinate = bump.group() + ":" + bump.name();
        List<String> events = new ArrayList<>();
        if (NpmExtractor.NPM_GROUP.equals(bump.group())) {
            NpmVersionBump.Result result = NpmVersionBump.apply(repoRoot, bump.name(), bump.newVersion());
            events.addAll(result.warnings());
            events.add(result.edited().isEmpty()
                    ? "bump: " + coordinate + " left unedited — " + bump.oldVersion()
                            + " already admits " + bump.newVersion()
                            + ", or the package is not declared here"
                    : edited(coordinate, bump, result.edited().size()));
            return events;
        }
        List<Path> edited = VersionBump.apply(repoRoot, bump.group(), bump.name(),
                bump.oldVersion(), bump.newVersion());
        events.add(edited.isEmpty()
                ? "bump: no declaration of " + coordinate + ":" + bump.oldVersion()
                        + " found — left unedited"
                : edited(coordinate, bump, edited.size()));
        return events;
    }

    private static String edited(String coordinate, RepoPropagation.BumpEdit bump, int files) {
        return "bump: " + coordinate + " " + bump.oldVersion() + " -> " + bump.newVersion()
                + " in " + files + " file(s)";
    }
}
