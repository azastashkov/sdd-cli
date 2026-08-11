package sdd.index.spring;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import sdd.index.source.SourceParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class RestClientExtractor {
    private static final Map<String, String> TEMPLATE_VERBS = Map.of(
            "getForObject", "GET", "getForEntity", "GET",
            "postForObject", "POST", "postForEntity", "POST",
            "exchange", "ANY", "put", "PUT", "delete", "DELETE", "patchForObject", "PATCH");
    private static final Map<String, String> FEIGN_VERBS = Map.of(
            "GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT",
            "DeleteMapping", "DELETE", "PatchMapping", "PATCH");
    private static final Set<String> CHAIN_VERBS = Set.of("get", "post", "put", "delete", "patch");

    private RestClientExtractor() {}

    public static List<SpringModel.ClientInfo> extract(SourceParser.Session session,
                                                       Map<String, String> defaultProps) {
        List<SpringModel.ClientInfo> out = new ArrayList<>();
        for (SourceParser.ParsedUnit unit : session.units()) {
            extractFeign(unit, defaultProps, out);
            extractCallSites(unit, defaultProps, out);
        }
        return List.copyOf(out);
    }

    private static void extractFeign(SourceParser.ParsedUnit unit, Map<String, String> props,
                                     List<SpringModel.ClientInfo> out) {
        for (ClassOrInterfaceDeclaration c : unit.cu().findAll(ClassOrInterfaceDeclaration.class)) {
            var feign = AnnotationValues.annotation(c, "FeignClient");
            if (!c.isInterface() || feign.isEmpty()) {
                continue;
            }
            String fqcn = c.getFullyQualifiedName().orElse(c.getNameAsString());
            String targetHint = AnnotationValues.attr(feign.get(), "url")
                    .or(() -> AnnotationValues.attr(feign.get(), "name"))
                    .or(() -> AnnotationValues.attr(feign.get(), "value"))
                    .map(expr -> {
                        ValueResolver.Resolved r = ValueResolver.resolve(expr, props);
                        return r.value() != null ? r.value() : r.rawExpr();
                    }).orElse(null);
            ValueResolver.Resolved baseResolved = AnnotationValues.attr(feign.get(), "path")
                    .map(expr -> ValueResolver.resolve(expr, props))
                    .orElse(new ValueResolver.Resolved("", ValueResolver.Resolution.LITERAL, ""));
            String feignBase = baseResolved.value() != null ? baseResolved.value() : "";
            // A class-level @RequestMapping on the @FeignClient interface (pre-2020 Spring Cloud
            // style) contributes an additional base-path segment ahead of every method mapping.
            // v1: only the first value/path entry is folded in — multiple class-level paths are
            // rare enough to leave for a later pass.
            String classPath = AnnotationValues.annotation(c, "RequestMapping")
                    .map(ann -> AnnotationValues.attrListAny(ann, "value", "path"))
                    .filter(paths -> !paths.isEmpty())
                    .map(paths -> Optional.ofNullable(ValueResolver.resolve(paths.get(0), props).value())
                            .orElse(""))
                    .orElse("");
            String base = RouteNormalizer.join(feignBase, classPath);
            for (MethodDeclaration m : c.getMethods()) {
                for (Map.Entry<String, String> verb : FEIGN_VERBS.entrySet()) {
                    AnnotationValues.annotation(m, verb.getKey()).ifPresent(ann ->
                            emitFeignRoutes(out, fqcn, m, base, targetHint, baseResolved, props,
                                    List.of(verb.getValue()), ann));
                }
                // Pre-2020 Spring Cloud style: methods mapped with the generic @RequestMapping
                // instead of one of the five verb-shortcut annotations above.
                AnnotationValues.annotation(m, "RequestMapping").ifPresent(ann ->
                        emitFeignRoutes(out, fqcn, m, base, targetHint, baseResolved, props,
                                requestMappingVerbs(ann), ann));
            }
        }
    }

    private static List<String> requestMappingVerbs(AnnotationExpr ann) {
        List<Expression> methodExprs = AnnotationValues.attrList(ann, "method");
        if (methodExprs.isEmpty()) {
            return List.of("ANY");
        }
        List<String> verbs = new ArrayList<>();
        for (Expression me : methodExprs) {
            String text = me.toString();
            int dot = text.lastIndexOf('.');
            verbs.add(dot >= 0 ? text.substring(dot + 1) : text);
        }
        return verbs;
    }

    private static void emitFeignRoutes(List<SpringModel.ClientInfo> out, String fqcn, MethodDeclaration m,
                                        String base, String targetHint, ValueResolver.Resolved baseResolved,
                                        Map<String, String> props, List<String> verbs, AnnotationExpr ann) {
        List<Expression> paths = AnnotationValues.attrListAny(ann, "value", "path");
        for (String verb : verbs) {
            if (paths.isEmpty()) {
                // No method-level path: the row's resolution/rawExpr describe how the base
                // itself resolved (e.g. a property placeholder), not a hardcoded literal.
                out.add(new SpringModel.ClientInfo("FEIGN", fqcn, m.getNameAsString(), verb,
                        RouteNormalizer.join(base, ""), targetHint,
                        baseResolved.resolution().name(), baseResolved.rawExpr()));
                continue;
            }
            for (Expression pathExpr : paths) {
                ValueResolver.Resolved r = ValueResolver.resolve(pathExpr, props);
                out.add(new SpringModel.ClientInfo("FEIGN", fqcn, m.getNameAsString(), verb,
                        r.value() != null ? RouteNormalizer.join(base, r.value()) : null,
                        targetHint, r.resolution().name(), r.rawExpr()));
            }
        }
    }

    private static void extractCallSites(SourceParser.ParsedUnit unit, Map<String, String> props,
                                         List<SpringModel.ClientInfo> out) {
        for (MethodCallExpr call : unit.cu().findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (TEMPLATE_VERBS.containsKey(name) && receiverIsRestTemplate(call)) {
                emitSite(unit, call, "RESTTEMPLATE", TEMPLATE_VERBS.get(name), props, out);
            } else if (name.equals("uri")) {
                chainVerbAndKind(call).ifPresent(vk ->
                        emitSite(unit, call, vk.kind(), vk.verb(), props, out));
            }
        }
    }

    private static void emitSite(SourceParser.ParsedUnit unit, MethodCallExpr call, String kind,
                                 String verb, Map<String, String> props,
                                 List<SpringModel.ClientInfo> out) {
        if (call.getArguments().isEmpty()) {
            return;
        }
        ValueResolver.Resolved r = ValueResolver.resolve(call.getArgument(0), props);
        String fqcn = enclosingTypeFqcn(call);
        String site = call.findAncestor(MethodDeclaration.class)
                .map(MethodDeclaration::getNameAsString).orElse("<init>");
        out.add(new SpringModel.ClientInfo(kind, fqcn, site, verb,
                r.value(), null, r.resolution().name(), r.rawExpr()));
    }

    private static boolean receiverIsRestTemplate(MethodCallExpr call) {
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) {
            return false;
        }
        try {
            String qualified = scope.get().calculateResolvedType().describe();
            if (qualified.endsWith("RestTemplate")) {
                return true;
            }
        } catch (Exception | StackOverflowError ignored) {
            // fall through to text heuristic
        }
        return scope.get().toString().toLowerCase(Locale.ROOT).contains("resttemplate");
    }

    private record VerbKind(String verb, String kind) {}

    private static Optional<VerbKind> chainVerbAndKind(MethodCallExpr uriCall) {
        String chainText = uriCall.toString();
        String kind = null;
        try {
            Optional<Expression> scope = uriCall.getScope();
            if (scope.isPresent()) {
                String resolved = scope.get().calculateResolvedType().describe();
                if (resolved.contains("WebClient")) {
                    kind = "WEBCLIENT";
                } else if (resolved.contains("RestClient")) {
                    kind = "RESTCLIENT";
                }
            }
        } catch (Exception | StackOverflowError ignored) {
            // text heuristic below
        }
        if (kind == null) {
            if (chainText.contains("webClient") || chainText.contains("WebClient")) {
                kind = "WEBCLIENT";
            } else if (chainText.contains("restClient") || chainText.contains("RestClient")) {
                kind = "RESTCLIENT";
            } else {
                return Optional.empty();
            }
        }
        Expression scope = uriCall.getScope().orElse(null);
        while (scope instanceof MethodCallExpr chained) {
            if (CHAIN_VERBS.contains(chained.getNameAsString())) {
                return Optional.of(new VerbKind(
                        chained.getNameAsString().toUpperCase(Locale.ROOT), kind));
            }
            scope = chained.getScope().orElse(null);
        }
        return Optional.empty();
    }

    private static String enclosingTypeFqcn(MethodCallExpr call) {
        return call.findAncestor(ClassOrInterfaceDeclaration.class)
                .flatMap(ClassOrInterfaceDeclaration::getFullyQualifiedName)
                .orElse("unknown");
    }
}
