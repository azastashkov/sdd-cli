package sdd.index.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import sdd.core.retrieve.FtsSymbolWriter;
import sdd.index.gradle.CatalogReader;
import sdd.index.gradle.GradleModel;
import sdd.index.gradle.ModeClassifier;
import sdd.index.scan.RepoScan;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class IndexPersistence {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IndexPersistence() {}

    public static void persistRepo(Jdbi jdbi, RepoScan scan, GradleModel.Extract extract,
                                   String gradleStatus, String error) {
        Set<String> catalogGAs = CatalogReader.internalGAs(scan.path());
        jdbi.useTransaction(h -> {
            String includedJson;
            try {
                includedJson = MAPPER.writeValueAsString(extract.includedBuilds().stream()
                        .map(Paths2::canonicalString).toList());
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize included builds", e);
            }
            String repoKind = rollupKind(extract);
            h.createUpdate("""
                            INSERT INTO repo(name, path, kind, head_commit, branch, dirty_hash,
                                             included_builds, gradle_status, error, indexed_at)
                            VALUES (:name, :path, :kind, :head, :branch, :dirty, :included, :status, :error, :at)
                            ON CONFLICT(name) DO UPDATE SET
                              path=excluded.path, kind=excluded.kind, head_commit=excluded.head_commit,
                              branch=excluded.branch, dirty_hash=excluded.dirty_hash,
                              included_builds=excluded.included_builds, gradle_status=excluded.gradle_status,
                              error=excluded.error, indexed_at=excluded.indexed_at""")
                    .bind("name", scan.name()).bind("path", Paths2.canonicalString(scan.path()))
                    .bind("kind", repoKind).bind("head", scan.headCommit())
                    .bind("branch", scan.branch()).bind("dirty", scan.dirtyHash())
                    .bind("included", includedJson).bind("status", gradleStatus)
                    .bind("error", error).bind("at", Instant.now().toString())
                    .execute();
            long repoId = h.createQuery("SELECT id FROM repo WHERE name=:n")
                    .bind("n", scan.name()).mapTo(Long.class).one();
            // Must precede the module delete: fts_symbol rows are keyed by module id and the
            // modules about to be reinserted get fresh ids, so this is the last moment the old
            // symbol rows are reachable at all.
            FtsSymbolWriter.deleteForRepo(h, repoId);
            h.createUpdate("DELETE FROM module WHERE repo_id=:r").bind("r", repoId).execute();
            for (GradleModel.Project p : extract.projects()) {
                insertModule(h, repoId, p, catalogGAs);
            }
        });
    }

    private static void insertModule(Handle h, long repoId, GradleModel.Project p, Set<String> catalogGAs) {
        String kind = moduleKind(p);
        h.createUpdate("INSERT INTO module(repo_id, gradle_path, grp, name, version, kind) "
                        + "VALUES (:r, :path, :grp, :name, :ver, :kind)")
                .bind("r", repoId).bind("path", p.path()).bind("grp", p.group())
                .bind("name", p.name()).bind("ver", p.version()).bind("kind", kind)
                .execute();
        long moduleId = h.createQuery("SELECT last_insert_rowid()").mapTo(Long.class).one();

        if (!p.publications().isEmpty()) {
            for (GradleModel.Publication pub : p.publications()) {
                insertArtifact(h, repoId, moduleId, pub.groupId(), pub.artifactId());
            }
        } else if (p.group() != null && !p.group().isBlank()) {
            insertArtifact(h, repoId, moduleId, p.group(), p.name());
        }

        // Edges are DECLARED dependencies only. The resolved set from the init script is the
        // full lenient classpath (transitives included); those are not this module's own edges.
        Map<String, MergedDep> merged = new LinkedHashMap<>();
        p.configurations().forEach((cfgName, cfg) -> {
            for (GradleModel.DeclaredDep d : cfg.declared()) {
                MergedDep m = merged.computeIfAbsent(d.group() + ":" + d.name(),
                        k -> new MergedDep(d.group(), d.name(), cfgName));
                if (m.declaredVersion == null) {
                    m.declaredVersion = d.version();
                }
            }
        });
        // Resolution results only enrich declared edges with the version actually selected.
        for (GradleModel.DepConfig cfg : p.configurations().values()) {
            for (GradleModel.ResolvedDep r : cfg.resolved()) {
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
                    .bind("mode", ModeClassifier.classify(d.declaredVersion, false).name())
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

    private static String moduleKind(GradleModel.Project p) {
        boolean boot = p.plugins().contains("org.springframework.boot") || p.hasBootJarTask();
        if (boot) {
            return "SERVICE";
        }
        if (p.plugins().contains("maven-publish") || !p.publications().isEmpty()) {
            return "LIBRARY";
        }
        return "UNKNOWN";
    }

    private static String rollupKind(GradleModel.Extract extract) {
        boolean svc = false;
        boolean lib = false;
        for (GradleModel.Project p : extract.projects()) {
            String k = moduleKind(p);
            svc |= k.equals("SERVICE");
            lib |= k.equals("LIBRARY");
        }
        if (svc && lib) {
            return "MIXED";
        }
        if (svc) {
            return "SERVICE";
        }
        return lib ? "LIBRARY" : "UNKNOWN";
    }

    public static void markStale(Jdbi jdbi, String repoName, String error) {
        jdbi.useHandle(h -> h.createUpdate(
                        "UPDATE repo SET gradle_status='STALE_OK', error=:e WHERE name=:n")
                .bind("e", error).bind("n", repoName).execute());
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
