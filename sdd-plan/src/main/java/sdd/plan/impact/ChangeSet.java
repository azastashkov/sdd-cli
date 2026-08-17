package sdd.plan.impact;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A git revision range, read as impact-analysis seeds: the developer's own first move — "what
 * changed in this repo?" — made available to the planner.
 *
 * <p>Measured before it was built. A spec that names a commit sha in prose produced zero seeds and
 * a blocking "no seeds" question, while this mapping reproduced the change's ground truth exactly:
 * 3 of 3 changed main-source files onto the right types, with the 4 test files reported as
 * unmapped rather than silently dropped.
 *
 * <p><b>Nothing is persisted, deliberately.</b> A commit table would need a second freshness
 * contract on top of {@code head_commit||':'||dirty_hash}, which says only whether HEAD is indexed
 * and nothing about whether stored history is complete for an arbitrary range. Worse, a rebase or
 * force-push would leave dangling rows that nothing in the indexer detects — a KB that answers with
 * a WRONG fact rather than merely an unread one. Git answers this in milliseconds; the reproducible
 * artifact is the resolved sha recorded in the seed's provenance, not a cached diff.
 */
public final class ChangeSet {

    /** One changed path, and the type it maps to ({@code null} when it maps to none). */
    public record ChangedFile(String path, String changeKind, String fqcn, boolean isApi) {}

    public record RepoChange(String repo, String range, String fromSha, String toSha,
                      List<ChangedFile> files, String resolution) {

        public List<ChangedFile> mapped() { return files.stream().filter(f -> f.fqcn() != null).toList(); }

        public List<ChangedFile> unmapped() { return files.stream().filter(f -> f.fqcn() == null).toList(); }
    }

    private ChangeSet() {
    }

    /**
     * @param ranges repo name -> either {@code "<a>..<b>"} or a bare ref meaning {@code "<ref>..HEAD"}
     */
    public static List<RepoChange> compute(Jdbi jdbi, Map<String, String> ranges) {
        List<RepoChange> out = new ArrayList<>();
        ranges.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            String repo = e.getKey();
            String range = e.getValue();
            String path = jdbi.withHandle(h -> h.createQuery("SELECT path FROM repo WHERE name = :n")
                    .bind("n", repo).mapTo(String.class).findOne().orElse(null));
            if (path == null) {
                out.add(new RepoChange(repo, range, null, null, List.of(), "REPO_NOT_IN_KB"));
                return;
            }
            out.add(scan(jdbi, repo, Path.of(path), range));
        });
        return out;
    }

    private static RepoChange scan(Jdbi jdbi, String repo, Path repoPath, String range) {
        String left = range.contains("..") ? range.substring(0, range.indexOf("..")) : range;
        String right = range.contains("..") ? range.substring(range.indexOf("..") + 2) : "HEAD";
        try (Git git = Git.open(repoPath.toFile())) {
            Repository repository = git.getRepository();
            ObjectId from = repository.resolve(left);
            ObjectId to = repository.resolve(right);
            if (from == null || to == null) {
                return new RepoChange(repo, range, null, null, List.of(), "REF_NOT_FOUND");
            }
            List<DiffEntry> entries;
            try (RevWalk walk = new RevWalk(repository);
                 DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                formatter.setRepository(repository);
                CanonicalTreeParser a = new CanonicalTreeParser();
                a.reset(walk.getObjectReader(), walk.parseCommit(from).getTree());
                CanonicalTreeParser b = new CanonicalTreeParser();
                b.reset(walk.getObjectReader(), walk.parseCommit(to).getTree());
                entries = formatter.scan(a, b);
            }
            List<ChangedFile> files = new ArrayList<>();
            for (DiffEntry entry : entries) {
                // DELETE has no new path; everything else is addressed by where the file ended up,
                // which is the side java_type.file_path records.
                String p = entry.getChangeType() == DiffEntry.ChangeType.DELETE
                        ? entry.getOldPath() : entry.getNewPath();
                Map<String, Object> row = jdbi.withHandle(h -> h.createQuery("""
                                SELECT t.fqcn AS fqcn, t.is_api AS is_api
                                FROM java_type t
                                JOIN module m ON m.id = t.module_id
                                JOIN repo r ON r.id = m.repo_id
                                WHERE r.name = :r AND t.file_path = :p
                                ORDER BY t.fqcn LIMIT 1""")
                        .bind("r", repo).bind("p", p).mapToMap().findOne().orElse(null));
                files.add(new ChangedFile(p, entry.getChangeType().name(),
                        row == null ? null : String.valueOf(row.get("fqcn")),
                        row != null && ((Number) row.get("is_api")).intValue() == 1));
            }
            files.sort((x, y) -> x.path().compareTo(y.path()));
            return new RepoChange(repo, range, from.abbreviate(8).name(), to.abbreviate(8).name(),
                    files, "RESOLVED");
        } catch (IOException ex) {
            return new RepoChange(repo, range, null, null, List.of(), "NOT_A_GIT_REPO");
        }
    }

    /** Renders what these changes would contribute as impact seeds. */
    public static String seedLines(List<RepoChange> changes) {
        StringBuilder sb = new StringBuilder();
        for (RepoChange c : changes) {
            if (!"RESOLVED".equals(c.resolution())) {
                sb.append("  ! ").append(c.repo()).append(" — ").append(c.resolution()).append('\n');
                continue;
            }
            if (c.mapped().isEmpty() && c.unmapped().isEmpty()) continue;
            List<String> names = c.mapped().stream()
                    .map(f -> f.fqcn().substring(f.fqcn().lastIndexOf('.') + 1)).sorted().toList();
            sb.append("  seed ").append(c.repo()).append(" (git: changed since ")
                    .append(c.fromSha()).append(": ").append(c.files().size()).append(" files, ")
                    .append(c.mapped().size()).append(" types — ").append(names).append(")\n");
            sb.append("       unmapped files: ").append(c.unmapped().size()).append(' ')
                    .append(c.unmapped().stream().map(ChangedFile::path).toList()).append('\n');
        }
        return sb.toString();
    }
}
