package sdd.plan.gen;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ModelException;
import sdd.core.llm.Usage;
import sdd.core.retrieve.FtsRetriever;
import sdd.core.testing.ScriptedChatModel;
import sdd.plan.impact.Closure;
import sdd.plan.impact.ImpactAnalysis;
import sdd.plan.impact.ImpactResult;
import sdd.plan.impact.Seed;
import sdd.plan.impact.SeedFinder;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;
import sdd.plan.spec.SpecParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Measurement harness for the plan-generation fact diagnosis, NOT a test of behaviour.
 *
 * <p>It lives in {@code sdd.plan.gen} for one reason: {@link PlanDrafter#composeInput} is
 * package-private, and calling it directly is how this project already inspects a composed prompt
 * ({@code PlanDrafterTest}). That door removes any need for a production dump flag.
 *
 * <p>Zero model calls. Each spec is run twice:
 * <ul>
 *   <li><b>deterministic</b> — the seeding model is unavailable, so the output is exactly what the
 *       deterministic half produces on its own: touchpoint seeds, FTS candidates, problems, closure.
 *   <li><b>declared</b> — a scripted model returns the repos the author declared in the sidecar
 *       {@code <spec>.expect} file, i.e. a hypothetically perfect impact analysis. What the drafter
 *       prompt does and does not contain under that condition isolates evidence starvation from
 *       seeding failure.
 * </ul>
 *
 * <p>Run: {@code ./gradlew :sdd-plan:test --tests '*PlanFactsHarness' -Dsdd.measure.ws=<probe>
 * -Dsdd.measure.specs=<dir> -Dsdd.measure.out=<dir>}
 */
@Tag("measure")
@EnabledIfEnvironmentVariable(named = "SDD_MEASURE_WS", matches = ".+")
class PlanFactsHarness {

    private static final int MAX_TOKENS = 32768;

    @Test
    void dumpFacts() throws IOException {
        Path ws = Path.of(System.getenv("SDD_MEASURE_WS"));
        Path specsDir = Path.of(System.getenv("SDD_MEASURE_SPECS"));
        Path out = Path.of(System.getenv("SDD_MEASURE_OUT"));
        Files.createDirectories(out);

        List<Path> specs;
        try (var s = Files.list(specsDir)) {
            specs = s.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList();
        }

        Database db = Database.open(ws);
        StringBuilder report = new StringBuilder("# Plan fact diagnosis — raw run\n");
        for (Path specFile : specs) {
            String name = specFile.getFileName().toString().replace(".md", "");
            NormalizedSpec spec = SpecParser.parse(Files.readString(specFile));
            List<String> declared = declaredRepos(specFile);

            report.append("\n## ").append(name).append('\n');
            report.append("declared expected repos: ").append(declared).append('\n');

            // ---- deterministic half, on its own -------------------------------------------
            SeedFinder.SeedScan scan = SeedFinder.find(db.jdbi(), new FtsRetriever(db.jdbi()), spec);
            report.append("\n### Deterministic seeding\n");
            report.append("seeds:      ").append(fmt(scan.seeds())).append('\n');
            report.append("candidates: ").append(fmt(scan.candidates())).append('\n');
            report.append("problems:   ").append(scan.problems()).append('\n');

            emit(db, spec, declared, name, "deterministic", unavailableModel(), out, report);
            emit(db, spec, declared, name, "declared", declaringModel(declared, spec), out, report);
            changeSet(db, specFile, report);
            annotations(db, specFile, report);
        }
        Files.writeString(out.resolve("report.md"), report.toString());
        System.out.println("wrote " + out.resolve("report.md"));
    }

    private void emit(Database db, NormalizedSpec spec, List<String> declared, String name,
                      String mode, ChatModel model, Path out, StringBuilder report)
            throws IOException {
        ImpactResult result = ImpactAnalysis.analyze(db.jdbi(), new FtsRetriever(db.jdbi()), spec,
                model, "measure", MAX_TOKENS);
        List<ExecutionOrder.Unit> order = ExecutionOrder.order(db.jdbi(), result);
        List<Question> questions = OpenQuestions.detect(db.jdbi(), result);
        String prompt = PlanDrafter.composeInput(db.jdbi(), spec, result, order, "");
        Files.writeString(out.resolve(name + "." + mode + ".prompt.txt"), prompt);

        Set<String> affected = result.affected().stream()
                .map(a -> a.repo()).collect(Collectors.toCollection(LinkedHashSet::new));

        report.append("\n### ").append(mode).append('\n');
        report.append("affected: ").append(affected).append('\n');
        result.affected().forEach(a ->
                report.append("  - ").append(a.repo()).append(" | ").append(a.role())
                        .append(" | ").append(a.annotation()).append('\n'));
        report.append("blocking questions: ")
                .append(questions.stream().filter(Question::blocking).map(Question::text).toList())
                .append('\n');
        report.append("non-blocking:       ")
                .append(questions.stream().filter(q -> !q.blocking()).map(Question::text).toList())
                .append('\n');
        // The declaration is compared, never merged — this is the A0 diff, computed here only to
        // measure it. Nothing about it feeds back into `affected`.
        report.append("expected-but-not-reached: ")
                .append(declared.stream().filter(r -> !affected.contains(r)).toList()).append('\n');
        report.append("reached-but-not-expected: ")
                .append(affected.stream().filter(r -> !declared.contains(r)).toList()).append('\n');
        report.append("prompt chars: ").append(prompt.length()).append('\n');
        for (String repo : affected) {
            report.append("  evidence[").append(repo).append("] chars: ")
                    .append(sectionLength(prompt, repo))
                    .append(sectionLength(prompt, repo) == 0 ? "  (NO SECTION)" : "")
                    .append(prompt.contains("…(truncated)") ? "" : "").append('\n');
        }
    }

    /**
     * The change-set half: what a {@code --since} seed source would contribute, and what the
     * deterministic closure would then produce from it. Driven by a sidecar {@code <spec>.since}
     * holding {@code <repo>=<range>} lines; specs without one are skipped.
     *
     * <p>Seeded through {@link Closure#expand} directly rather than through {@code ImpactAnalysis},
     * because the point is to measure the deterministic reach of git-derived seeds with no model in
     * the loop at all.
     */
    private void changeSet(Database db, Path specFile, StringBuilder report) throws IOException {
        Path sinceFile = specFile.resolveSibling(
                specFile.getFileName().toString().replace(".md", ".since"));
        if (!Files.exists(sinceFile)) return;

        Map<String, String> ranges = new LinkedHashMap<>();
        for (String line : Files.readAllLines(sinceFile)) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#") || !l.contains("=")) continue;
            ranges.put(l.substring(0, l.indexOf('=')).trim(), l.substring(l.indexOf('=') + 1).trim());
        }
        List<ChangeSetProbe.RepoChange> changes = ChangeSetProbe.compute(db.jdbi(), ranges);

        report.append("\n### change set (prototype)\n");
        for (ChangeSetProbe.RepoChange c : changes) {
            report.append(c.repo()).append(' ').append(c.range()).append(" -> ")
                    .append(c.resolution()).append(' ')
                    .append(c.fromSha()).append("..").append(c.toSha()).append('\n');
            c.files().forEach(f -> report.append("  ").append(f.changeKind().charAt(0)).append(' ')
                    .append(f.path()).append(f.fqcn() == null ? "   (no indexed type)"
                            : "   -> " + f.fqcn() + (f.isApi() ? " [api]" : "")).append('\n'));
        }
        report.append("would-be seeds:\n").append(ChangeSetProbe.seedLines(changes));

        Set<String> roots = changes.stream().filter(c -> !c.mapped().isEmpty())
                .map(ChangeSetProbe.RepoChange::repo)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Closure.Expansion expansion = Closure.expand(db.jdbi(), roots);
        report.append("closure from git seeds: roots=").append(roots).append('\n');
        expansion.added().forEach(a -> report.append("  + ").append(a.repo()).append(" | ")
                .append(a.role()).append(" | ").append(a.annotation()).append('\n'));
    }

    /**
     * Case 4: how accurate is the CODE_CHANGE_LIKELY / BUMP_REBUILD_ONLY annotation, and would a
     * cheaper rule than member-level usage fix it? Driven by a sidecar {@code <spec>.annotate} with
     * {@code provider=}, {@code changed=} (comma-separated fqcns) and {@code truth.code=} lines;
     * every other affected repo is ground-truth rebuild-only.
     */
    private void annotations(Database db, Path specFile, StringBuilder report) throws IOException {
        Path file = specFile.resolveSibling(
                specFile.getFileName().toString().replace(".md", ".annotate"));
        if (!Files.exists(file)) return;

        Map<String, String> cfg = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file)) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#") || !l.contains("=")) continue;
            cfg.put(l.substring(0, l.indexOf('=')).trim(), l.substring(l.indexOf('=') + 1).trim());
        }
        String provider = cfg.get("provider");
        Set<String> changed = split(cfg.get("changed"));
        Set<String> truthCode = split(cfg.get("truth.code"));

        // The consumers under test are exactly what the closure reaches from the provider — the
        // repos the annotation has to classify.
        Closure.Expansion expansion = Closure.expand(db.jdbi(), Set.of(provider));
        Set<String> consumers = expansion.added().stream().map(a -> a.repo())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        report.append("\n### annotation accuracy (case 4)\n");
        report.append("provider: ").append(provider).append('\n');
        report.append("changed types: ").append(changed).append('\n');
        report.append("consumers reached by closure: ").append(consumers).append('\n');
        report.append("ground truth needing code change: ")
                .append(truthCode.stream().filter(consumers::contains).toList()).append('\n');

        List<AnnotationProbe.Verdict> verdicts = List.of(
                AnnotationProbe.today(db.jdbi(), provider, consumers),
                AnnotationProbe.typeFiltered(db.jdbi(), changed, consumers),
                AnnotationProbe.kindAware(db.jdbi(), changed, consumers));
        for (AnnotationProbe.Verdict v : verdicts) {
            AnnotationProbe.Scored s = AnnotationProbe.score(v, truthCode);
            report.append("\n  rule: ").append(s.rule()).append('\n');
            v.byRepo().forEach((r, a) -> report.append("    ").append(r).append(" -> ")
                    .append(a).append('\n'));
            report.append("    correct ").append(s.correct()).append('/')
                    .append(v.byRepo().size())
                    .append(", false CODE_CHANGE ").append(s.falseCodeChange())
                    .append(", false REBUILD_ONLY ").append(s.falseRebuild()).append('\n');
            s.mistakes().forEach(m -> report.append("      x ").append(m).append('\n'));
        }
    }

    private static Set<String> split(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return java.util.Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Characters between "## &lt;repo&gt;" in the evidence block and the next "## ". */
    private static int sectionLength(String prompt, String repo) {
        int i = prompt.indexOf("\n## " + repo + "\n");
        if (i < 0) return 0;
        int j = prompt.indexOf("\n## ", i + 1);
        return (j < 0 ? prompt.length() : j) - i;
    }

    private static List<String> declaredRepos(Path specFile) throws IOException {
        Path expect = specFile.resolveSibling(
                specFile.getFileName().toString().replace(".md", ".expect"));
        if (!Files.exists(expect)) return List.of();
        return Files.readAllLines(expect).stream().map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
    }

    private static String fmt(List<Seed> seeds) {
        return seeds.stream().map(s -> s.repo() + "(" + s.source() + ": " + s.detail() + ")")
                .collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * Drives ModelSeeder's documented degradation path: unavailable, deterministic-only.
     *
     * <p>It must throw {@link ModelException} specifically — {@code ModelSeeder.seed} catches only
     * that type, so any other runtime exception from a model client propagates and aborts the whole
     * command instead of degrading. Noted here because that is a real narrowness in the production
     * catch, not a quirk of this harness.
     */
    private static ChatModel unavailableModel() {
        return req -> { throw new ModelException("measurement: model deliberately unavailable", 503); };
    }

    /**
     * A hypothetically perfect seeder that returns exactly what the author declared.
     *
     * <p>{@code covers} must list every requirement id: {@code ImpactAnalysis} raises a
     * {@code "no repo covers R<n>"} <em>problem</em> — and therefore a blocking question — for any
     * requirement no model seed claims. Leaving it empty makes every run look blocked for a reason
     * that belongs to the harness, not to the pipeline.
     */
    private static ChatModel declaringModel(List<String> declared, NormalizedSpec spec) {
        String covers = spec.requirements().stream().map(SpecItem::id)
                .map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
        List<String> entries = new ArrayList<>();
        for (String repo : declared) {
            entries.add("{\"repo\":\"" + repo + "\",\"role\":\"primary\",\"covers\":[" + covers + "],"
                    + "\"reason\":\"declared by the spec author\"}");
        }
        String json = "{\"repos\":[" + String.join(",", entries) + "]}";
        return new ScriptedChatModel(List.of(
                new ChatResponse(ChatMessage.assistant(json), "stop", new Usage(1, 1))));
    }
}
