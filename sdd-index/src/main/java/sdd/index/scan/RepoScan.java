package sdd.index.scan;

import java.nio.file.Path;

public record RepoScan(String name, Path path, String headCommit, String branch, String dirtyHash) {
    public String fingerprint() {
        return headCommit + ":" + dirtyHash;
    }
}
