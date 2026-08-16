package sdd.cli.implement;

import com.fasterxml.jackson.databind.JsonNode;
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
import sdd.index.streams.StreamDescriptorExtractor;
import sdd.index.npm.PackageJson;
import sdd.index.ts.PackageEntries;
import sdd.index.ts.TsApiSurface;
import sdd.index.ts.TsExtraction;
import sdd.index.ts.TsSourceFiles;
import sdd.core.ts.TsSidecar;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        return actualize(repoRoot, provided, null);
    }

    /**
     * @param nodeHome the configured node installation, or null to take node from PATH. Only the
     *                 TypeScript kinds use it, and the sidecar is started only when one is asked
     *                 for — a Java-only plan never pays for node being absent.
     */
    public static Map<String, String> actualize(Path repoRoot, List<PlanModel.PlanContract> provided,
                                                Path nodeHome) {
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
        TsSessions ts = new TsSessions(repoRoot, nodeHome, provided);
        Map<String, String> result = new LinkedHashMap<>();
        for (PlanModel.PlanContract contract : provided) {
            String body = switch (contract.kind()) {
                case ContractKinds.JAVA_API -> javaApi(sessions, contract.body(), contract.declared());
                case ContractKinds.REST -> rest(repoRoot, sessions);
                case ContractKinds.KAFKA -> kafka(repoRoot, sessions);
                case ContractKinds.TS_API -> tsApi(ts, contract.declared());
                case ContractKinds.REST_CLIENT -> restClient(ts);
                case ContractKinds.STREAM_DESCRIPTOR -> streamDescriptor(sessions, ts);
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

    // -- TypeScript ----------------------------------------------------------------------------

    /**
     * The sidecar's reading of every npm package in the repo, computed once and only when a
     * TypeScript kind is actually asked for.
     *
     * <p>Held as one object because both TS kinds want the same expensive thing — one node
     * invocation per package — and a provider commonly declares both a {@code ts-api} and a
     * {@code rest-client} contract in the same plan.
     */
    private static final class TsSessions {
        private final List<TsPackage> packages = new ArrayList<>();

        TsSessions(Path repoRoot, Path nodeHome, List<PlanModel.PlanContract> provided) {
            boolean wanted = provided.stream().anyMatch(c ->
                    ContractKinds.TS_API.equals(c.kind()) || ContractKinds.REST_CLIENT.equals(c.kind())
                            || ContractKinds.STREAM_DESCRIPTOR.equals(c.kind()));
            if (!wanted) {
                return;
            }
            List<Path> roots = NpmPackages.roots(repoRoot);
            if (roots.isEmpty()) {
                return;
            }
            Optional<TsSidecar> sidecar = TsSidecar.create(nodeHome);
            if (sidecar.isEmpty()) {
                // Node absent: every TS body comes back empty, which ContractRecheck already reports
                // as NOT_EXTRACTABLE. That is the honest outcome — an empty body must never be read
                // as an empty surface, and this is the one place that distinction is preserved.
                return;
            }
            // Canonical, because the sidecar relativizes each file against this path and the file
            // list is canonical. On macOS a temp dir arrives as /var/... while the files resolve
            // under /private/var/..., and every recorded site comes out as a ../../.. climb out of
            // the repo — a path no reader can open and no two machines agree on.
            Path canonicalRoot = repoRoot;
            try {
                canonicalRoot = repoRoot.toRealPath();
            } catch (java.io.IOException e) {
                canonicalRoot = repoRoot.toAbsolutePath().normalize();
            }
            for (Path packageDir : roots) {
                String name;
                try {
                    name = PackageJson.read(packageDir.resolve("package.json")).name();
                } catch (java.io.IOException | RuntimeException e) {
                    continue;   // an unreadable manifest degrades that package, never the repo
                }
                if (name == null) {
                    continue;   // a nameless package.json is a workspaces shell, not a surface
                }
                List<Path> files = TsSourceFiles.discover(packageDir, roots);
                if (files.isEmpty()) {
                    continue;
                }
                packages.add(new TsPackage(name, packageDir, files, canonicalRoot, sidecar.get()));
            }
        }
    }

    /** One npm package, with its sidecar responses fetched lazily so a rest-client contract never
     *  pays for the API-surface pass and vice versa. */
    private static final class TsPackage {
        private final String name;
        private final Path packageDir;
        private final List<Path> files;
        private final Path repoRoot;
        private final TsSidecar sidecar;
        private TsSidecar.Result callSites;
        private TsSidecar.Result surface;
        private TsSidecar.Result streams;
        private PackageEntries.Result entries;

        TsPackage(String name, Path packageDir, List<Path> files, Path repoRoot, TsSidecar sidecar) {
            this.name = name;
            this.packageDir = packageDir;
            this.files = files;
            this.repoRoot = repoRoot;
            this.sidecar = sidecar;
        }

        TsSidecar.Result callSites() {
            if (callSites == null) {
                callSites = sidecar.httpCallSites(repoRoot, files);
            }
            return callSites;
        }

        TsSidecar.Result streams() {
            if (streams == null) {
                streams = sidecar.streamDescriptors(repoRoot, files);
            }
            return streams;
        }

        JsonNode surfaceNode() {
            if (surface == null) {
                entries = PackageEntries.of(packageDir, name);
                Map<String, Path> entryMap = new LinkedHashMap<>();
                entries.entries().forEach(e -> entryMap.put(e.specifier(), e.sourceFile()));
                surface = sidecar.apiSurface(repoRoot, name, files, entryMap);
            }
            return surface.ok() ? surface.json().path("packages").path(0) : null;
        }
    }

    /**
     * The published surface, as {@code <specifier>} headers over indented
     * {@code <Export>[.<member>]: <type>} lines — the same two-level shape {@code java-api} uses,
     * so {@code DeclaredContract} canonicalizes both with one rule.
     *
     * <p>A member with no written type annotation is marked {@link #UNRESOLVED_MARKER} rather than
     * given an inferred one. Inference needs the checker with lib files loaded, which the sidecar
     * deliberately does not do; guessing {@code any} here would let a contract "match" a type
     * nothing ever read.
     */
    /**
     * @param declared the contract's declared block, used as a selector exactly as
     *                 {@link #javaApi} uses its own — see {@link #declaredTsExports} for why this
     *                 is not optional on a real package.
     */
    private static String tsApi(TsSessions ts, List<String> declared) {
        Set<String> declaredExports = declaredTsExports(declared);
        StringBuilder body = new StringBuilder();
        for (TsPackage pkg : ts.packages) {
            JsonNode packageNode = pkg.surfaceNode();
            if (packageNode == null) {
                continue;
            }
            // Reuse the indexer's naming exactly: a contract that named an export differently from
            // the way the knowledge base records it would be checkable but unsearchable.
            List<SourceModel.TypeInfo> types = TsApiSurface.typesOf(packageNode, pkg.name,
                    false, false);
            Map<String, List<SourceModel.TypeInfo>> bySpecifier = new LinkedHashMap<>();
            for (SourceModel.TypeInfo type : types) {
                if (!type.isApi()) {
                    continue;   // not exported: not a surface anyone can declare a contract about
                }
                if (!declaredExports.isEmpty() && !declaredExports.contains(type.fqcn())) {
                    continue;   // selected out; the group is never created, so no bare header
                }
                int dot = type.fqcn().lastIndexOf('.');
                bySpecifier.computeIfAbsent(type.fqcn().substring(0, dot), k -> new ArrayList<>())
                        .add(type);
            }
            bySpecifier.forEach((specifier, exports) -> {
                body.append(specifier).append('\n');
                for (SourceModel.TypeInfo export : exports) {
                    appendExport(body, export);
                }
            });
        }
        return body.toString();
    }

    /**
     * The exports a ts-api declaration names, as {@code <specifier>.<Export>} — the shape
     * {@code TypeInfo.fqcn()} already uses, so selection is string equality.
     *
     * <p>Empty means "select everything", preserving the behaviour of every plan that declares
     * nothing. Where java-api can key on the part before the {@code #} because that IS the type,
     * ts-api cannot: there the prefix is the module specifier, so keying on it would re-select the
     * whole package. The export name is the first segment after the {@code #}, ending at the member
     * dot, the parameter list, or the type colon — whichever comes first.
     *
     * <p>Why this matters more here than on java-api: a Java contract's declared types are a handful
     * of classes out of a module, while a TypeScript package's surface is EVERY export of every
     * entry point in one body. On the real trading-web-sdk that is ~53kB against
     * {@link #MAX_BODY}'s 4000, so without this selector the declared members sat past the cut and
     * the contract could only ever report NOT_COMPARABLE — a conformance axis unable to reach a
     * verdict on the very packages it was added for.
     */
    private static Set<String> declaredTsExports(List<String> declared) {
        Set<String> exports = new LinkedHashSet<>();
        for (String member : DeclaredContract.parse(ContractKinds.TS_API,
                String.join("\n", declared)).members()) {
            int hash = member.indexOf('#');
            if (hash < 0) {
                continue;
            }
            String rest = member.substring(hash + 1);
            int end = rest.length();
            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c == '.' || c == '(' || c == ':') {
                    end = i;
                    break;
                }
            }
            exports.add(member.substring(0, hash) + "." + rest.substring(0, end));
        }
        return exports;
    }

    /** The synthetic member name the sidecar gives a const's or a non-object alias's written type —
     *  the whole export IS that type, so it supersedes the kind line rather than adding to it. */
    private static final String TS_VALUE_MEMBER = "<value>";

    private static void appendExport(StringBuilder body, SourceModel.TypeInfo export) {
        String name = export.fqcn().substring(export.fqcn().lastIndexOf('.') + 1);
        boolean valueTyped = export.members().stream()
                .anyMatch(m -> TS_VALUE_MEMBER.equals(m.name()));
        if (!valueTyped) {
            body.append("  ").append(name).append(": ").append(tsKind(export.kind())).append('\n');
        }
        for (SourceModel.MemberInfo member : export.members()) {
            String left;
            if (TS_VALUE_MEMBER.equals(member.name()) || member.name().equals(name)) {
                // A function's single member, or a const's written type: the export and the member
                // are the same thing, so prefixing would read as `login.login(...)`.
                left = name + member.signature().substring(member.name().length());
            } else {
                left = name + "." + member.signature();
            }
            body.append("  ").append(left).append(": ");
            if (member.returnType() == null) {
                body.append('?').append(UNRESOLVED_MARKER);
            } else {
                body.append(member.returnType());
            }
            body.append('\n');
        }
    }

    /** How a human writes the kind in TypeScript, so a declaration reads like the source it is
     *  about. {@code TYPE_ALIAS} is the one that is not simply lowercased — the keyword is
     *  {@code type}. */
    private static String tsKind(String kind) {
        return "TYPE_ALIAS".equals(kind) ? "type" : kind.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Outbound HTTP call sites, in the same {@code <VERB> <path> -> <site>} shape {@code rest} uses.
     *
     * <p>A site whose path could not be resolved writes {@code ?} — not a legal path, so it can
     * never be mistaken for one — and carries {@link #UNRESOLVED_MARKER} so a reviewer sees at Gate
     * 2 that something was unreadable right beside whatever was reported missing.
     */
    private static String restClient(TsSessions ts) {
        StringBuilder body = new StringBuilder();
        for (TsPackage pkg : ts.packages) {
            TsSidecar.Result result = pkg.callSites();
            if (!result.ok()) {
                continue;
            }
            for (SpringModel.ClientInfo client : TsExtraction.clientsOf(result.json())) {
                boolean resolved = client.uriTemplate() != null;
                body.append(client.httpMethod()).append(' ')
                        .append(resolved ? client.uriTemplate() : "?")
                        .append(" -> ").append(site(client));
                if (!resolved) {
                    body.append(UNRESOLVED_MARKER);
                }
                body.append('\n');
            }
        }
        return body.toString();
    }

    /**
     * {@code src/blotter.ts#BlotterModule.post} for a call inside a class, {@code src/index.ts#load}
     * for a bare function — the container already carries the {@code #} when there is one, so the
     * separator follows from whether a container exists rather than being repeated.
     */
    private static String site(SpringModel.ClientInfo client) {
        String container = client.classFqcn();
        if (container == null || container.isBlank()) {
            return client.methodOrSite();
        }
        if (client.methodOrSite() == null || client.methodOrSite().isBlank()) {
            return container;
        }
        return container + (container.indexOf('#') >= 0 ? "." : "#") + client.methodOrSite();
    }

    /**
     * A stream registration's two checkable axes, from whichever end of it this repo is.
     *
     * <p>The one kind either toolchain can provide, so both extractors run and whichever finds
     * something wins. That is not a fallback: a repo is one or the other, and asking both is how
     * the actualizer avoids needing to know which — the same body comes out either way, which is
     * the entire point of a contract two repos declare identically.
     */
    private static String streamDescriptor(List<ModuleSession> sessions, TsSessions ts) {
        Map<String, Descriptor> descriptors = new LinkedHashMap<>();
        for (ModuleSession module : sessions) {
            for (StreamDescriptorExtractor.Descriptor java
                    : StreamDescriptorExtractor.extract(module.session())) {
                descriptors.putIfAbsent(java.stream(),
                        new Descriptor(java.stream(), java.key(), java.channels()));
            }
        }
        for (TsPackage pkg : ts.packages) {
            TsSidecar.Result result = pkg.streams();
            if (!result.ok()) {
                continue;
            }
            for (JsonNode node : result.json().path("descriptors")) {
                Descriptor descriptor = new Descriptor(node.path("stream").asText(),
                        values(node.path("key")), values(node.path("channels")));
                descriptors.putIfAbsent(descriptor.stream(), descriptor);
            }
        }

        StringBuilder body = new StringBuilder();
        descriptors.values().forEach(descriptor -> {
            appendAxis(body, descriptor.stream(), "key", descriptor.key());
            appendAxis(body, descriptor.stream(), "channels", descriptor.channels());
        });
        return body.toString();
    }

    /** One descriptor as both actualizers see it — the shape is identical by construction, which
     *  is what lets one contract be declared twice with byte-identical bodies. */
    private record Descriptor(String stream, List<String> key, List<String> channels) {
    }

    private static List<String> values(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.isNull() ? null : value.asText()));
        return values;
    }

    /**
     * {@code md key clientId,securityType}. A value that could not be read is written {@code ?}
     * and marks the whole LINE unresolved, because what failed is one entry in an ordered list:
     * the list cannot be confirmed, and no narrower claim than that is true.
     */
    private static void appendAxis(StringBuilder body, String stream, String axis,
                                   List<String> values) {
        if (values.isEmpty()) {
            return;   // an axis a descriptor genuinely does not have is not an unread one
        }
        body.append(stream).append(' ').append(axis).append(' ');
        for (int i = 0; i < values.size(); i++) {
            body.append(i == 0 ? "" : ",").append(values.get(i) == null ? "?" : values.get(i));
        }
        // Not List.contains(null): an immutable list throws on it, and the key axis is always one.
        if (values.stream().anyMatch(java.util.Objects::isNull)) {
            body.append(UNRESOLVED_MARKER);
        }
        body.append('\n');
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
