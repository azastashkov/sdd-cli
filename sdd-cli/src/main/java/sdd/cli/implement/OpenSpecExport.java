package sdd.cli.implement;

import org.eclipse.jgit.api.Git;
import sdd.plan.openspec.OpenSpecChange;
import sdd.plan.openspec.OpenSpecInput;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes a rendered OpenSpec change into a repository working tree.
 *
 * <p>The governing rule, and the reason every branch below exists: <b>sdd writes only under
 * {@code openspec/changes/<our-id>/}, and never over bytes it did not itself produce.</b> The
 * target repository belongs to somebody else. An export that clobbers a human's proposal, or
 * appends to a main spec, is worse than no export — it destroys work while looking like a feature.
 *
 * <p>Consequences worth stating rather than discovering:
 * <ul>
 *   <li>{@code openspec/specs/**} is never written. Not created, not appended, not archived.
 *       Folding a delta into a main spec is the foreign agent's act, and archive's rules
 *       (block replacement, the scenario-loss guard, capability retirement) are exactly where a
 *       reimplementation would diverge.
 *   <li>{@code openspec/config.yaml} is never created or touched. Its schema for the targeted
 *       version is unverified, and inventing one would produce a file the real CLI may reject.
 *   <li>A change directory that exists and <em>differs in any file</em> is left entirely alone —
 *       not merged, not partially written, and with no {@code .sdd-new} sidecar, because a stray
 *       file inside a change directory risks confusing the validator this export targets.
 * </ul>
 */
public final class OpenSpecExport {

    private OpenSpecExport() {
    }

    /** What {@link #materialize} did, as event lines for the repo's agent-events log. */
    public record Result(boolean written, List<String> events) {
        public Result {
            events = List.copyOf(events);
        }
    }

    /**
     * Whether this capability already has a main spec in the target repository.
     *
     * <p>Decides only whether {@code proposal.md} says "New Capabilities" or "Modified
     * Capabilities" — it never changes the delta, which is always {@code ADDED}. Must be called
     * while the tree is at the plan's {@code base_sha}, which after {@code RunGit.startBranch} it
     * provably is, on a fresh run and on a resume alike.
     */
    public static boolean capabilityExists(Path repoRoot, String capability) {
        return Files.isRegularFile(repoRoot.resolve("openspec/specs").resolve(capability)
                .resolve("spec.md"));
    }

    /**
     * Writes the change, or explains why it did not.
     *
     * <p>All-or-nothing on purpose: a half-written change directory is a change that neither
     * validates nor reflects anybody's intent.
     */
    public static Result materialize(Path repoRoot, String changeId, OpenSpecChange.Files files) {
        Map<String, String> wanted = files.byPath(changeId);
        Path changeDir = repoRoot.resolve("openspec/changes").resolve(changeId);
        List<String> events = new ArrayList<>();

        if (Files.exists(changeDir)) {
            List<String> differing = differing(repoRoot, wanted);
            if (differing.isEmpty()) {
                events.add("openspec: change " + changeId + " already present and identical");
                return new Result(false, events);
            }
            events.add("openspec: change " + changeId + " exists and differs ("
                    + String.join(", ", differing) + ") — left untouched; sdd's version is in the "
                    + "run directory");
            return new Result(false, events);
        }

        try {
            for (Map.Entry<String, String> file : wanted.entrySet()) {
                Path target = repoRoot.resolve(file.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the OpenSpec export into " + repoRoot, e);
        }
        events.add("openspec: wrote change " + changeId + " (" + wanted.size() + " files)");
        ignoredWarning(repoRoot, wanted.keySet()).ifPresent(events::add);
        return new Result(true, events);
    }

    /** Which of the files we would write already exist with different content. */
    private static List<String> differing(Path repoRoot, Map<String, String> wanted) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> file : wanted.entrySet()) {
            Path target = repoRoot.resolve(file.getKey());
            try {
                if (!Files.exists(target)
                        || !Files.readString(target, StandardCharsets.UTF_8).equals(file.getValue())) {
                    out.add(file.getKey());
                }
            } catch (IOException e) {
                out.add(file.getKey());   // unreadable counts as different: never overwrite it
            }
        }
        return out;
    }

    /**
     * A warning when the export cannot reach the checkpoint because the repository ignores it.
     *
     * <p>{@code RunGit.commitAll} is JGit's {@code AddCommand}, which honours {@code .gitignore} —
     * that is exactly why {@code NpmOverlay} can keep backups in ignored paths and stay out of the
     * reviewed diff. A repository that gitignores {@code openspec/} would therefore produce a
     * checkpoint with none of these files in it, silently. Detected and named; never overridden,
     * because an ignore rule is the repository's decision and forcing past it would put files in a
     * diff its owners chose not to track.
     */
    private static java.util.Optional<String> ignoredWarning(Path repoRoot, Set<String> paths) {
        try (Git git = Git.open(repoRoot.toFile())) {
            Set<String> ignored = git.status().call().getIgnoredNotInIndex();
            List<String> hidden = paths.stream()
                    .filter(p -> ignored.stream()
                            .anyMatch(i -> p.equals(i) || p.startsWith(i.endsWith("/") ? i : i + "/")))
                    .sorted()
                    .toList();
            if (hidden.isEmpty()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of("openspec: WARNING — " + hidden.size() + " exported file(s)"
                    + " are git-ignored in this repository and will NOT reach the checkpoint commit"
                    + " (first: " + hidden.get(0) + "); remove the ignore rule to track them");
        } catch (Exception e) {
            // A repo we cannot read the status of is not a reason to fail the export; the files are
            // already written, and the checkpoint will show whether they landed.
            return java.util.Optional.empty();
        }
    }
}
