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
                } catch (Exception | StackOverflowError ignored) {
                    // best-effort: unresolvable call sites are skipped. StackOverflowError is in
                    // the list because the symbol solver recurses without bound on deep generics;
                    // it unwinds harmlessly here and costs us one call site, not the run.
                }
            }
            for (com.github.javaparser.ast.expr.ObjectCreationExpr creation
                    : unit.cu().findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class)) {
                try {
                    targets.add(new Target(
                            creation.resolve().declaringType().getQualifiedName(), "CALL"));
                } catch (Exception | StackOverflowError ignored) {
                    // best-effort
                }
            }
            for (ClassOrInterfaceType typeRef : unit.cu().findAll(ClassOrInterfaceType.class)) {
                if (isQualifierOfParent(typeRef)) {
                    // JavaParser models "a.b.C" as nested ClassOrInterfaceType nodes (C scoped by
                    // "a.b", scoped by "a"); findAll walks into those scope nodes too. Only the
                    // outermost node is a real reference — the scope chain is packaging syntax,
                    // not a separate usage — so skip anything that is itself another node's scope.
                    continue;
                }
                resolveType(typeRef).ifPresent(fqcn -> targets.add(new Target(fqcn, "TYPE")));
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

    /**
     * True when {@code type} is the scope of its own parent {@link ClassOrInterfaceType} — i.e.
     * it is a package/outer-class segment inside a longer qualified name ("acme" inside
     * "com.acme.Foo"), not a standalone type usage in its own right.
     */
    private static boolean isQualifierOfParent(ClassOrInterfaceType type) {
        return type.getParentNode()
                .filter(ClassOrInterfaceType.class::isInstance)
                .map(ClassOrInterfaceType.class::cast)
                .flatMap(ClassOrInterfaceType::getScope)
                .filter(scope -> scope == type)
                .isPresent();
    }

    private static java.util.Optional<String> resolveType(ClassOrInterfaceType type) {
        try {
            return java.util.Optional.of(type.resolve().asReferenceType().getQualifiedName());
        } catch (Exception | StackOverflowError e) {
            // see the call-site catch above: an unresolvable (or stack-blowing) supertype is
            // skipped rather than sinking the module. Exception: a type written with an explicit
            // *package* qualifier ("com.acme.pricing.PriceCalculator") that the symbol solver
            // can't back with a source file or classpath jar (e.g. a cross-repo type parsed
            // without its producer's jar on the classpath) still names its target unambiguously in
            // the source text; trust that literal spelling instead of dropping the reference, the
            // same way IMPORT targets are read verbatim below.
            //
            // getScope().isPresent() alone is NOT enough of a guard: a partially-qualified
            // use-site reference like "Outer.Inner" or "Foo.FooBuilder" also has a scope, but its
            // literal text is not a valid fqcn (the true package prefix is missing) — leaking that
            // as a "usage" would sit forever as a NULL-target api_usage row (UsageLinker never
            // matches it to a java_type, and unresolved rows are deliberately never deleted, see
            // UsageLinker's javadoc). Unlike imports, which are always fully-qualified by Java
            // grammar, use-site qualified names are not. Package names are conventionally
            // lowercase and type names are conventionally capitalized, so require the written
            // text's first character to be lowercase before trusting it as a real fqcn; a bare
            // name or a capitalized (nested-type-shaped) qualified name stays dropped, exactly as
            // it was pre-widening.
            String written = type.getNameWithScope();
            if (type.getScope().isPresent() && !written.isEmpty()
                    && Character.isLowerCase(written.charAt(0))) {
                return java.util.Optional.of(written);
            }
            return java.util.Optional.empty();
        }
    }
}
