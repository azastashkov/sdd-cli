package sdd.index;

import org.jdbi.v3.core.Jdbi;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.core.llm.ChatModel;
import sdd.index.cards.RepoCardGenerator;
import sdd.index.gradle.ExtractionException;
import sdd.index.gradle.GradleExtractor;
import sdd.index.gradle.GradleModel;
import sdd.index.gradle.StaticGradleParser;
import sdd.index.report.CurationReport;
import sdd.index.scan.RepoScan;
import sdd.index.scan.WorkspaceScanner;
import sdd.index.source.SourceExtraction;
import sdd.index.store.ArtifactLinker;
import sdd.index.store.IndexPersistence;
import sdd.index.store.RestMatcher;
import sdd.index.store.SourcePersistence;
import sdd.index.store.TopicJanitor;
import sdd.index.store.UsageLinker;

import java.nio.file.Path;
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
    private final ChatModel cardModel;
    private final String cardModelName;

    private ArtifactLinker.LinkReport lastLinkReport;
    private UsageLinker.Report lastUsageReport;
    private RestMatcher.Report lastRestReport;
    private int lastTopicsCleaned;
    private RepoCardGenerator.CardResult lastCardResult;
    private String lastCardError;
    private Path lastReportPath;

    public IndexService() {
        this(null);
    }

    /** Seam so tests can inject a stub {@link Extractor} without shelling out to Gradle. */
    IndexService(Extractor extractor) {
        this(extractor, null, null);
    }

    /**
     * Full constructor: {@code cardModel} is optional (null skips repo-card generation entirely,
     * e.g. the CLI's {@code --no-cards} escape hatch) and {@code extractor} is optional (null uses
     * the real {@link GradleExtractor}). Public so callers outside this package (the CLI) can wire
     * a real {@link ChatModel} without reaching for the package-private test seam.
     */
    public IndexService(Extractor extractor, ChatModel cardModel, String cardModelName) {
        this.injectedExtractor = extractor;
        this.cardModel = cardModel;
        this.cardModelName = cardModelName;
    }

    public List<RepoResult> run(SddConfig config, Database db) {
        return run(config, db, false);
    }

    /**
     * @param force bypasses only the fingerprint short-circuit in {@link #indexRepo}, so every
     *              scanned repo is re-extracted and re-persisted even when its head/dirty
     *              fingerprint matches the stored row. Everything downstream — per-repo
     *              transactional replace, keep-last-good/STALE_OK fallback, link passes, cards —
     *              is unaffected: cards in particular remain gated by {@link RepoCardGenerator}'s
     *              own content-hash cache, so a forced re-index that reproduces byte-identical
     *              module/endpoint/dep data will not regenerate cards.
     */
    public List<RepoResult> run(SddConfig config, Database db, boolean force) {
        List<String> scanFailures = new ArrayList<>();
        List<RepoScan> scans = WorkspaceScanner.scan(config.workspace(), config.excludes(), scanFailures);
        Extractor extractor = injectedExtractor != null
                ? injectedExtractor
                : new GradleExtractor(config.jdkHomes())::extract;
        List<RepoResult> results = new ArrayList<>();
        for (RepoScan scan : scans) {
            results.add(indexRepo(db.jdbi(), extractor, scan, force));
        }
        for (String failure : scanFailures) {
            results.add(scanFailureResult(db.jdbi(), config.workspace(), failure));
        }
        lastLinkReport = ArtifactLinker.link(db.jdbi(), config.artifactOverrides());
        lastUsageReport = UsageLinker.link(db.jdbi());
        lastRestReport = RestMatcher.match(db.jdbi(), config.manualEdges());
        lastTopicsCleaned = TopicJanitor.clean(db.jdbi());
        lastCardResult = generateCards(db.jdbi(), config.workspace());
        // Always runs, regardless of --no-cards/model availability: it only reads already-persisted
        // tables, so it has no model dependency and nothing above it can legitimately skip it.
        lastReportPath = CurationReport.write(db.jdbi(), config.workspace());
        return results.stream().map(r -> withCounts(db.jdbi(), r)).toList();
    }

    /**
     * Repo cards are a model-touching nicety, not core indexing: a broken/unreachable endpoint,
     * a bug in the generator, anything RuntimeException-shaped must degrade to "no cards this run"
     * rather than sink an otherwise-successful index. Returns null when cards were skipped
     * (no card model configured) or when generation itself blew up.
     */
    private RepoCardGenerator.CardResult generateCards(Jdbi jdbi, Path workspace) {
        lastCardError = null;
        if (cardModel == null) {
            return null;
        }
        try {
            return RepoCardGenerator.generate(jdbi, workspace, cardModel, cardModelName);
        } catch (RuntimeException e) {
            lastCardError = String.valueOf(e);
            return null;
        }
    }

    /** A repo we could not even scan: keep whatever we already know, else record the failure. */
    private RepoResult scanFailureResult(Jdbi jdbi, Path workspace, String failure) {
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

    /** Null when cards were skipped ({@code --no-cards}, no card model) or generation failed. */
    public RepoCardGenerator.CardResult lastCardResult() {
        return lastCardResult;
    }

    /** Null unless card generation crashed; non-null distinguishes a crash from a configured skip. */
    public String lastCardError() {
        return lastCardError;
    }

    public Path lastReportPath() {
        return lastReportPath;
    }

    RepoResult indexRepo(Jdbi jdbi, Extractor extractor, RepoScan scan) {
        return indexRepo(jdbi, extractor, scan, false);
    }

    /** @param force bypasses only the fingerprint short-circuit below; see {@link #run(SddConfig, Database, boolean)}. */
    RepoResult indexRepo(Jdbi jdbi, Extractor extractor, RepoScan scan, boolean force) {
        // A FAILED parse_status must not be treated as "unchanged, skip": with repo-atomic source
        // writes, a failed extraction leaves the previous (pre-failure) data intact, so retrying
        // on the next run is coherent and cheap — unlike a gradle-status skip, nothing was lost.
        // A NULL parse_status is not "parsed fine" either: rows written before source extraction
        // existed have one, and skipping them would leave those repos without source data forever.
        // A NULL build_system is not "unchanged" either: it means the row predates the V4 migration
        // and so has never been through an extractor that records which build system produced it.
        // Without this clause a workspace upgraded to V4 reports "(unchanged, skipped)" for every
        // repo and the new column stays NULL forever on a plain `sdd index` — the same trap the V2
        // and V3 upgrades hit, whose documented remedy was the easily-missed `sdd index --force`.
        // Costing one full re-index on upgrade buys back a workspace that heals itself.
        Optional<String> stored = jdbi.withHandle(h -> h.createQuery("""
                        SELECT head_commit || ':' || dirty_hash FROM repo
                        WHERE name=:n AND gradle_status='OK'
                          AND parse_status IS NOT NULL AND parse_status != 'FAILED'
                          AND build_system IS NOT NULL""")
                .bind("n", scan.name()).mapTo(String.class).findOne());
        if (!force && stored.isPresent() && stored.get().equals(scan.fingerprint())) {
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
