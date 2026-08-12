package sdd.index.spring;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import sdd.core.route.Routes;
import sdd.index.source.SourceParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RestClientExtractor {
    private static final Map<String, String> TEMPLATE_VERBS = Map.of(
            "getForObject", "GET", "getForEntity", "GET",
            "postForObject", "POST", "postForEntity", "POST",
            "exchange", "ANY", "put", "PUT", "delete", "DELETE", "patchForObject", "PATCH");
    private static final Map<String, String> FEIGN_VERBS = Map.of(
            "GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT",
            "DeleteMapping", "DELETE", "PatchMapping", "PATCH");
    private static final Set<String> CHAIN_VERBS = Set.of("get", "post", "put", "delete", "patch");
    private static final Set<String> TEMPLATE_TYPE_SUFFIXES =
            Set.of("RestTemplate", "RestOperations", "TestRestTemplate");
    private static final Pattern HTTP_METHOD_ARG = Pattern.compile("HttpMethod\\.([A-Z]+)");

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
            String base = Routes.join(feignBase, classPath);
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
                        Routes.join(base, ""), targetHint,
                        baseResolved.resolution().name(), baseResolved.rawExpr()));
                continue;
            }
            for (Expression pathExpr : paths) {
                ValueResolver.Resolved r = ValueResolver.resolve(pathExpr, props);
                out.add(new SpringModel.ClientInfo("FEIGN", fqcn, m.getNameAsString(), verb,
                        r.value() != null ? Routes.join(base, r.value()) : null,
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
            // Resolution succeeded: this is a definite answer. Accept only the known
            // RestTemplate-family types; do NOT fall back to the name heuristic for a
            // resolved-but-wrong type (that would let e.g. a HashMap field named
            // "restTemplateCache" rescue itself via a lucky name).
            return isTemplateTypeName(qualified);
        } catch (Exception | StackOverflowError ignored) {
            // Resolution failed (e.g. unresolvable/synthetic scope): fall back to the
            // text heuristic, which is our only signal in that case.
            return scope.get().toString().toLowerCase(Locale.ROOT).contains("resttemplate");
        }
    }

    private static boolean isTemplateTypeName(String qualified) {
        for (String suffix : TEMPLATE_TYPE_SUFFIXES) {
            if (qualified.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private record VerbKind(String verb, String kind) {}

    private static Optional<VerbKind> chainVerbAndKind(MethodCallExpr uriCall) {
        String kind = resolveChainKind(uriCall).orElse(null);
        if (kind == null) {
            return Optional.empty();
        }
        Expression scope = uriCall.getScope().orElse(null);
        while (scope instanceof MethodCallExpr chained) {
            String chainedName = chained.getNameAsString();
            if (CHAIN_VERBS.contains(chainedName)) {
                return Optional.of(new VerbKind(chainedName.toUpperCase(Locale.ROOT), kind));
            }
            if (chainedName.equals("method")) {
                return Optional.of(new VerbKind(httpMethodArgVerb(chained), kind));
            }
            scope = chained.getScope().orElse(null);
        }
        return Optional.empty();
    }

    private static Optional<String> resolveChainKind(MethodCallExpr uriCall) {
        Optional<Expression> scope = uriCall.getScope();
        if (scope.isEmpty()) {
            return Optional.empty();
        }
        try {
            String resolved = scope.get().calculateResolvedType().describe();
            // Resolution succeeded: this is a definite answer. Accept only WebClient/RestClient;
            // do NOT fall back to the text heuristic for a resolved-but-wrong type.
            if (resolved.contains("WebClient")) {
                return Optional.of("WEBCLIENT");
            } else if (resolved.contains("RestClient")) {
                return Optional.of("RESTCLIENT");
            }
            return Optional.empty();
        } catch (Exception | StackOverflowError ignored) {
            // Resolution failed: fall back to the text heuristic, our only signal here.
            String chainText = uriCall.toString();
            if (chainText.contains("webClient") || chainText.contains("WebClient")) {
                return Optional.of("WEBCLIENT");
            } else if (chainText.contains("restClient") || chainText.contains("RestClient")) {
                return Optional.of("RESTCLIENT");
            }
            return Optional.empty();
        }
    }

    private static String httpMethodArgVerb(MethodCallExpr methodCall) {
        if (methodCall.getArguments().isEmpty()) {
            return "ANY";
        }
        String argText = methodCall.getArgument(0).toString();
        Matcher matcher = HTTP_METHOD_ARG.matcher(argText);
        return matcher.find() ? matcher.group(1) : "ANY";
    }

    private static String enclosingTypeFqcn(MethodCallExpr call) {
        return call.findAncestor(ClassOrInterfaceDeclaration.class)
                .flatMap(ClassOrInterfaceDeclaration::getFullyQualifiedName)
                .orElse("unknown");
    }
}
