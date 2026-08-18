package sdd.index.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import sdd.core.retrieve.FtsSymbolWriter;
import sdd.index.gradle.CatalogReader;
import sdd.index.extract.BuildModel;
import sdd.index.gradle.ConsumptionMode;
import sdd.index.gradle.ModeClassifier;
import sdd.index.npm.NpmModeClassifier;
import sdd.index.scan.RepoScan;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class IndexPersistence {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IndexPersistence() {}

    /**
     * @param buildSystem which extractor produced {@code extract}, persisted to
     *                    {@code repo.build_system}. Never null: a NULL in that column means "row
     *                    predates the V4 migration", which is what tells {@code IndexService} to
     *                    re-extract rather than trust the fingerprint.
     */
    public static void persistRepo(Jdbi jdbi, RepoScan scan, BuildModel.Extract extract,
                                   String buildSystem, String gradleStatus, String error) {
        Set<String> catalogGAs = CatalogReader.internalGAs(scan.path());
        jdbi.useTransaction(h -> {
            String includedJson;
            try {
                includedJson = MAPPER.writeValueAsString(extract.compositeRoots().stream()
                        .map(Paths2::canonicalString).toList());
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize included builds", e);
            }
            String repoKind = rollupKind(extract);
            h.createUpdate("""
                            INSERT INTO repo(name, path, kind, head_commit, branch, dirty_hash,
                                             included_builds, gradle_status, error, indexed_at,
                                             build_system, extractor_epoch)
                            VALUES (:name, :path, :kind, :head, :branch, :dirty, :included, :status, :error, :at,
                                    :buildSystem, :epoch)
                            ON CONFLICT(name) DO UPDATE SET
                              path=excluded.path, kind=excluded.kind, head_commit=excluded.head_commit,
                              branch=excluded.branch, dirty_hash=excluded.dirty_hash,
                              included_builds=excluded.included_builds, gradle_status=excluded.gradle_status,
                              error=excluded.error, indexed_at=excluded.indexed_at,
                              build_system=excluded.build_system,
                              extractor_epoch=excluded.extractor_epoch""")
                    .bind("name", scan.name()).bind("path", Paths2.canonicalString(scan.path()))
                    .bind("kind", repoKind).bind("head", scan.headCommit())
                    .bind("branch", scan.branch()).bind("dirty", scan.dirtyHash())
                    .bind("included", includedJson).bind("status", gradleStatus)
                    .bind("error", error).bind("at", Instant.now().toString())
                    .bind("buildSystem", java.util.Objects.requireNonNull(buildSystem, "buildSystem"))
                    .bind("epoch", sdd.index.source.SourceExtraction.EXTRACTOR_EPOCH)
                    .execute();
            long repoId = h.createQuery("SELECT id FROM repo WHERE name=:n")
                    .bind("n", scan.name()).mapTo(Long.class).one();
            // Must precede the module delete: fts_symbol rows are keyed by module id and the
            // modules about to be reinserted get fresh ids, so this is the last moment the old
            // symbol rows are reachable at all.
            FtsSymbolWriter.deleteForRepo(h, repoId);
            h.createUpdate("DELETE FROM module WHERE repo_id=:r").bind("r", repoId).execute();
            for (BuildModel.Module m : extract.modules()) {
                insertModule(h, repoId, m, catalogGAs);
            }
        });
    }

    private static void insertModule(Handle h, long repoId, BuildModel.Module p, Set<String> catalogGAs) {
        h.createUpdate("INSERT INTO module(repo_id, gradle_path, grp, name, version, kind, language) "
                        + "VALUES (:r, :path, :grp, :name, :ver, :kind, :lang)")
                .bind("r", repoId).bind("path", p.path()).bind("grp", p.group())
                .bind("name", p.name()).bind("ver", p.version()).bind("kind", p.kind())
                .bind("lang", p.language())
                .execute();
        long moduleId = h.createQuery("SELECT last_insert_rowid()").mapTo(Long.class).one();

        if (!p.publishes().isEmpty()) {
            for (BuildModel.Coordinate pub : p.publishes()) {
                insertArtifact(h, repoId, moduleId, pub.group(), pub.name());
            }
        } else if (p.group() != null && !p.group().isBlank()) {
            insertArtifact(h, repoId, moduleId, p.group(), p.name());
        }

        // Edges are DECLARED dependencies only. The resolved set from the init script is the
        // full lenient classpath (transitives included); those are not this module's own edges.
        // Iterates whatever configuration names sdd-init.gradle emitted (compile/runtime AND, as
        // of the test-scope fix, testCompileClasspath/testRuntimeClasspath) — nothing here is
        // hardcoded to a config name. p.configurations() is a LinkedHashMap that preserves the
        // init script's emission order (compile/runtime first, test configs after), so the
        // first-seen-wins merge below keeps compile-scope `configuration` labeling for a dep that
        // is declared in both a compile-scope and a test-scope config. A dep declared ONLY in a
        // test config keeps that config's name as its `configuration` label, and its dep_edge
        // gets a normal mode/declared_via classification (design doc line 40: consumption mode is
        // per internal edge, not per scope). Test-scope edges still feed the planner's impact
        // closure, which runs over ALL internal Gradle edges regardless of scope (design doc line
        // 50) — so a product's testImplementation on an internal test-fixture artifact now
        // correctly pulls that artifact's producer repo into the affected set.
        Map<String, MergedDep> merged = new LinkedHashMap<>();
        p.scopes().forEach((cfgName, cfg) -> {
            for (BuildModel.DeclaredDep d : cfg.declared()) {
                MergedDep m = merged.computeIfAbsent(d.group() + ":" + d.name(),
                        k -> new MergedDep(d.group(), d.name(), cfgName));
                if (m.declaredVersion == null) {
                    m.declaredVersion = d.version();
                }
            }
        });
        // Resolution results only enrich declared edges with the version actually selected.
        for (BuildModel.DepScope cfg : p.scopes().values()) {
            for (BuildModel.ResolvedDep r : cfg.resolved()) {
                MergedDep m = merged.get(r.group() + ":" + r.name());
                if (m != null && m.resolvedVersion == null) {
                    m.resolvedVersion = r.version();
                }
            }
        }
        for (MergedDep d : merged.values()) {
            boolean inCatalog = catalogGAs.contains(d.group + ":" + d.name);
            h.createUpdate("""
                            INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration,
                                                 declared_version, resolved_version, declared_via, mode)
                            VALUES (:m, :g, :n, :cfg, :dv, :rv, :via, :mode)""")
                    .bind("m", moduleId).bind("g", d.group).bind("n", d.name).bind("cfg", d.configuration)
                    .bind("dv", d.declaredVersion).bind("rv", d.resolvedVersion)
                    .bind("via", ModeClassifier.declaredVia(d.declaredVersion, inCatalog))
                    .bind("mode", classifyMode(p.language(), d.declaredVersion).name())
                    .execute();
        }
    }

    private static void insertArtifact(Handle h, long repoId, long moduleId, String grp, String name) {
        // The GA may already be claimed by a module in another repo. The upsert still wins
        // (last writer owns the mapping) but the takeover must not be silent: record it on the
        // repo row so `sdd index` surfaces it. Full resolution lives in the linker report.
        h.createQuery("""
                        SELECT r.name FROM artifact a
                        JOIN module m ON m.id = a.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE a.grp = :g AND a.name = :n AND r.id <> :repo""")
                .bind("g", grp).bind("n", name).bind("repo", repoId)
                .mapTo(String.class).findOne()
                .ifPresent(other -> h.createUpdate(
                                "UPDATE repo SET error = COALESCE(error, '') || :note WHERE id = :repo")
                        .bind("note", "GA conflict: " + grp + ":" + name
                                + " also published by " + other + "; ")
                        .bind("repo", repoId).execute());
        h.createUpdate("INSERT INTO artifact(grp, name, module_id) VALUES (:g, :n, :m) "
                        + "ON CONFLICT(grp, name) DO UPDATE SET module_id=excluded.module_id")
                .bind("g", grp).bind("n", name).bind("m", moduleId).execute();
    }

    /**
     * Version-specifier grammar is per-ecosystem, and the two disagree on the same strings.
     * {@code ^0.2.1} under Maven rules has no {@code +}, does not end {@code -SNAPSHOT} and does
     * not start {@code [} or {@code (}, so the Gradle classifier calls it PINNED — the exact
     * opposite of what it means. Every npm range in an estate would be mislabelled, with no error.
     */
    static ConsumptionMode classifyMode(String language, String declaredVersion) {
        return "TYPESCRIPT".equals(language)
                ? NpmModeClassifier.classify(declaredVersion, false)
                : ModeClassifier.classify(declaredVersion, false);
    }

    private static String rollupKind(BuildModel.Extract extract) {
        boolean svc = false;
        boolean lib = false;
        for (BuildModel.Module m : extract.modules()) {
            svc |= m.kind().equals("SERVICE");
            lib |= m.kind().equals("LIBRARY");
        }
        if (svc && lib) {
            return "MIXED";
        }
        if (svc) {
            return "SERVICE";
        }
        return lib ? "LIBRARY" : "UNKNOWN";
    }

    /**
     * If the exact append text is already present as a "; "-delimited segment of the existing
     * error, the append is skipped — a repeated identical staleness cause (e.g. the same repo
     * going stale for the same reason on successive runs) must not grow the column unbounded.
     * The match is delimiter-anchored rather than a raw substring check: notes are
     * "; "-terminated by construction, so both the existing error and the needle are wrapped in
     * "; " before comparing, which also means "3 ... failed" is not mistaken for already-present
     * inside "13 ... failed". Kept mirrored with {@link SourcePersistence#updateParseStatus}'s
     * equivalent CASE expression.
     */
    public static void markStale(Jdbi jdbi, String repoName, String error) {
        jdbi.useHandle(h -> h.createUpdate("""
                        UPDATE repo SET gradle_status='STALE_OK',
                          error = CASE WHEN :append IS NULL THEN error
                                       WHEN instr('; ' || COALESCE(error, ''), '; ' || :append || '; ') > 0 THEN error
                                       ELSE COALESCE(NULLIF(rtrim(COALESCE(error, ''), '; '), '') || '; ', '')
                                            || :append || '; ' END
                        WHERE name=:name""")
                .bind("append", error).bind("name", repoName).execute());
    }

    private static final class MergedDep {
        final String group;
        final String name;
        final String configuration;
        String declaredVersion;
        String resolvedVersion;

        MergedDep(String group, String name, String configuration) {
            this.group = group;
            this.name = name;
            this.configuration = configuration;
        }
    }
}
