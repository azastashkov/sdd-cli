package sdd.cli.implement;

import sdd.index.source.ApiSurfaceExtractor;
import sdd.index.source.SourceModel;
import sdd.index.source.SourceParser;
import sdd.index.spring.ConfigFileParser;
import sdd.index.spring.KafkaExtractor;
import sdd.index.spring.RestEndpointExtractor;
import sdd.index.spring.SpringConfigPersistence;
import sdd.index.spring.SpringModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-extracts a green provider's REAL interface surface into actualized contract bodies
 * (design line 62, M4): downstream work orders see what the tree actually exposes, not what the
 * planner drafted. Jar-less on purpose — ApiSurface/REST extraction is syntactic; Kafka payload
 * types degrade to raw expressions exactly as the indexer does without a resolved classpath.
 * The KB is never touched (read-only during implement).
 */
public final class ContractActualizer {
    static final int MAX_BODY = 4000;

    private ContractActualizer() {
    }

    public static Map<String, String> actualize(Path repoRoot, List<PlanModel.PlanContract> provided) {
        if (provided.isEmpty()) {
            return Map.of();
        }
        List<ModuleSession> sessions = new ArrayList<>();
        for (Path moduleDir : moduleRoots(repoRoot)) {
            try {
                sessions.add(new ModuleSession(moduleDir,
                        SourceParser.parseModule(repoRoot, moduleDir, List.of())));
            } catch (RuntimeException e) {
                // an unparseable module degrades that module's surface, never the whole actualization
            }
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (PlanModel.PlanContract contract : provided) {
            String body = switch (contract.kind()) {
                case "java-api" -> javaApi(sessions, contract.body());
                case "rest" -> rest(repoRoot, sessions);
                case "kafka" -> kafka(repoRoot, sessions);
                default -> "";
            };
            if (!body.isBlank()) {
                result.put(contract.id(), cap("# actualized (" + contract.kind() + ")\n" + body));
            }
        }
        return result;
    }

    /** Session alone loses the module dir the REST/Kafka config parse needs — keep the pair. */
    private record ModuleSession(Path moduleDir, SourceParser.Session session) {
    }

    private static String javaApi(List<ModuleSession> sessions, String draftedBody) {
        List<SourceModel.TypeInfo> all = new ArrayList<>();
        for (ModuleSession module : sessions) {
            all.addAll(ApiSurfaceExtractor.extract(module.session(), true));
        }
        List<SourceModel.TypeInfo> relevant = all.stream()
                .filter(type -> draftedBody.contains(type.fqcn()) || draftedBody.contains(simple(type.fqcn())))
                .toList();
        StringBuilder body = new StringBuilder();
        for (SourceModel.TypeInfo type : relevant.isEmpty() ? all : relevant) {
            body.append(type.fqcn()).append('\n');
            for (SourceModel.MemberInfo member : type.members()) {
                body.append("  ").append(member.signature()).append(": ")
                        .append(member.returnType()).append('\n');
            }
        }
        return body.toString();
    }

    private static String rest(Path repoRoot, List<ModuleSession> sessions) {
        StringBuilder body = new StringBuilder();
        for (ModuleSession module : sessions) {
            // parseModuleConfig returns Result(entries, issues); flatten to default-profile props
            Map<String, String> props = SpringConfigPersistence.defaultProfileProps(
                    ConfigFileParser.parseModuleConfig(repoRoot, module.moduleDir()).entries());
            for (SpringModel.EndpointInfo endpoint : RestEndpointExtractor.extract(module.session(), props)) {
                body.append(endpoint.httpMethod()).append(' ').append(endpoint.pathTemplate())
                        .append(" -> ").append(endpoint.classFqcn()).append('#')
                        .append(endpoint.methodName()).append('\n');
            }
        }
        return body.toString();
    }

    private static String kafka(Path repoRoot, List<ModuleSession> sessions) {
        StringBuilder body = new StringBuilder();
        for (ModuleSession module : sessions) {
            Map<String, String> props = SpringConfigPersistence.defaultProfileProps(
                    ConfigFileParser.parseModuleConfig(repoRoot, module.moduleDir()).entries());
            KafkaExtractor.KafkaResult kafka = KafkaExtractor.extract(module.session(), props,
                    List.of(), props.keySet());
            kafka.uses().forEach(use -> body.append(use.role()).append(' ')
                    .append(use.topic()).append('\n'));
        }
        return body.toString();
    }

    private static List<Path> moduleRoots(Path repoRoot) {
        List<Path> roots = new ArrayList<>();
        if (Files.isDirectory(repoRoot.resolve("src/main/java"))) {
            roots.add(repoRoot);
        }
        try (var children = Files.list(repoRoot)) {
            children.filter(child -> Files.isDirectory(child.resolve("src/main/java")))
                    .sorted()
                    .forEach(roots::add);
        } catch (java.io.IOException e) {
            // unreadable repo root: fall through with whatever we found
        }
        return roots;
    }

    private static String simple(String fqcn) {
        return fqcn.substring(fqcn.lastIndexOf('.') + 1);
    }

    private static String cap(String body) {
        return body.length() <= MAX_BODY ? body : body.substring(0, MAX_BODY) + "\n…(truncated)";
    }
}
