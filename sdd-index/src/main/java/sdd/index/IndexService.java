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
import sdd.index.source.SourceExtraction;
import sdd.index.store.ArtifactLinker;
import sdd.index.store.IndexPersistence;
import sdd.index.store.RestMatcher;
import sdd.index.store.SourcePersistence;
import sdd.index.store.TopicJanitor;
import sdd.index.store.UsageLinker;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class IndexService {
    public record RepoResult(String repo, String status, String parseStatus, int modules,
                             int internalDeps, boolean skipped, String error) {}

    /** Seam over {@link GradleExtractor#extract} so failure handling is testable without Gradle. */
    @FunctionalInterface
    interface Extractor {
        GradleModel.Extract extract(java.nio.file.Path repoDir);
    }

    private final Extractor injectedExtractor;

    private ArtifactLinker.LinkReport lastLinkReport;
    private UsageLinker.Report lastUsageReport;
    private RestMatcher.Report lastRestReport;
    private int lastTopicsCleaned;

    public IndexService() {
        this(null);
    }

    /** Seam so tests can inject a stub {@link Extractor} without shelling out to Gradle. */
    IndexService(Extractor extractor) {
        this.injectedExtractor = extractor;
    }

    public List<RepoResult> run(SddConfig config, Database db) {
        List<String> scanFailures = new ArrayList<>();
        List<RepoScan> scans = WorkspaceScanner.scan(config.workspace(), config.excludes(), scanFailures);
        Extractor extractor = injectedExtractor != null
                ? injectedExtractor
                : new GradleExtractor(config.jdkHomes())::extract;
        List<RepoResult> results = new ArrayList<>();
        for (RepoScan scan : scans) {
            results.add(indexRepo(db.jdbi(), extractor, scan));
        }
        for (String failure : scanFailures) {
            results.add(scanFailureResult(db.jdbi(), config.workspace(), failure));
        }
        lastLinkReport = ArtifactLinker.link(db.jdbi(), config.artifactOverrides());
        lastUsageReport = UsageLinker.link(db.jdbi());
        lastRestReport = RestMatcher.match(db.jdbi(), config.manualEdges());
        lastTopicsCleaned = TopicJanitor.clean(db.jdbi());
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

    public UsageLinker.Report lastUsageReport() {
        return lastUsageReport;
    }

    public RestMatcher.Report lastRestReport() {
        return lastRestReport;
    }

    public int lastTopicsCleaned() {
        return lastTopicsCleaned;
    }

    RepoResult indexRepo(Jdbi jdbi, Extractor extractor, RepoScan scan) {
        // A FAILED parse_status must not be treated as "unchanged, skip": with repo-atomic source
        // writes, a failed extraction leaves the previous (pre-failure) data intact, so retrying
        // on the next run is coherent and cheap — unlike a gradle-status skip, nothing was lost.
        // A NULL parse_status is not "parsed fine" either: rows written before source extraction
        // existed have one, and skipping them would leave those repos without source data forever.
        Optional<String> stored = jdbi.withHandle(h -> h.createQuery("""
                        SELECT head_commit || ':' || dirty_hash FROM repo
                        WHERE name=:n AND gradle_status='OK'
                          AND parse_status IS NOT NULL AND parse_status != 'FAILED'""")
                .bind("n", scan.name()).mapTo(String.class).findOne());
        if (stored.isPresent() && stored.get().equals(scan.fingerprint())) {
            return new RepoResult(scan.name(), "OK", null, 0, 0, true, null);
        }
        try {
            GradleModel.Extract extract = extractor.extract(scan.path());
            IndexPersistence.persistRepo(jdbi, scan, extract, "OK", null);
            String parseStatus = runSourceExtraction(jdbi, scan, extract);
            return new RepoResult(scan.name(), "OK", parseStatus, extract.projects().size(), 0, false, null);
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
                return new RepoResult(scan.name(), "STALE_OK", null, 0, 0, false, gradleError);
            }
            IndexPersistence.persistRepo(jdbi, scan, fallback, "DEGRADED", gradleError);
            String parseStatus = runSourceExtraction(jdbi, scan, fallback);
            return new RepoResult(scan.name(), "DEGRADED", parseStatus,
                    fallback.projects().size(), 0, false, gradleError);
        } catch (RuntimeException fallbackFailure) {
            return staleOrFailed(jdbi, scan, describe(fallbackFailure));
        }
    }

    private RepoResult staleOrFailed(Jdbi jdbi, RepoScan scan, String error) {
        if (hasRows(jdbi, scan.name())) {
            IndexPersistence.markStale(jdbi, scan.name(), error);
            return new RepoResult(scan.name(), "STALE_OK", null, 0, 0, false, error);
        }
        IndexPersistence.persistRepo(jdbi, scan,
                new GradleModel.Extract(List.of(), List.of()), "FAILED", error);
        return new RepoResult(scan.name(), "FAILED", "FAILED", 0, 0, false, error);
    }

    /**
     * Runs source extraction for a repo whose gradle model was just persisted (OK or DEGRADED).
     * A failure here — a JavaParser bug, an unreadable jar, anything — must stay confined to this
     * repo's parse_status; it must never propagate and sink the whole indexing run.
     *
     * <p>StackOverflowError is caught alongside RuntimeException because JavaParser's symbol
     * solver recurses on deeply generic or mutually referential types and blows the stack on
     * real-world code. It is recoverable (the stack has already unwound by the time we get here)
     * and it is the one Error this pipeline provokes by itself, so it is named explicitly rather
     * than swallowing every Error — an OutOfMemoryError still ends the run, as it should.
     */
    private String runSourceExtraction(Jdbi jdbi, RepoScan scan, GradleModel.Extract extract) {
        try {
            long repoId = jdbi.withHandle(h -> h.createQuery("SELECT id FROM repo WHERE name=:n")
                    .bind("n", scan.name()).mapTo(Long.class).one());
            return SourceExtraction.extractRepo(jdbi, repoId, scan.name(), scan.path(), extract);
        } catch (RuntimeException | StackOverflowError e) {
            SourcePersistence.updateParseStatus(jdbi, scan.name(), "FAILED",
                    "source extraction failed: " + firstLine(e.getMessage()));
            return "FAILED";
        }
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "unknown error";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
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
        // Re-read from the repo row rather than trust r.parseStatus(): it is the source of truth
        // for skipped repos (which never ran extraction this call) and matches what a freshly
        // extracted repo already wrote. But a repo whose gradle scan itself failed and was never
        // persisted before (persistRepo never touches parse_status) has no stored value yet — fall
        // back to the in-flight result's parseStatus (e.g. the literal "FAILED" staleOrFailed sets)
        // rather than losing it to a stray NULL.
        String stored = jdbi.withHandle(h -> h.createQuery(
                        "SELECT parse_status FROM repo WHERE name=:n")
                .bind("n", r.repo()).mapTo(String.class).findOne().orElse(null));
        String parseStatus = stored != null ? stored : r.parseStatus();
        return new RepoResult(r.repo(), r.status(), parseStatus, modules, internal, r.skipped(), r.error());
    }
}
