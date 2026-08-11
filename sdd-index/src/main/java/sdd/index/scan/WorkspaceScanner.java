package sdd.index.scan;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

public final class WorkspaceScanner {
    private WorkspaceScanner() {}

    public static List<RepoScan> scan(Path workspace, List<String> excludes) {
        return scan(workspace, excludes, new ArrayList<>());
    }

    /**
     * Scans every git repo directly under {@code workspace}. A repo whose git metadata cannot be
     * read is skipped rather than aborting the whole scan; {@code failuresOut} receives one
     * {@code "name: error"} entry per skipped repo so callers can report it.
     */
    public static List<RepoScan> scan(Path workspace, List<String> excludes, List<String> failuresOut) {
        List<RepoScan> result = new ArrayList<>();
        try (Stream<Path> children = Files.list(workspace)) {
            children.filter(Files::isDirectory)
                    .filter(dir -> Files.exists(dir.resolve(".git")))
                    .filter(dir -> !excludes.contains(dir.getFileName().toString()))
                    .sorted()
                    .forEach(dir -> {
                        try {
                            result.add(scanRepo(dir));
                        } catch (RuntimeException e) {
                            failuresOut.add(dir.getFileName() + ": " + describe(e));
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        return e.getCause() == null ? message : message + " (" + e.getCause() + ")";
    }

    private static RepoScan scanRepo(Path dir) {
        try (Git git = Git.open(dir.toFile())) {
            Repository repo = git.getRepository();
            ObjectId head = repo.resolve("HEAD");
            String headSha = head == null ? "" : head.name();
            String branch = repo.getBranch();
            boolean clean = git.status().call().isClean();
            String dirtyHash = clean || head == null ? "" : hashWorkingTreeDiff(repo, head);
            return new RepoScan(dir.getFileName().toString(), dir, headSha, branch, dirtyHash);
        } catch (Exception e) {
            throw new IllegalStateException("cannot scan git repo " + dir, e);
        }
    }

    private static String hashWorkingTreeDiff(Repository repo, ObjectId head) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DiffFormatter fmt = new DiffFormatter(out); RevWalk walk = new RevWalk(repo)) {
            fmt.setRepository(repo);
            CanonicalTreeParser treeParser = new CanonicalTreeParser();
            treeParser.reset(walk.getObjectReader(), walk.parseCommit(head).getTree());
            AbstractTreeIterator workingTree = new FileTreeIterator(repo);
            fmt.format(fmt.scan(treeParser, workingTree));
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(out.toByteArray()));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
