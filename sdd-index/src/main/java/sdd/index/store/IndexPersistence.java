package sdd.index.store;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import sdd.index.gradle.CatalogReader;
import sdd.index.gradle.GradleModel;
import sdd.index.gradle.ModeClassifier;
import sdd.index.scan.RepoScan;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class IndexPersistence {
    private IndexPersistence() {}

    public static void persistRepo(Jdbi jdbi, RepoScan scan, GradleModel.Extract extract,
                                   String gradleStatus, String error) {
        Set<String> catalogGAs = CatalogReader.internalGAs(scan.path());
        jdbi.useTransaction(h -> {
            String includedJson = extract.includedBuilds().stream()
                    .map(p -> '"' + p.toString().replace("\\", "\\\\").replace("\"", "\\\"") + '"')
                    .collect(Collectors.joining(",", "[", "]"));
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
                    .bind("name", scan.name()).bind("path", scan.path().toString())
                    .bind("kind", repoKind).bind("head", scan.headCommit())
                    .bind("branch", scan.branch()).bind("dirty", scan.dirtyHash())
                    .bind("included", includedJson).bind("status", gradleStatus)
                    .bind("error", error).bind("at", Instant.now().toString())
                    .execute();
            long repoId = h.createQuery("SELECT id FROM repo WHERE name=:n")
                    .bind("n", scan.name()).mapTo(Long.class).one();
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
                insertArtifact(h, moduleId, pub.groupId(), pub.artifactId());
            }
        } else if (p.group() != null && !p.group().isBlank()) {
            insertArtifact(h, moduleId, p.group(), p.name());
        }

        Map<String, MergedDep> merged = new LinkedHashMap<>();
        p.configurations().forEach((cfgName, cfg) -> {
            for (GradleModel.DeclaredDep d : cfg.declared()) {
                merged.computeIfAbsent(d.group() + ":" + d.name(),
                        k -> new MergedDep(d.group(), d.name(), cfgName)).declaredVersion = d.version();
            }
            for (GradleModel.ResolvedDep r : cfg.resolved()) {
                merged.computeIfAbsent(r.group() + ":" + r.name(),
                        k -> new MergedDep(r.group(), r.name(), cfgName)).resolvedVersion = r.version();
            }
        });
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

    private static void insertArtifact(Handle h, long moduleId, String grp, String name) {
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
