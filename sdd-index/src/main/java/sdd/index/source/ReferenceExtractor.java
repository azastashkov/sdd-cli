package sdd.index.source;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ReferenceExtractor {
    public record Refs(List<SourceModel.UsageRef> usages, List<SourceModel.FileRef> fileRefs) {}

    private ReferenceExtractor() {}

    public static Refs extract(SourceParser.Session session, Map<String, String> repoTypeIndex) {
        Set<String> usageKeys = new LinkedHashSet<>();
        List<SourceModel.UsageRef> usages = new ArrayList<>();
        Map<String, Integer> fileRefCounts = new LinkedHashMap<>();

        for (SourceParser.ParsedUnit unit : session.units()) {
            List<Target> targets = new ArrayList<>();
            unit.cu().getImports().stream()
                    .filter(i -> !i.isStatic() && !i.isAsterisk())
                    .forEach(i -> targets.add(new Target(i.getNameAsString(), "IMPORT")));
            for (ClassOrInterfaceDeclaration c : unit.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                for (ClassOrInterfaceType ext : c.getExtendedTypes()) {
                    resolveType(ext).ifPresent(fqcn -> targets.add(new Target(fqcn, "EXTENDS")));
                }
                for (ClassOrInterfaceType impl : c.getImplementedTypes()) {
                    resolveType(impl).ifPresent(fqcn -> targets.add(new Target(fqcn, "EXTENDS")));
                }
            }
            for (MethodCallExpr call : unit.cu().findAll(MethodCallExpr.class)) {
                try {
                    String declaring = call.resolve().declaringType().getQualifiedName();
                    targets.add(new Target(declaring, "CALL"));
                } catch (Exception ignored) {
                    // best-effort: unresolvable call sites are skipped
                }
            }

            for (Target target : targets) {
                if (target.fqcn.startsWith("java.") || target.fqcn.startsWith("javax.")
                        || target.fqcn.startsWith("jakarta.")) {
                    continue;
                }
                String dstRel = repoTypeIndex.get(target.fqcn);
                if (dstRel != null) {
                    if (!dstRel.equals(unit.relPath())) {
                        fileRefCounts.merge(unit.relPath() + "|" + dstRel, 1, Integer::sum);
                    }
                } else if (usageKeys.add(target.fqcn + ":" + target.refKind)) {
                    usages.add(new SourceModel.UsageRef(target.fqcn, target.refKind));
                }
            }
        }
        List<SourceModel.FileRef> fileRefs = fileRefCounts.entrySet().stream()
                .map(e -> {
                    String[] parts = e.getKey().split("\\|", 2);
                    return new SourceModel.FileRef(parts[0], parts[1], e.getValue());
                }).toList();
        return new Refs(List.copyOf(usages), fileRefs);
    }

    private record Target(String fqcn, String refKind) {}

    private static java.util.Optional<String> resolveType(ClassOrInterfaceType type) {
        try {
            return java.util.Optional.of(type.resolve().asReferenceType().getQualifiedName());
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }
}
