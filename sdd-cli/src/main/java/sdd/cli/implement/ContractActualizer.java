package sdd.cli.implement;

import sdd.core.contract.ContractKinds;
import sdd.core.contract.DeclaredContract;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Re-extracts a green provider's REAL interface surface into actualized contract bodies
 * (design line 62, M4): downstream work orders see what the tree actually exposes, not what the
 * planner drafted. Jar-less on purpose — ApiSurface/REST extraction is syntactic; Kafka payload
 * types degrade to raw expressions exactly as the indexer does without a resolved classpath.
 * The KB is never touched (read-only during implement).
 */
public final class ContractActualizer {
    static final int MAX_BODY = 4000;
    /** Marks a body cut off at MAX_BODY; shared with ContractRecheck so it can spot a match that
     *  is only a match because both sides were truncated at the same cap. */
    public static final String TRUNCATION_MARKER = "…(truncated)";
    /** Marks an actual line whose value extraction could not resolve — the 2026-08-14 "unresolved
     *  extraction" amendment (design line 42: unresolved is not the same as nonexistent). Appended
     *  at the very end of the line so it never disturbs the canonical member text {@code
     *  DeclaredContract} matches on. Mirrored (not referenced — sdd-core cannot depend on sdd-cli)
     *  as a private literal in {@code DeclaredContract.unresolvedMembers}. */
    public static final String UNRESOLVED_MARKER = " [unresolved]";
    /** The literal {@code KafkaExtractor} writes into {@code KafkaUse.resolution()} — {@code
     *  ValueResolver.Resolution.DYNAMIC.name()} — whenever a topic (or topic pattern) expression
     *  could not be resolved and the topic field falls back to the raw source expression instead
     *  of a literal value. Grepped from KafkaExtractor, not guessed. */
    private static final String KAFKA_UNRESOLVED = "DYNAMIC";
    /** The verb {@code RestEndpointExtractor.mappingsOf} writes for a verbless method-level {@code
     *  @RequestMapping} (no {@code method} attribute) — a value {@code DeclaredContract}'s
     *  REST_METHODS grammar cannot legally declare in the first place. Shared with
     *  {@code ContractRecheck} the same way {@link #UNRESOLVED_MARKER} is: this class marks the
     *  shape, that one matches it, and the two halves of one protocol must not drift apart. */
    public static final String REST_ANY_VERB = "ANY";

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
                case "java-api" -> javaApi(sessions, contract.body(), contract.declared());
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

    private static String javaApi(List<ModuleSession> sessions, String draftedBody, List<String> declared) {
        List<SourceModel.TypeInfo> all = new ArrayList<>();
        for (ModuleSession module : sessions) {
            all.addAll(ApiSurfaceExtractor.extract(module.session(), true));
        }
        List<String> declaredMembers = DeclaredContract.parse(ContractKinds.JAVA_API,
                String.join("\n", declared)).members();
        List<SourceModel.TypeInfo> selected;
        if (declaredMembers.isEmpty()) {
            // No usable declaration (nothing declared, or every line was malformed): behavior must
            // stay byte-for-byte what it was before declarations existed.
            List<SourceModel.TypeInfo> relevant = all.stream()
                    .filter(type -> draftedBody.contains(type.fqcn()) || draftedBody.contains(simple(type.fqcn())))
                    .toList();
            selected = relevant.isEmpty() ? all : relevant;
        } else {
            // A declared block is the strongest selector there is — no whole-surface fallback.
            // "none of the declared types exist" must produce a small body that fails containment
            // loudly, not a large healthy-looking dump that hides the divergence.
            Set<String> declaredFqcns = new LinkedHashSet<>();
            for (String member : declaredMembers) {
                int hash = member.indexOf('#');
                declaredFqcns.add(hash >= 0 ? member.substring(0, hash) : member);
            }
            selected = all.stream().filter(type -> declaredFqcns.contains(type.fqcn())).toList();
        }
        StringBuilder body = new StringBuilder();
        for (SourceModel.TypeInfo type : selected) {
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
                        .append(endpoint.methodName());
                // Only the verbless shape is marked here. The other named shape — an unresolvable
                // path expression — is NOT marked: RestEndpointExtractor.resolvePaths substitutes
                // "" on failure, but Routes.join(base, path) floors any all-empty join to "/", and
                // a bare @GetMapping with no path attribute at all *also* joins to "/" by the exact
                // same path (resolvePaths' exprs.isEmpty() branch, never touching the substitution
                // line). EndpointInfo carries no field that tells these two apart, so pathTemplate
                // alone cannot distinguish "unresolved" from "no path was ever declared" — verified
                // empirically, not assumed. Marking on pathTemplate=="/" would also mark every
                // genuine bare-root endpoint, and the partition rule this amendment specifies
                // ("same verb with an empty path") ignores the missing member's path entirely, so
                // it would excuse ANY missing declared member sharing that verb — silently turning
                // real divergences into NOT_RESOLVED. Deliberately left unmarked — see the
                // 2026-08-14 "unresolved extraction" amendment's `rest` bullet (design spec) for
                // the ruling and the full trace of why this shape is not distinguishable.
                if (REST_ANY_VERB.equals(endpoint.httpMethod())) {
                    body.append(UNRESOLVED_MARKER);
                }
                body.append('\n');
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
            kafka.uses().forEach(use -> {
                body.append(use.role()).append(' ').append(use.topic());
                if (KAFKA_UNRESOLVED.equals(use.resolution())) {
                    body.append(UNRESOLVED_MARKER);
                }
                body.append('\n');
            });
        }
        return body.toString();
    }

    // Mirrors FileTools.SKIP_DIRS: never descend into vendored deps or tool/VCS output looking for
    // module roots (the real estate live smoke nests modules under libs/ and services/ two deep —
    // src/main/java at repoRoot/depth-1 alone missed every one of them).
    private static final java.util.Set<String> SKIP_DIRS = java.util.Set.of(".git", "build", ".gradle",
            ".sdd", ".idea", "node_modules", "dist", "target");
    private static final int MAX_MODULE_DEPTH = 4;

    private static List<Path> moduleRoots(Path repoRoot) {
        List<Path> roots = new ArrayList<>();
        collectModuleRoots(repoRoot, 0, roots);
        return roots.stream().sorted().toList();
    }

    private static void collectModuleRoots(Path dir, int depth, List<Path> roots) {
        if (Files.isDirectory(dir.resolve("src/main/java"))) {
            roots.add(dir);
        }
        if (depth >= MAX_MODULE_DEPTH) {
            return;
        }
        try (var children = Files.list(dir)) {
            children.filter(Files::isDirectory)
                    .filter(child -> !SKIP_DIRS.contains(child.getFileName().toString()))
                    .forEach(child -> collectModuleRoots(child, depth + 1, roots));
        } catch (java.io.IOException e) {
            // unreadable directory: fall through with whatever was found so far
        }
    }

    private static String simple(String fqcn) {
        return fqcn.substring(fqcn.lastIndexOf('.') + 1);
    }

    private static String cap(String body) {
        return body.length() <= MAX_BODY ? body : body.substring(0, MAX_BODY) + "\n" + TRUNCATION_MARKER;
    }
}
