package sdd.plan.gen;

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
 * Prototype of the change-set fact source, built to be MEASURED, not shipped.
 *
 * <p>It answers the one question the diagnosis left open: given a git revision range, can the
 * changed-file set be mapped onto {@code java_type.file_path} mechanically enough to seed impact
 * analysis? Everything here is deliberately kept out of production until that measurement says the
 * feature earns its place.
 *
 * <p>Nothing is persisted. A commit table would need a second freshness contract on top of
 * {@code head_commit||':'||dirty_hash}, and a rebase would leave dangling rows that nothing detects
 * — a wrong fact rather than an unread one.
 */
final class ChangeSetProbe {

    /** One changed path, and the type it maps to ({@code null} when it maps to none). */
    record ChangedFile(String path, String changeKind, String fqcn, boolean isApi) {}

    record RepoChange(String repo, String range, String fromSha, String toSha,
                      List<ChangedFile> files, String resolution) {

        List<ChangedFile> mapped() { return files.stream().filter(f -> f.fqcn() != null).toList(); }

        List<ChangedFile> unmapped() { return files.stream().filter(f -> f.fqcn() == null).toList(); }
    }

    private ChangeSetProbe() {
    }

    /**
     * @param ranges repo name -> either {@code "<a>..<b>"} or a bare ref meaning {@code "<ref>..HEAD"}
     */
    static List<RepoChange> compute(Jdbi jdbi, Map<String, String> ranges) {
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
    static String seedLines(List<RepoChange> changes) {
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
