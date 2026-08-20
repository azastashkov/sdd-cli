package sdd.index.source;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.util.ArrayList;
import java.util.List;

/**
 * Names a declaration's supertypes, deterministically and without the symbol solver.
 *
 * <p>{@link ApiSurfaceExtractor} is a pure-AST walk today, and that is worth keeping: it runs over
 * every type in the estate, while the solver is the expensive part of indexing and already fails
 * often enough that {@code ReferenceExtractor} catches {@code StackOverflowError} around it. So this
 * resolves against the compilation unit's own import list instead — which is exact for the case that
 * matters, since a cross-repo supertype must be imported by name to be referenced at all.
 *
 * <p>Every rung records HOW it resolved. {@code UNRESOLVED} is a row, not a dropped one: "no
 * subtypes of X" and "subtypes we could not place" are different answers, and the KB may not render
 * them the same.
 */
final class SupertypeResolver {

    private SupertypeResolver() {
    }

    static List<SourceModel.SupertypeRef> resolve(CompilationUnit cu, ClassOrInterfaceDeclaration decl) {
        List<SourceModel.SupertypeRef> out = new ArrayList<>();
        for (ClassOrInterfaceType t : decl.getExtendedTypes()) {
            out.add(one(cu, t, decl.isInterface() ? "EXTENDS" : "EXTENDS"));
        }
        for (ClassOrInterfaceType t : decl.getImplementedTypes()) {
            out.add(one(cu, t, "IMPLEMENTS"));
        }
        return out;
    }

    private static SourceModel.SupertypeRef one(CompilationUnit cu, ClassOrInterfaceType type,
                                                String relation) {
        String written = type.getNameWithScope();

        // A package-qualified spelling names its target unambiguously whatever the classpath holds.
        // Requiring a lowercase-leading first segment is the same guard ReferenceExtractor applies,
        // and for the same reason: "Outer.Inner" also has a scope but its literal text is not an
        // fqcn, and leaking that would sit forever as an unmatchable row.
        if (type.getScope().isPresent() && !written.isEmpty()
                && Character.isLowerCase(written.charAt(0))) {
            return new SourceModel.SupertypeRef(written, relation, "WRITTEN");
        }

        String simple = type.getNameAsString();
        for (var imp : cu.getImports()) {
            if (imp.isStatic() || imp.isAsterisk()) {
                continue;
            }
            String name = imp.getNameAsString();
            if (name.endsWith("." + simple)) {
                return new SourceModel.SupertypeRef(name, relation, "IMPORT");
            }
        }

        // No import names it, so within Java's rules it is either package-local or came in on a
        // wildcard. Package-local is by far the commoner, so that is the guess -- and it is
        // recorded AS a guess, which is what the SAME_PACKAGE resolution value is for.
        //
        // Nothing re-checks it. An earlier version of this comment claimed a HierarchyLinker did;
        // no such class has ever existed, and V6's supertype_module_id column it would have filled
        // is still written by nobody and read by nobody. Building one now would add a second
        // unread column rather than remove the first.
        //
        // Leaving the guess unchecked is safe in the direction that matters. The only consumer,
        // KbHierarchy, joins supertype_fqcn by name, so a wrong guess names a type that exists
        // nowhere and simply matches nothing: it costs a missed subtype, never an invented one.
        // Reversing that -- resolving the guess against real types and rewriting supertype_fqcn --
        // is what would risk asserting a hierarchy edge the source does not have, and would also
        // make `resolution` a lie about how the row was arrived at.
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        return pkg.isEmpty()
                ? new SourceModel.SupertypeRef(simple, relation, "UNRESOLVED")
                : new SourceModel.SupertypeRef(pkg + "." + simple, relation, "SAME_PACKAGE");
    }
}
