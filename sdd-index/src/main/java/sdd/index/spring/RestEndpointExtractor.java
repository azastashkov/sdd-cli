package sdd.index.spring;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import sdd.index.source.SourceParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class RestEndpointExtractor {
    private static final Map<String, String> VERB_ANNOTATIONS = Map.of(
            "GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT",
            "DeleteMapping", "DELETE", "PatchMapping", "PATCH");

    private RestEndpointExtractor() {}

    public static List<SpringModel.EndpointInfo> extract(SourceParser.Session session,
                                                         Map<String, String> defaultProps) {
        List<SpringModel.EndpointInfo> out = new ArrayList<>();
        for (SourceParser.ParsedUnit unit : session.units()) {
            for (ClassOrInterfaceDeclaration c : unit.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                boolean rest = AnnotationValues.annotation(c, "RestController").isPresent()
                        || (AnnotationValues.annotation(c, "Controller").isPresent()
                            && AnnotationValues.annotation(c, "ResponseBody").isPresent());
                if (!rest) {
                    continue;
                }
                String fqcn = c.getFullyQualifiedName().orElse(c.getNameAsString());
                List<String> bases = AnnotationValues.annotation(c, "RequestMapping")
                        .map(ann -> resolvePaths(ann, defaultProps)).orElse(List.of(""));
                for (MethodDeclaration m : c.getMethods()) {
                    for (Mapping mapping : mappingsOf(m, defaultProps)) {
                        for (String base : bases) {
                            for (String path : mapping.paths()) {
                                out.add(new SpringModel.EndpointInfo(fqcn, m.getNameAsString(),
                                        mapping.verb(), RouteNormalizer.join(base, path),
                                        requestBodyType(m), m.getType().asString()));
                            }
                        }
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    private record Mapping(String verb, List<String> paths) {}

    private static List<Mapping> mappingsOf(MethodDeclaration m, Map<String, String> props) {
        List<Mapping> mappings = new ArrayList<>();
        for (Map.Entry<String, String> e : VERB_ANNOTATIONS.entrySet()) {
            AnnotationValues.annotation(m, e.getKey()).ifPresent(ann ->
                    mappings.add(new Mapping(e.getValue(), resolvePaths(ann, props))));
        }
        AnnotationValues.annotation(m, "RequestMapping").ifPresent(ann -> {
            List<Expression> methodExprs = AnnotationValues.attrList(ann, "method");
            if (methodExprs.isEmpty()) {
                mappings.add(new Mapping("ANY", resolvePaths(ann, props)));
            } else {
                List<String> paths = resolvePaths(ann, props);
                for (Expression me : methodExprs) {
                    String text = me.toString();
                    int dot = text.lastIndexOf('.');
                    mappings.add(new Mapping(dot >= 0 ? text.substring(dot + 1) : text, paths));
                }
            }
        });
        return mappings;
    }

    private static List<String> resolvePaths(AnnotationExpr ann, Map<String, String> props) {
        List<Expression> exprs = AnnotationValues.attrListAny(ann, "value", "path");
        if (exprs.isEmpty()) {
            return List.of("");
        }
        List<String> paths = new ArrayList<>();
        for (Expression expr : exprs) {
            ValueResolver.Resolved r = ValueResolver.resolve(expr, props);
            paths.add(r.value() != null ? r.value() : "");
        }
        return paths;
    }

    private static String requestBodyType(MethodDeclaration m) {
        return m.getParameters().stream()
                .filter(p -> AnnotationValues.annotation(p, "RequestBody").isPresent())
                .map(p -> p.getType().asString())
                .findFirst().orElse(null);
    }
}
