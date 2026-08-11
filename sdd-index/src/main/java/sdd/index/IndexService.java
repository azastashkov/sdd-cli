package sdd.index;

import org.jdbi.v3.core.Jdbi;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.index.gradle.ExtractionException;
import sdd.index.gradle.GradleExtractor;
import sdd.index.gradle.GradleModel;
import sdd.index.gradle.StaticGradleParser;
import sdd.index.scan.RepoScan;
import sdd.index.scan.WorkspaceScanner;
import sdd.index.store.ArtifactLinker;
import sdd.index.store.IndexPersistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class IndexService {
    public record RepoResult(String repo, String status, int modules, int internalDeps,
                             boolean skipped, String error) {}

    private ArtifactLinker.LinkReport lastLinkReport;

    public List<RepoResult> run(SddConfig config, Database db) {
        List<RepoScan> scans = WorkspaceScanner.scan(config.workspace(), config.excludes());
        GradleExtractor extractor = new GradleExtractor(config.jdkHomes());
        List<RepoResult> results = new ArrayList<>();
        for (RepoScan scan : scans) {
            results.add(indexRepo(db.jdbi(), extractor, scan));
        }
        lastLinkReport = ArtifactLinker.link(db.jdbi(), config.artifactOverrides());
        return results.stream().map(r -> withCounts(db.jdbi(), r)).toList();
    }

    public ArtifactLinker.LinkReport lastLinkReport() {
        return lastLinkReport;
    }

    private RepoResult indexRepo(Jdbi jdbi, GradleExtractor extractor, RepoScan scan) {
        Optional<String> stored = jdbi.withHandle(h -> h.createQuery(
                        "SELECT head_commit || ':' || dirty_hash FROM repo WHERE name=:n AND gradle_status='OK'")
                .bind("n", scan.name()).mapTo(String.class).findOne());
        if (stored.isPresent() && stored.get().equals(scan.fingerprint())) {
            return new RepoResult(scan.name(), "OK", 0, 0, true, null);
        }
        try {
            GradleModel.Extract extract = extractor.extract(scan.path());
            IndexPersistence.persistRepo(jdbi, scan, extract, "OK", null);
            return new RepoResult(scan.name(), "OK", extract.projects().size(), 0, false, null);
        } catch (ExtractionException gradleFailure) {
            try {
                GradleModel.Extract fallback = StaticGradleParser.parse(scan.path());
                IndexPersistence.persistRepo(jdbi, scan, fallback, "DEGRADED", gradleFailure.getMessage());
                return new RepoResult(scan.name(), "DEGRADED", fallback.projects().size(), 0, false,
                        gradleFailure.getMessage());
            } catch (RuntimeException fallbackFailure) {
                boolean hasRows = jdbi.withHandle(h -> h.createQuery(
                                "SELECT count(*) FROM repo WHERE name=:n").bind("n", scan.name())
                        .mapTo(Integer.class).one()) > 0;
                if (hasRows) {
                    IndexPersistence.markStale(jdbi, scan.name(), fallbackFailure.getMessage());
                    return new RepoResult(scan.name(), "STALE_OK", 0, 0, false, fallbackFailure.getMessage());
                }
                IndexPersistence.persistRepo(jdbi, scan,
                        new GradleModel.Extract(List.of(), List.of()), "FAILED", fallbackFailure.getMessage());
                return new RepoResult(scan.name(), "FAILED", 0, 0, false, fallbackFailure.getMessage());
            }
        }
    }

    private RepoResult withCounts(Jdbi jdbi, RepoResult r) {
        int modules = jdbi.withHandle(h -> h.createQuery("""
                        SELECT count(*) FROM module m JOIN repo rp ON rp.id=m.repo_id WHERE rp.name=:n""")
                .bind("n", r.repo()).mapTo(Integer.class).one());
        int internal = jdbi.withHandle(h -> h.createQuery("""
                        SELECT count(*) FROM dep_edge e JOIN module m ON m.id=e.from_module_id
                        JOIN repo rp ON rp.id=m.repo_id WHERE rp.name=:n AND e.is_internal=1""")
                .bind("n", r.repo()).mapTo(Integer.class).one());
        return new RepoResult(r.repo(), r.status(), modules, internal, r.skipped(), r.error());
    }
}
