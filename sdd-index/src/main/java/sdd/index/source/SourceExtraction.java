package sdd.index.source;

import com.github.javaparser.ParserConfiguration;
import org.jdbi.v3.core.Jdbi;
import sdd.index.extract.BuildModel;
import sdd.index.spring.ConfigFileParser;
import sdd.index.spring.SpringConfigPersistence;
import sdd.index.spring.SpringExtraction;
import sdd.index.spring.SpringModel;
import sdd.index.store.Paths2;
import sdd.index.store.SourcePersistence;
import sdd.index.store.SpringPersistence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SourceExtraction {

    /**
     * Which vintage of extractor output the knowledge base holds. <b>Bump this by hand in the same
     * commit as any change to what the extractors EMIT.</b>
     *
     * <p>The index short-circuits on {@code head_commit||':'||dirty_hash}, which a migration does
     * not change — so a schema upgrade that adds a fact is invisible to it, and a plain
     * {@code sdd index} skips every repo and reports success. V4 bought visibility with a bespoke
     * {@code build_system IS NOT NULL} guard; V2 and V3 bought nothing and left {@code --force} as
     * the only remedy, which has bitten this project twice. Guarding the fingerprint on this
     * constant instead means a NULL (pre-V6) or stale epoch never matches, so the workspace heals
     * itself — while a future migration that only widens a reader-side table can deliberately NOT
     * bump it rather than forcing a needless full re-extract.
     *
     * <p>1 = the epoch that introduced {@code type_supertype}.
     */
    public static final int EXTRACTOR_EPOCH = 1;
    private SourceExtraction() {}

    public static String extractRepo(Jdbi jdbi, long repoId, String repoName,
                                     Path repoPath, BuildModel.Extract extract) {
        record ModuleWork(long moduleId, boolean library, SourceParser.Session session,
                          ConfigFileParser.Result config, List<Path> jars) {}
        record EligibleProject(long moduleId, boolean library, Path projectDir, List<Path> jars) {}

        // Both sides of the relativize below must be canonical or the relative path degenerates
        // into "../../../private/var/..." junk: the scanner reports the repo path as listed
        // (symlinks intact) while Gradle reports projectDir already canonicalized.
        Path repoRoot = Paths2.canonical(repoPath);

        List<EligibleProject> eligible = new ArrayList<>();
        for (BuildModel.Module p : extract.modules()) {
            Optional<Long> moduleId = jdbi.withHandle(h -> h.createQuery(
                            "SELECT id FROM module WHERE repo_id=:r AND gradle_path=:p")
                    .bind("r", repoId).bind("p", p.path()).mapTo(Long.class).findOne());
            if (moduleId.isEmpty()) {
                continue;
            }
            List<Path> jars = Optional.ofNullable(p.scopes().get("compileClasspath"))
                    .map(c -> c.resolved().stream().flatMap(r -> r.files().stream()).toList())
                    .orElse(List.of());
            Path projectDir = Paths2.canonical(p.moduleDir());
            boolean library = jdbi.withHandle(h -> h.createQuery(
                            "SELECT kind FROM module WHERE id=:m").bind("m", moduleId.get())
                    .mapTo(String.class).one()).equals("LIBRARY");
            eligible.add(new EligibleProject(moduleId.get(), library, projectDir, jars));
        }

        // One solver for the whole repo: every eligible module's source roots plus the union of
        // every eligible module's classpath jars, deduped by canonical path so a jar shared by
        // several modules is parsed (and parented) exactly once — see RepoSolver's javadoc for why
        // sharing JarTypeSolver instances across CombinedTypeSolvers is unsupported.
        List<Path> allRoots = new ArrayList<>();
        Map<String, Path> uniqueJarsByKey = new LinkedHashMap<>();
        for (EligibleProject ep : eligible) {
            allRoots.addAll(SourceParser.sourceRootsOf(ep.projectDir()));
            for (Path jar : ep.jars()) {
                uniqueJarsByKey.putIfAbsent(Paths2.canonicalString(jar), jar);
            }
        }
        ParserConfiguration repoConfig = RepoSolver.configFor(allRoots, List.copyOf(uniqueJarsByKey.values()));

        List<ModuleWork> work = new ArrayList<>();
        int totalIssues = 0;
        for (EligibleProject ep : eligible) {
            SourceParser.Session session = SourceParser.parseModule(repoRoot, ep.projectDir(), repoConfig);
            totalIssues += session.issues().size();
            ConfigFileParser.Result config = ConfigFileParser.parseModuleConfig(repoRoot, ep.projectDir());
            totalIssues += config.issues().size();
            work.add(new ModuleWork(ep.moduleId(), ep.library(), session, config, ep.jars()));
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
                SpringConfigPersistence.persistModuleConfig(h, w.moduleId(), w.config().entries());

                Map<String, String> defaults =
                        SpringConfigPersistence.defaultProfileProps(w.config().entries());
                List<String> allKeys = w.config().entries().stream()
                        .map(ConfigFileParser.ConfigEntry::key).toList();
                SpringModel.SpringExtract spring = SpringExtraction.extractModule(
                        w.session(), defaults, w.jars(), allKeys);
                SpringPersistence.persistModuleSpring(h, w.moduleId(),
                        defaults.get("server.servlet.context-path"), spring);
            }
        });

        String status = totalIssues == 0 ? "OK" : "DEGRADED";
        SourcePersistence.updateParseStatus(jdbi, repoName, status,
                totalIssues == 0 ? null : totalIssues + " source files failed to parse");
        return status;
    }
}
