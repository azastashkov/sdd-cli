package sdd.agent.tool;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import sdd.core.db.Database;
import sdd.core.kb.KbEntities;
import sdd.core.retrieve.FtsRetriever;
import sdd.core.retrieve.Hit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Measurement harness, NOT a test of behaviour: for each term, what does the planner's only
 * free-text route reach, and what does the explorer's search reach?
 *
 * <p>The exploration plan rests on one mechanism claim — free text reaches impact analysis solely
 * through {@code SeedFinder} → {@code FtsRetriever} → {@code fts_symbol}, a corpus of Java type and
 * member NAMES, so a term that is not a Java name cannot be reached at all. That claim is
 * measurable with zero model calls by running both retrievers over the same real estate and
 * comparing which repos each one names.
 *
 * <p>Run: {@code SDD_MEASURE_WS=<probe> SDD_MEASURE_TERMS=<file> SDD_MEASURE_OUT=<dir> \
 * gradle :sdd-agent:test --tests '*EstateReachHarness'}
 */
@Tag("measure")
@EnabledIfEnvironmentVariable(named = "SDD_MEASURE_WS", matches = ".+")
class EstateReachHarness {

    private static double precision(Set<String> got, Set<String> truth) {
        return got.isEmpty() ? 1.0 : got.stream().filter(truth::contains).count() / (double) got.size();
    }

    private static double recall(Set<String> got, Set<String> truth) {
        return truth.stream().filter(got::contains).count() / (double) truth.size();
    }

    private static String pct(double v) {
        return String.valueOf(Math.round(v * 100)) + "%";
    }

    @Test
    void compareReach() throws IOException {
        Path ws = Path.of(System.getenv("SDD_MEASURE_WS"));
        Path out = Path.of(System.getenv("SDD_MEASURE_OUT"));
        Files.createDirectories(out);
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        for (String raw : Files.readAllLines(Path.of(System.getenv("SDD_MEASURE_TERMS")))) {
            String line = raw.strip();
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int bar = line.indexOf('|');
            expected.put(line.substring(0, bar),
                    new LinkedHashSet<>(List.of(line.substring(bar + 1).split(","))));
        }

        StringBuilder report = new StringBuilder("# Estate reach — fts_symbol vs estate search\n");
        try (Database db = Database.open(ws)) {
            Map<String, Path> roots = new LinkedHashMap<>();
            db.jdbi().useHandle(h -> h.createQuery("SELECT name, path FROM repo ORDER BY name")
                    .map((rs, ctx) -> Map.entry(rs.getString("name"), rs.getString("path")))
                    .forEach(e -> roots.put(e.getKey(), ws.resolve(e.getValue()))));
            report.append("\nrepos indexed: ").append(roots.keySet()).append('\n');

            FtsRetriever fts = new FtsRetriever(db.jdbi());
            EstateSearch search = new EstateSearch(new EstateJail(roots));
            StringBuilder table = new StringBuilder(
                    "\n| term | fts repos | P | R | search repos | P | R | cite |\n"
                            + "|---|---|---|---|---|---|---|---|\n");
            for (String term : expected.keySet()) {
                Set<String> truth = expected.get(term);
                report.append("\n## `").append(term).append("`\n");

                List<Hit> hits = fts.search(term, 30);
                Set<String> ftsRepos = new LinkedHashSet<>();
                for (Hit hit : hits) {
                    String repo = KbEntities.repoOfModule(db.jdbi(), hit.moduleId());
                    ftsRepos.add(repo == null ? "?" : repo);
                }
                long docOnly = hits.stream().filter(Hit::docOnly).count();
                report.append("\nfts_symbol: ").append(hits.size()).append(" hits in ")
                        .append(ftsRepos).append("  (").append(docOnly)
                        .append(" javadoc-only)\n");
                for (Hit hit : hits.subList(0, Math.min(5, hits.size()))) {
                    report.append("  - ").append(hit.fqcn())
                            .append(hit.docOnly() ? "  [javadoc only]" : "").append('\n');
                }

                // Literal, not regex: a term like tier.lvc.map must be measured as the string a
                // human would type, not as a pattern whose dots match anything.
                EstateSearch.Result result = search.find(java.util.regex.Pattern.quote(term),
                        null, null);
                Set<String> searchRepos = new LinkedHashSet<>();
                for (String path : result.paths()) {
                    searchRepos.add(path.substring(0, path.indexOf('/')));
                }
                report.append("\nestate search: ").append(result.paths().size())
                        .append(" files in ").append(searchRepos).append('\n');
                for (String path : result.paths().subList(0, Math.min(5, result.paths().size()))) {
                    report.append("  - ").append(path).append('\n');
                }

                // A citation only counts when it points at src/main: a hit in a test tree or a
                // design document is real text, but it is not where the thing is defined.
                String cite = result.paths().stream()
                        .filter(p -> p.contains("/src/main/")).findFirst().orElse("(none)");
                table.append("| `").append(term).append("` | ").append(ftsRepos.size())
                        .append(' ').append(ftsRepos).append(" | ").append(pct(precision(ftsRepos, truth)))
                        .append(" | ").append(pct(recall(ftsRepos, truth)))
                        .append(" | ").append(searchRepos.size()).append(' ').append(searchRepos)
                        .append(" | ").append(pct(precision(searchRepos, truth)))
                        .append(" | ").append(pct(recall(searchRepos, truth)))
                        .append(" | ").append(cite.equals("(none)") ? "no" : "`" + cite + "`")
                        .append(" |\n");
            }
            report.append(table);
        }
        Files.writeString(out.resolve("reach.md"), report.toString());
        System.out.println("wrote " + out.resolve("reach.md"));
    }
}
