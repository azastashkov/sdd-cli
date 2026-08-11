package sdd.index.source;

import org.jdbi.v3.core.Jdbi;
import sdd.index.gradle.GradleModel;
import sdd.index.store.Paths2;
import sdd.index.store.SourcePersistence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SourceExtraction {
    private SourceExtraction() {}

    public static String extractRepo(Jdbi jdbi, long repoId, String repoName,
                                     Path repoPath, GradleModel.Extract extract) {
        record ModuleWork(long moduleId, boolean library, SourceParser.Session session) {}
        List<ModuleWork> work = new ArrayList<>();
        int totalIssues = 0;
        // Both sides of the relativize below must be canonical or the relative path degenerates
        // into "../../../private/var/..." junk: the scanner reports the repo path as listed
        // (symlinks intact) while Gradle reports projectDir already canonicalized.
        Path repoRoot = Paths2.canonical(repoPath);
        JarSolverCache jarCache = new JarSolverCache();
        for (GradleModel.Project p : extract.projects()) {
            Optional<Long> moduleId = jdbi.withHandle(h -> h.createQuery(
                            "SELECT id FROM module WHERE repo_id=:r AND gradle_path=:p")
                    .bind("r", repoId).bind("p", p.path()).mapTo(Long.class).findOne());
            if (moduleId.isEmpty()) {
                continue;
            }
            List<Path> jars = Optional.ofNullable(p.configurations().get("compileClasspath"))
                    .map(c -> c.resolved().stream().flatMap(r -> r.files().stream()).toList())
                    .orElse(List.of());
            SourceParser.Session session = SourceParser.parseModule(
                    repoRoot, Paths2.canonical(p.projectDir()), jars, jarCache);
            totalIssues += session.issues().size();
            boolean library = jdbi.withHandle(h -> h.createQuery(
                            "SELECT kind FROM module WHERE id=:m").bind("m", moduleId.get())
                    .mapTo(String.class).one()).equals("LIBRARY");
            work.add(new ModuleWork(moduleId.get(), library, session));
        }

        Map<String, String> repoTypeIndex = new LinkedHashMap<>();
        Map<Long, List<SourceModel.TypeInfo>> typesByModule = new LinkedHashMap<>();
        for (ModuleWork w : work) {
            List<SourceModel.TypeInfo> types = ApiSurfaceExtractor.extract(w.session(), w.library());
            typesByModule.put(w.moduleId(), types);
            types.forEach(t -> repoTypeIndex.putIfAbsent(t.fqcn(), t.relPath()));
        }

        // One transaction for the whole repo: a mid-loop failure (bad row, constraint violation)
        // must roll back every module written so far, not leave a mix of freshly extracted and
        // stale modules plus a wiped file_ref table.
        jdbi.useTransaction(h -> {
            SourcePersistence.clearRepoFileRefs(h, repoId);
            for (ModuleWork w : work) {
                ReferenceExtractor.Refs refs = ReferenceExtractor.extract(w.session(), repoTypeIndex);
                SourcePersistence.persistModuleSource(h, repoId, w.moduleId(),
                        typesByModule.get(w.moduleId()), refs.usages(), refs.fileRefs());
            }
        });

        String status = totalIssues == 0 ? "OK" : "DEGRADED";
        SourcePersistence.updateParseStatus(jdbi, repoName, status,
                totalIssues == 0 ? null : totalIssues + " source files failed to parse");
        return status;
    }
}
