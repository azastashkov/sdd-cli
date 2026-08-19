package sdd.index;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import sdd.core.config.ConfigLoader;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.index.extract.BuildModel;
import sdd.index.extract.GradleBuildExtractor;
import sdd.index.scan.RepoScan;
import sdd.index.scan.WorkspaceScanner;
import sdd.index.source.SourceExtraction;
import sdd.index.store.IndexPersistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Measurement harness, NOT a test of behaviour: where does {@code sdd index} actually spend its
 * time?
 *
 * <p>The index-thinning plan rests on one cost claim — "that would cut most of the 40 s indexing
 * cost, since source parsing is the bulk of it". Nothing has ever measured that, and it is the
 * whole payoff: the tables proposed for removal are exactly the ones source parsing produces, so if
 * the Gradle Tooling API is where the time goes instead, thinning buys close to nothing and the
 * only thing left on the table is risk.
 *
 * <p>It times the same two stages {@code IndexService.indexRepo} runs, through the same entry
 * points, per repo. Zero model calls; no production code exists for it.
 *
 * <p>Run: {@code SDD_MEASURE_WS=<probe> SDD_MEASURE_OUT=<dir> gradle :sdd-index:test
 * --tests '*IndexCostHarness' --rerun-tasks}
 */
@Tag("measure")
@EnabledIfEnvironmentVariable(named = "SDD_MEASURE_WS", matches = ".+")
class IndexCostHarness {

    @Test
    void splitBuildFromSourceCost() throws IOException {
        Path ws = Path.of(System.getenv("SDD_MEASURE_WS"));
        Path out = Path.of(System.getenv("SDD_MEASURE_OUT"));
        Files.createDirectories(out);
        SddConfig config = ConfigLoader.load(ws);

        StringBuilder report = new StringBuilder(
                "# Index cost split — Gradle Tooling API vs source parsing\n\n"
                        + "| repo | build extract (ms) | source parse (ms) | source share |\n"
                        + "|---|---|---|---|\n");
        long totalBuild = 0;
        long totalSource = 0;
        try (Database db = Database.open(ws)) {
            List<RepoScan> scans = WorkspaceScanner.scan(config.workspace(),
                    config.excludes());
            GradleBuildExtractor extractor = new GradleBuildExtractor(config.jdkHomes());
            for (RepoScan scan : scans) {
                if (!extractor.detects(scan.path())) {
                    continue;
                }
                long t0 = System.nanoTime();
                BuildModel.Extract extract = extractor.extract(scan.path());
                long buildMs = (System.nanoTime() - t0) / 1_000_000;

                IndexPersistence.persistRepo(db.jdbi(), scan, extract, extractor.buildSystem(),
                        "OK", null);
                long repoId = db.jdbi().withHandle(h -> h
                        .createQuery("SELECT id FROM repo WHERE name=:n")
                        .bind("n", scan.name()).mapTo(Long.class).one());

                long t1 = System.nanoTime();
                SourceExtraction.extractRepo(db.jdbi(), repoId, scan.name(), scan.path(), extract);
                long sourceMs = (System.nanoTime() - t1) / 1_000_000;

                totalBuild += buildMs;
                totalSource += sourceMs;
                report.append("| ").append(scan.name()).append(" | ").append(buildMs)
                        .append(" | ").append(sourceMs).append(" | ")
                        .append(share(sourceMs, buildMs + sourceMs)).append(" |\n");
            }
        }
        report.append("| **total** | **").append(totalBuild).append("** | **").append(totalSource)
                .append("** | **").append(share(totalSource, totalBuild + totalSource))
                .append("** |\n");
        Files.writeString(out.resolve("index-cost.md"), report.toString());
        System.out.println(report);
    }

    private static String share(long part, long whole) {
        return whole == 0 ? "n/a" : Math.round(part * 100.0 / whole) + "%";
    }
}
