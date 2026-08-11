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

    /** Seam over {@link GradleExtractor#extract} so failure handling is testable without Gradle. */
    @FunctionalInterface
    interface Extractor {
        GradleModel.Extract extract(java.nio.file.Path repoDir);
    }

    private ArtifactLinker.LinkReport lastLinkReport;

    public List<RepoResult> run(SddConfig config, Database db) {
        List<String> scanFailures = new ArrayList<>();
        List<RepoScan> scans = WorkspaceScanner.scan(config.workspace(), config.excludes(), scanFailures);
        GradleExtractor extractor = new GradleExtractor(config.jdkHomes());
        List<RepoResult> results = new ArrayList<>();
        for (RepoScan scan : scans) {
            results.add(indexRepo(db.jdbi(), extractor::extract, scan));
        }
        for (String failure : scanFailures) {
            results.add(scanFailureResult(db.jdbi(), config.workspace(), failure));
        }
        lastLinkReport = ArtifactLinker.link(db.jdbi(), config.artifactOverrides());
        return results.stream().map(r -> withCounts(db.jdbi(), r)).toList();
    }

    /** A repo we could not even scan: keep whatever we already know, else record the failure. */
    private RepoResult scanFailureResult(Jdbi jdbi, java.nio.file.Path workspace, String failure) {
        int sep = failure.indexOf(": ");
        String name = sep < 0 ? failure : failure.substring(0, sep);
        String error = sep < 0 ? failure : failure.substring(sep + 2);
        return staleOrFailed(jdbi, new RepoScan(name, workspace.resolve(name), "", "", ""), error);
    }

    public ArtifactLinker.LinkReport lastLinkReport() {
        return lastLinkReport;
    }

    RepoResult indexRepo(Jdbi jdbi, Extractor extractor, RepoScan scan) {
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
            return degraded(jdbi, scan, describe(gradleFailure));
        } catch (RuntimeException unexpected) {
            // Anything the extractor throws beyond ExtractionException must stay confined to
            // this repo — one bad repo may never sink the whole run.
            return staleOrFailed(jdbi, scan, describe(unexpected));
        }
    }

    private RepoResult degraded(Jdbi jdbi, RepoScan scan, String gradleError) {
        try {
            GradleModel.Extract fallback = StaticGradleParser.parse(scan.path());
            if (fallback.projects().isEmpty() && hasRows(jdbi, scan.name())) {
                // Persisting an empty DEGRADED extract would delete modules and edges we already
                // have. Keep the previous (now stale) picture instead.
                IndexPersistence.markStale(jdbi, scan.name(), gradleError);
                return new RepoResult(scan.name(), "STALE_OK", 0, 0, false, gradleError);
            }
            IndexPersistence.persistRepo(jdbi, scan, fallback, "DEGRADED", gradleError);
            return new RepoResult(scan.name(), "DEGRADED", fallback.projects().size(), 0, false, gradleError);
        } catch (RuntimeException fallbackFailure) {
            return staleOrFailed(jdbi, scan, describe(fallbackFailure));
        }
    }

    private RepoResult staleOrFailed(Jdbi jdbi, RepoScan scan, String error) {
        if (hasRows(jdbi, scan.name())) {
            IndexPersistence.markStale(jdbi, scan.name(), error);
            return new RepoResult(scan.name(), "STALE_OK", 0, 0, false, error);
        }
        IndexPersistence.persistRepo(jdbi, scan,
                new GradleModel.Extract(List.of(), List.of()), "FAILED", error);
        return new RepoResult(scan.name(), "FAILED", 0, 0, false, error);
    }

    private static boolean hasRows(Jdbi jdbi, String repoName) {
        return jdbi.withHandle(h -> h.createQuery("SELECT count(*) FROM repo WHERE name=:n")
                .bind("n", repoName).mapTo(Integer.class).one()) > 0;
    }

    private static String describe(RuntimeException e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
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
