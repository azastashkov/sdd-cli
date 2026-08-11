package sdd.index.spring;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import sdd.index.source.SourceParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class KafkaExtractor {
    public record KafkaResult(List<SpringModel.KafkaUse> uses, boolean streamDetected) {}

    private KafkaExtractor() {}

    public static KafkaResult extract(SourceParser.Session session, Map<String, String> defaultProps,
                                      List<Path> classpathJars, Collection<String> allConfigKeys) {
        List<SpringModel.KafkaUse> uses = new ArrayList<>();
        for (SourceParser.ParsedUnit unit : session.units()) {
            for (MethodDeclaration m : unit.cu().findAll(MethodDeclaration.class)) {
                AnnotationValues.annotation(m, "KafkaListener").ifPresent(ann ->
                        extractListener(m, ann, defaultProps, uses));
            }
            for (MethodCallExpr call : unit.cu().findAll(MethodCallExpr.class)) {
                if (call.getNameAsString().equals("send") && receiverIsKafkaTemplate(call)
                        && !call.getArguments().isEmpty()) {
                    extractSend(call, defaultProps, uses);
                }
            }
        }
        boolean stream = classpathJars.stream().anyMatch(j ->
                        j.getFileName().toString().contains("spring-cloud-stream"))
                || allConfigKeys.stream().anyMatch(k -> k.startsWith("spring.cloud.stream"));
        return new KafkaResult(List.copyOf(uses), stream);
    }

    private static void extractListener(MethodDeclaration m,
                                        com.github.javaparser.ast.expr.AnnotationExpr ann,
                                        Map<String, String> props, List<SpringModel.KafkaUse> uses) {
        String fqcn = m.findAncestor(ClassOrInterfaceDeclaration.class)
                .flatMap(ClassOrInterfaceDeclaration::getFullyQualifiedName).orElse("unknown");
        String groupId = AnnotationValues.attr(ann, "groupId")
                .map(e -> ValueResolver.resolve(e, props).value()).orElse(null);
        String payloadType = m.getParameters().isEmpty() ? null
                : m.getParameter(0).getType().asString();
        for (Expression topicExpr : AnnotationValues.attrListAny(ann, "topics", "value")) {
            ValueResolver.Resolved r = ValueResolver.resolve(topicExpr, props);
            uses.add(new SpringModel.KafkaUse(
                    r.value() != null ? r.value() : r.rawExpr(), "CONSUMER", fqcn,
                    groupId, payloadType, r.resolution().name(), r.rawExpr()));
        }
        AnnotationValues.attr(ann, "topicPattern").ifPresent(patternExpr -> {
            ValueResolver.Resolved r = ValueResolver.resolve(patternExpr, props);
            uses.add(new SpringModel.KafkaUse(
                    r.value() != null ? r.value() : r.rawExpr(), "CONSUMER", fqcn,
                    groupId, payloadType, "DYNAMIC", r.rawExpr()));
        });
    }

    private static void extractSend(MethodCallExpr call, Map<String, String> props,
                                    List<SpringModel.KafkaUse> uses) {
        String fqcn = call.findAncestor(ClassOrInterfaceDeclaration.class)
                .flatMap(ClassOrInterfaceDeclaration::getFullyQualifiedName).orElse("unknown");
        ValueResolver.Resolved r = ValueResolver.resolve(call.getArgument(0), props);
        String payloadType = null;
        if (call.getArguments().size() >= 2) {
            try {
                payloadType = call.getArgument(1).calculateResolvedType().describe();
            } catch (Exception | StackOverflowError ignored) {
                // best-effort
            }
        }
        uses.add(new SpringModel.KafkaUse(
                r.value() != null ? r.value() : r.rawExpr(), "PRODUCER", fqcn,
                null, payloadType, r.resolution().name(), r.rawExpr()));
    }

    private static boolean receiverIsKafkaTemplate(MethodCallExpr call) {
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) {
            return false;
        }
        try {
            if (scope.get().calculateResolvedType().describe().contains("KafkaTemplate")) {
                return true;
            }
        } catch (Exception | StackOverflowError ignored) {
            // fall through
        }
        return scope.get().toString().toLowerCase(Locale.ROOT).contains("kafkatemplate");
    }
}
