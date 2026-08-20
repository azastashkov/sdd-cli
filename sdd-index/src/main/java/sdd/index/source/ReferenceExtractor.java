package sdd.index.source;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
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

    /**
     * Type -> type references, the edge {@link #extract} structurally cannot produce.
     *
     * <p>Two differences from {@code extract}, both deliberate:
     * <ul>
     *   <li><b>Both ends are named.</b> {@code extract} attributes a reference to the compilation
     *       unit and then to the module, so the referring type is gone by the time anything is
     *       stored. Here each reference is attributed to the nearest enclosing declaration that
     *       {@link ApiSurfaceExtractor#isExtractedType} admits, so the {@code from_type_id} foreign
     *       key always has a row to point at. Nearest-enclosing is what stops a nested type's
     *       references being counted a second time against its outer type.
     *   <li><b>No repo type index.</b> {@code extract} routes a target declared in the same repo to
     *       {@code file_ref} and only writes a usage for the rest. Intra-repo edges are precisely
     *       what a type graph is for, so no such filter applies.
     * </ul>
     *
     * <p>Imports are attributed to the compilation unit's primary type. An import belongs to the
     * file, not to any one declaration in it, and spraying it across every nested type would
     * multiply one written line into several claimed references.
     *
     * <p>A type referencing itself is dropped, mirroring {@code extract}'s own
     * {@code !dstRel.equals(unit.relPath())} guard: it is not an edge.
     *
     * <p>Target discovery, the {@code java.}/{@code javax.}/{@code jakarta.} skip, the
     * scope-node guard and the unresolvable-symbol fallbacks are all shared with {@code extract} —
     * this walks the same nodes and asks {@link #resolveType} the same questions, so the two cannot
     * disagree about what a reference is.
     */
    public static List<SourceModel.TypeRef> typeRefs(SourceParser.Session session) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SourceParser.ParsedUnit unit : session.units()) {
            String pkg = unit.cu().getPackageDeclaration()
                    .map(pd -> pd.getNameAsString()).orElse("");

            String primary = primaryTypeFqcn(unit.cu(), pkg);
            if (primary != null) {
                unit.cu().getImports().stream()
                        .filter(i -> !i.isStatic() && !i.isAsterisk())
                        .forEach(i -> record(counts, primary, i.getNameAsString(), "IMPORT"));
            }
            for (ClassOrInterfaceDeclaration c
                    : unit.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                String owner = ownerOfDeclaration(c, pkg);
                if (owner == null) {
                    continue;
                }
                for (ClassOrInterfaceType ext : c.getExtendedTypes()) {
                    resolveType(ext).ifPresent(fqcn -> record(counts, owner, fqcn, "EXTENDS"));
                }
                for (ClassOrInterfaceType impl : c.getImplementedTypes()) {
                    resolveType(impl).ifPresent(fqcn -> record(counts, owner, fqcn, "EXTENDS"));
                }
            }
            for (MethodCallExpr call : unit.cu().findAll(MethodCallExpr.class)) {
                String owner = ownerOfNode(call, pkg);
                if (owner == null) {
                    continue;
                }
                try {
                    record(counts, owner, call.resolve().declaringType().getQualifiedName(), "CALL");
                } catch (Exception | StackOverflowError ignored) {
                    // best-effort, exactly as in extract
                }
            }
            for (com.github.javaparser.ast.expr.ObjectCreationExpr creation
                    : unit.cu().findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class)) {
                String owner = ownerOfNode(creation, pkg);
                if (owner == null) {
                    continue;
                }
                try {
                    record(counts, owner,
                            creation.resolve().declaringType().getQualifiedName(), "CALL");
                } catch (Exception | StackOverflowError ignored) {
                    // best-effort
                }
            }
            for (ClassOrInterfaceType typeRef : unit.cu().findAll(ClassOrInterfaceType.class)) {
                if (isQualifierOfParent(typeRef)) {
                    continue;
                }
                String owner = ownerOfNode(typeRef, pkg);
                if (owner == null) {
                    continue;
                }
                resolveType(typeRef).ifPresent(fqcn -> record(counts, owner, fqcn, "TYPE"));
            }
        }
        List<SourceModel.TypeRef> out = new ArrayList<>();
        counts.forEach((key, count) -> {
            String[] parts = key.split("\\|", 3);
            out.add(new SourceModel.TypeRef(parts[0], parts[1], parts[2], count));
        });
        return List.copyOf(out);
    }

    private static void record(Map<String, Integer> counts, String from, String to, String kind) {
        if (to.startsWith("java.") || to.startsWith("javax.") || to.startsWith("jakarta.")
                || from.equals(to)) {
            return;
        }
        counts.merge(from + "|" + to + "|" + kind, 1, Integer::sum);
    }

    /**
     * The fqcn of the type an arbitrary node's reference should be attributed to: the nearest
     * enclosing declaration the extractor admits, or null when there is none (a reference written
     * inside a package-private type nested in a class, whose outer type is admitted, attributes to
     * that outer type; one with no admitted ancestor at all is dropped rather than invented).
     */
    private static String ownerOfNode(com.github.javaparser.ast.Node node, String pkg) {
        return node.findAncestor(TypeDeclaration.class)
                .map(t -> ownerOfDeclaration(t, pkg)).orElse(null);
    }

    /** As {@link #ownerOfNode}, but considers the declaration itself first. */
    private static String ownerOfDeclaration(TypeDeclaration<?> decl, String pkg) {
        for (TypeDeclaration<?> t = decl; t != null;
                t = t.findAncestor(TypeDeclaration.class).orElse(null)) {
            if (ApiSurfaceExtractor.isExtractedType(t)) {
                return fqcnOf(t, pkg);
            }
        }
        return null;
    }

    /**
     * The primary type of a compilation unit — the one whose name matches the file, else the first
     * top-level declaration in document order. Null for a unit that declares no type at all (a
     * {@code package-info.java}), whose imports then belong to nothing and are dropped.
     */
    private static String primaryTypeFqcn(com.github.javaparser.ast.CompilationUnit cu, String pkg) {
        return cu.getPrimaryType()
                .or(() -> cu.getTypes().isNonEmpty()
                        ? java.util.Optional.of(cu.getTypes().get(0)) : java.util.Optional.empty())
                .filter(ApiSurfaceExtractor::isExtractedType)
                .map(t -> fqcnOf(t, pkg))
                .orElse(null);
    }

    private static String fqcnOf(TypeDeclaration<?> t, String pkg) {
        return t.getFullyQualifiedName()
                .orElse(pkg.isEmpty() ? t.getNameAsString() : pkg + "." + t.getNameAsString());
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
