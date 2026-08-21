package sdd.core.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.filter.PathFilter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything sdd reads out of git, and nothing it writes. The read half of the split
 * {@code RunGit}'s javadoc already names ("LiveGit reads; this writes") — extracted here so the
 * agent-facing {@code git_history} tool and the orchestrator share one implementation.
 *
 * <p><b>Read-only structurally, not by policy.</b> There is no verb allowlist and no flag
 * filtering to maintain, because there is no command line: every method below is a JGit read.
 * A caller cannot reach checkout, reset, commit, clean or push through this class because those
 * methods are not on it. That property is what makes it safe to hand to a model, and it is the
 * reason this is JGit rather than a {@code git} subprocess — a subprocess would need an argv
 * allowlist plus rejection of {@code -c}, {@code --ext-diff}, {@code --output} and the
 * {@code GIT_*} environment, forever.
 *
 * <p><b>Revisions, not SHAs.</b> Unlike {@code RunGit}'s private helper, which took
 * {@code ObjectId.fromString} and therefore only ever accepted a full 40-character SHA, every
 * {@code rev} argument here goes through {@link Repository#resolve}, so {@code HEAD~3}, a branch
 * name and a tag all work. Full SHAs are a subset, so the orchestrator's existing callers are
 * unaffected.
 *
 * <p><b>Resolved SHAs travel with every answer.</b> {@code ChangeSet}'s javadoc states the
 * principle this follows: "the reproducible artifact is the resolved sha recorded in the seed's
 * provenance, not a cached diff". {@code HEAD} moves; a SHA does not, so callers rendering these
 * results for a model are expected to echo {@link #resolve}'s output alongside them.
 *
 * <p>No JGit type appears in a signature, so a consumer needs JGit on its runtime classpath only.
 * The private {@code *In} helpers are named apart from their public counterparts for that reason
 * and not for style: javac resolves every same-arity overload before picking one, so a private
 * {@code resolve(Repository, String)} beside a public {@code resolve(Path, String)} would force
 * every caller to have JGit at COMPILE time — the same trap {@code sdd-cli}'s build file documents
 * for javaparser.
 */
public final class GitRead {

    /** How many hex characters an abbreviated SHA carries — as {@code RuntimeInfo} abbreviates. */
    public static final int SHORT_SHA = 12;

    private GitRead() {
    }

    /** One commit, flattened. {@code when} is the author time. */
    public record Commit(String sha, String shortSha, String author, Instant when, String subject) {
    }

    /**
     * One path touched by a diff.
     *
     * @param path       the new path, or the old one for a delete
     * @param changeKind ADD | MODIFY | DELETE | RENAME | COPY
     * @param oldPath    the pre-rename path, or null when this is not a rename
     */
    public record FileChange(String path, String changeKind, String oldPath,
                             int insertions, int deletions) {
    }

    /** One blamed line. {@code line} is 1-based, matching how {@code read_file} numbers. */
    public record BlameLine(String shortSha, String author, Instant when, int line, String text) {
    }

    /** A resolved revision range — both ends as full SHAs. */
    public record Range(String fromSha, String toSha) {
    }

    /**
     * Full SHA for any revision expression git understands.
     *
     * @throws IllegalStateException when the repo cannot be opened or {@code rev} names nothing
     */
    public static String resolve(Path repo, String rev) {
        try (Git git = Git.open(repo.toFile())) {
            return resolveIn(git.getRepository(), rev);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("cannot open git repo " + repo + ": " + e.getMessage(), e);
        }
    }

    private static String resolveIn(Repository repository, String rev) {
        String wanted = rev == null || rev.isBlank() ? "HEAD" : rev.strip();
        ObjectId id;
        try {
            id = repository.resolve(wanted);
        } catch (Exception e) {
            throw new IllegalStateException("cannot resolve '" + wanted + "': " + e.getMessage(), e);
        }
        if (id == null) {
            throw new IllegalStateException("no such revision: '" + wanted + "'");
        }
        return id.name();
    }

    /**
     * Resolves {@code "<a>..<b>"}, or a bare ref meaning {@code "<ref>..HEAD"} — the same grammar
     * {@code ChangeSet.compute} accepts, so a range written for {@code sdd plan --since} means the
     * same thing here.
     */
    public static Range range(Path repo, String rev) {
        try (Git git = Git.open(repo.toFile())) {
            return rangeIn(git.getRepository(), rev);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("cannot open git repo " + repo + ": " + e.getMessage(), e);
        }
    }

    private static Range rangeIn(Repository repository, String rev) {
        String wanted = rev == null || rev.isBlank() ? "HEAD" : rev.strip();
        int dots = wanted.indexOf("..");
        if (dots < 0) {
            return new Range(resolveIn(repository, wanted), resolveIn(repository, "HEAD"));
        }
        String from = wanted.substring(0, dots).strip();
        String to = wanted.substring(dots + 2).strip();
        // "a.." and "..b" are legal git, meaning HEAD on the empty side.
        return new Range(resolveIn(repository, from.isEmpty() ? "HEAD" : from),
                resolveIn(repository, to.isEmpty() ? "HEAD" : to));
    }

    /**
     * Commits in {@code rev}, newest first.
     *
     * <p>A range yields what {@code git log a..b} yields — reachable from {@code b}, not from
     * {@code a}. A bare ref yields that ref's own history, NOT {@code ref..HEAD}: asking for the
     * log of a branch and being handed the commits it is missing would be a different question
     * than the one asked. Use {@link #range} when the two-ended reading is what is wanted.
     *
     * @param path  restrict to commits touching this repo-relative path, or null for all
     * @param limit maximum commits returned; must be positive
     */
    public static List<Commit> log(Path repo, String rev, String path, int limit) {
        try (Git git = Git.open(repo.toFile())) {
            Repository repository = git.getRepository();
            String wanted = rev == null || rev.isBlank() ? "HEAD" : rev.strip();
            var command = git.log();
            int dots = wanted.indexOf("..");
            if (dots < 0) {
                command.add(ObjectId.fromString(resolveIn(repository, wanted)));
            } else {
                Range r = rangeIn(repository, wanted);
                command.addRange(ObjectId.fromString(r.fromSha()), ObjectId.fromString(r.toSha()));
            }
            if (path != null && !path.isBlank()) {
                command.addPath(path.strip());
            }
            command.setMaxCount(Math.max(1, limit));
            List<Commit> out = new ArrayList<>();
            for (RevCommit commit : command.call()) {
                out.add(toCommit(commit));
            }
            return out;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("cannot read log of " + repo + ": " + e.getMessage(), e);
        }
    }

    /** The single commit {@code rev} names, with its metadata. */
    public static Commit commit(Path repo, String rev) {
        try (Git git = Git.open(repo.toFile())) {
            Repository repository = git.getRepository();
            try (RevWalk walk = new RevWalk(repository)) {
                return toCommit(walk.parseCommit(ObjectId.fromString(resolveIn(repository, rev))));
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("cannot read commit " + rev + " in " + repo + ": "
                    + e.getMessage(), e);
        }
    }

    private static Commit toCommit(RevCommit commit) {
        PersonIdent author = commit.getAuthorIdent();
        String name = author == null ? "unknown" : author.getName();
        Instant when = author == null
                ? Instant.ofEpochSecond(commit.getCommitTime())
                : author.getWhenAsInstant();
        String sha = commit.name();
        return new Commit(sha, sha.substring(0, SHORT_SHA), name, when, commit.getShortMessage());
    }

    /**
     * The parent {@code rev} is a change against — its first parent, or null for a root commit,
     * which {@link #diffFiles} reads as the empty tree. What "show this commit" means.
     */
    public static String parentOf(Path repo, String rev) {
        try (Git git = Git.open(repo.toFile())) {
            Repository repository = git.getRepository();
            try (RevWalk walk = new RevWalk(repository)) {
                RevCommit commit = walk.parseCommit(ObjectId.fromString(resolveIn(repository, rev)));
                return commit.getParentCount() == 0 ? null : commit.getParent(0).name();
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("cannot read parent of " + rev + " in " + repo + ": "
                    + e.getMessage(), e);
        }
    }

    /**
     * Per-path change kinds and line counts between two revisions, with renames detected.
     *
     * <p>Rename detection is what makes "where did this file go" answerable, so it is on for the
     * model-facing reads. It is OFF in {@link #diffFiles(Path, String, String, String, boolean)}'s
     * false case, which is what the orchestrator uses: a rename counted once instead of as an add
     * plus a delete would silently change the file counts in Gate 2's report.md.
     */
    public static List<FileChange> diffFiles(Path repo, String fromRev, String toRev, String path) {
        return diffFiles(repo, fromRev, toRev, path, true);
    }

    /** @param detectRenames see {@link #diffFiles(Path, String, String, String)} */
    public static List<FileChange> diffFiles(Path repo, String fromRev, String toRev, String path,
                                             boolean detectRenames) {
        try (Git git = Git.open(repo.toFile());
             ByteArrayOutputStream sink = new ByteArrayOutputStream()) {
            Repository repository = git.getRepository();
            try (RevWalk walk = new RevWalk(repository);
                 DiffFormatter formatter = new DiffFormatter(sink)) {
                List<DiffEntry> entries =
                        scan(repository, walk, formatter, fromRev, toRev, path, detectRenames);
                List<FileChange> out = new ArrayList<>();
                for (DiffEntry entry : entries) {
                    int insertions = 0;
                    int deletions = 0;
                    for (var edit : formatter.toFileHeader(entry).toEditList()) {
                        insertions += edit.getEndB() - edit.getBeginB();
                        deletions += edit.getEndA() - edit.getBeginA();
                    }
                    boolean deleted = entry.getChangeType() == DiffEntry.ChangeType.DELETE;
                    boolean renamed = entry.getChangeType() == DiffEntry.ChangeType.RENAME
                            || entry.getChangeType() == DiffEntry.ChangeType.COPY;
                    out.add(new FileChange(deleted ? entry.getOldPath() : entry.getNewPath(),
                            entry.getChangeType().name(), renamed ? entry.getOldPath() : null,
                            insertions, deletions));
                }
                return out;
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("cannot diff " + repo + " " + fromRev + ".." + toRev
                    + ": " + e.getMessage(), e);
        }
    }

    /** Unified diff between two revisions, renames detected; empty when the trees are identical. */
    public static String diffText(Path repo, String fromRev, String toRev, String path) {
        return diffText(repo, fromRev, toRev, path, true);
    }

    /** @param detectRenames see {@link #diffFiles(Path, String, String, String)} */
    public static String diffText(Path repo, String fromRev, String toRev, String path,
                                  boolean detectRenames) {
        try (Git git = Git.open(repo.toFile());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Repository repository = git.getRepository();
            try (RevWalk walk = new RevWalk(repository);
                 DiffFormatter formatter = new DiffFormatter(out)) {
                formatter.format(
                        scan(repository, walk, formatter, fromRev, toRev, path, detectRenames));
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("cannot diff " + repo + " " + fromRev + ".." + toRev
                    + ": " + e.getMessage(), e);
        }
    }

    /**
     * @param fromRev null means the empty tree — the shape a root commit is shown against
     */
    private static List<DiffEntry> scan(Repository repository, RevWalk walk, DiffFormatter formatter,
                                        String fromRev, String toRev, String path,
                                        boolean detectRenames)
            throws java.io.IOException {
        formatter.setRepository(repository);
        // JGit disables its own rename detector past its rename limit rather than running away,
        // so this stays bounded even on a large diff.
        formatter.setDetectRenames(detectRenames);
        if (path != null && !path.isBlank()) {
            formatter.setPathFilter(PathFilter.create(path.strip()));
        }
        return formatter.scan(tree(repository, walk, fromRev), tree(repository, walk, toRev));
    }

    private static CanonicalTreeParser tree(Repository repository, RevWalk walk, String rev)
            throws java.io.IOException {
        CanonicalTreeParser parser = new CanonicalTreeParser();
        if (rev == null) {
            return parser;   // an empty parser IS the empty tree
        }
        parser.reset(walk.getObjectReader(),
                walk.parseCommit(ObjectId.fromString(resolveIn(repository, rev))).getTree());
        return parser;
    }

    /**
     * Who last touched each line of {@code path} in the window {@code [fromLine, toLine]}, 1-based
     * and inclusive, clamped to the file rather than throwing.
     *
     * <p>The window is mandatory at the tool layer for a reason: this is the one read here whose
     * cost is proportional to history rather than to the answer, and JGit's blame has no deadline
     * of its own.
     */
    public static List<BlameLine> blame(Path repo, String path, String rev, int fromLine, int toLine) {
        try (Git git = Git.open(repo.toFile())) {
            Repository repository = git.getRepository();
            BlameResult result = git.blame()
                    .setFilePath(path)
                    .setStartCommit(ObjectId.fromString(resolveIn(repository, rev)))
                    .call();
            if (result == null) {
                throw new IllegalStateException("no such path at that revision: '" + path + "'");
            }
            int total = result.getResultContents().size();
            List<BlameLine> out = new ArrayList<>();
            for (int i = Math.max(1, fromLine); i <= Math.min(total, toLine); i++) {
                RevCommit source = result.getSourceCommit(i - 1);
                PersonIdent author = result.getSourceAuthor(i - 1);
                String sha = source == null
                        ? "?".repeat(SHORT_SHA) : source.name().substring(0, SHORT_SHA);
                out.add(new BlameLine(sha,
                        author == null ? "unknown" : author.getName(),
                        author == null ? Instant.EPOCH : author.getWhenAsInstant(),
                        i, result.getResultContents().getString(i - 1)));
            }
            return out;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("cannot blame " + path + " in " + repo + ": "
                    + e.getMessage(), e);
        }
    }

    /** How many lines {@code path} has at {@code rev} — what bounds a blame window. */
    public static int lineCount(Path repo, String path, String rev) {
        try (Git git = Git.open(repo.toFile())) {
            Repository repository = git.getRepository();
            BlameResult result = git.blame()
                    .setFilePath(path)
                    .setStartCommit(ObjectId.fromString(resolveIn(repository, rev)))
                    .call();
            return result == null ? 0 : result.getResultContents().size();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + path + " in " + repo + ": "
                    + e.getMessage(), e);
        }
    }

    /** Local branch names, then tag names — both short, in JGit's own ref order. */
    public static List<String> refs(Path repo) {
        try (Git git = Git.open(repo.toFile())) {
            List<String> out = new ArrayList<>();
            for (Ref ref : git.branchList().call()) {
                out.add(Repository.shortenRefName(ref.getName()));
            }
            for (Ref ref : git.tagList().call()) {
                out.add(Repository.shortenRefName(ref.getName()));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("cannot list refs of " + repo + ": " + e.getMessage(), e);
        }
    }

    /** How many commits {@code toRev} is ahead of {@code fromRev}. */
    public static int commitsBetween(Path repo, String fromRev, String toRev) {
        try (Git git = Git.open(repo.toFile())) {
            Repository repository = git.getRepository();
            int n = 0;
            for (var ignored : git.log().addRange(ObjectId.fromString(resolveIn(repository, fromRev)),
                    ObjectId.fromString(resolveIn(repository, toRev))).call()) {
                n++;
            }
            return n;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("cannot count commits in " + repo + ": "
                    + e.getMessage(), e);
        }
    }

    /** Whether {@code ancestorRev} is an ancestor of, or equal to, {@code descendantRev}. */
    public static boolean isAncestor(Path repo, String ancestorRev, String descendantRev) {
        try (Git git = Git.open(repo.toFile())) {
            Repository repository = git.getRepository();
            try (RevWalk walk = new RevWalk(repository)) {
                RevCommit ancestor =
                        walk.parseCommit(ObjectId.fromString(resolveIn(repository, ancestorRev)));
                RevCommit descendant =
                        walk.parseCommit(ObjectId.fromString(resolveIn(repository, descendantRev)));
                return walk.isMergedInto(ancestor, descendant);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("cannot check ancestry of " + ancestorRev + " and "
                    + descendantRev + " in " + repo + ": " + e.getMessage(), e);
        }
    }
}
