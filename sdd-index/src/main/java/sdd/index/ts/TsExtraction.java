package sdd.index.ts;

import com.fasterxml.jackson.databind.JsonNode;
import org.jdbi.v3.core.Jdbi;
import sdd.core.ts.TsSidecar;
import sdd.index.extract.BuildModel;
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

/**
 * Source facts for a TypeScript repo. Today that means the outbound HTTP calls its packages make,
 * which is what lets a question about a backend endpoint be answered with the front ends that call
 * it.
 *
 * <p>Call sites are written to {@code rest_client}, the same table the Spring extractor fills.
 * Sharing it is the whole point: {@code RestMatcher}, {@code ContractEdges}, the impact closure and
 * every explain fact already read that table and none of them mention a language, so a TypeScript
 * caller becomes visible everywhere a Java one is, without a line of new query code.
 */
public final class TsExtraction {

    /** {@code rest_client.kind} values for the two shapes a TypeScript call can take. */
    public static final String KIND_FETCH = "TS_FETCH";
    public static final String KIND_WRAPPER = "TS_HTTP_WRAPPER";

    private TsExtraction() {
    }

    /**
     * @param sidecar absent when node could not be found. That is recorded as a FAILED parse rather
     *                than silently producing nothing: a repo with no facts and a repo whose facts
     *                could not be read look identical in the knowledge base, and only one of them
     *                is worth acting on. Because the fingerprint short-circuit refuses to skip a
     *                FAILED parse, the repo also retries by itself once node appears.
     * @return the parse status recorded for the repo
     */
    public static String extractRepo(Jdbi jdbi, long repoId, String repoName, Path repoPath,
                                     BuildModel.Extract extract, Optional<TsSidecar> sidecar) {
        if (sidecar.isEmpty()) {
            SourcePersistence.updateParseStatus(jdbi, repoName, "FAILED",
                    "no node available to read TypeScript sources");
            return "FAILED";
        }

        Path repoRoot = Paths2.canonical(repoPath);
        Map<Long, List<SpringModel.ClientInfo>> clientsByModule = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();

        List<Path> allModuleDirs = extract.modules().stream()
                .filter(m -> "TYPESCRIPT".equals(m.language()))
                .map(BuildModel.Module::moduleDir)
                .toList();

        for (BuildModel.Module module : extract.modules()) {
            if (!"TYPESCRIPT".equals(module.language())) {
                continue;
            }
            Optional<Long> moduleId = jdbi.withHandle(h -> h.createQuery(
                            "SELECT id FROM module WHERE repo_id=:r AND gradle_path=:p")
                    .bind("r", repoId).bind("p", module.path()).mapTo(Long.class).findOne());
            if (moduleId.isEmpty()) {
                continue;
            }
            List<Path> files = TsSourceFiles.discover(module.moduleDir(), allModuleDirs);
            if (files.isEmpty()) {
                clientsByModule.put(moduleId.get(), List.of());
                continue;
            }
            TsSidecar.Result result = sidecar.get().httpCallSites(repoRoot, files);
            if (!result.ok()) {
                failures.add(module.path() + ": " + result.error());
                continue;
            }
            clientsByModule.put(moduleId.get(), clientsOf(result.json()));
        }

        // One transaction for the whole repo, matching the Java path: a mid-loop failure must not
        // leave some modules freshly extracted and others stale.
        jdbi.useTransaction(h -> clientsByModule.forEach((moduleId, clients) ->
                SpringPersistence.persistModuleSpring(h, moduleId, null,
                        new SpringModel.SpringExtract(List.of(), clients, List.of(), false))));

        if (!failures.isEmpty()) {
            SourcePersistence.updateParseStatus(jdbi, repoName, "DEGRADED",
                    failures.size() + " package(s) could not be read: " + String.join("; ", failures));
            return "DEGRADED";
        }
        SourcePersistence.updateParseStatus(jdbi, repoName, "OK", null);
        return "OK";
    }

    private static List<SpringModel.ClientInfo> clientsOf(JsonNode response) {
        List<SpringModel.ClientInfo> clients = new ArrayList<>();
        for (JsonNode site : response.path("sites")) {
            String path = text(site, "pathValue");
            clients.add(new SpringModel.ClientInfo(
                    site.path("kind").asText(KIND_FETCH),
                    // A file path with an optional container, never a dotted name: nothing joins
                    // this column against java_type, and the shape stops a reader mistaking a
                    // TypeScript module for a Java class.
                    text(site, "container"),
                    text(site, "site"),
                    site.path("verb").asText("ANY"),
                    // Blank is converted to null deliberately. Routes.normalize("") returns "/",
                    // and templatesMatch would then pair such a client with every bare-root
                    // endpoint in the estate — a fabricated edge from an empty string.
                    path == null || path.isBlank() ? null : path,
                    null,   // no target hint: a browser talks to one origin, so there is none to read
                    site.path("resolution").asText("DYNAMIC"),
                    text(site, "raw")));
        }
        return clients;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }
}
