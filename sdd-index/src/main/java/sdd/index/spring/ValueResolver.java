package sdd.index.spring;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ValueResolver {
    public enum Resolution { LITERAL, CONSTANT, PROPERTY, DYNAMIC }
    public record Resolved(String value, Resolution resolution, String rawExpr) {}

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");

    private ValueResolver() {}

    public static Resolved resolve(Expression expr, Map<String, String> defaultProfileProps) {
        String raw = expr.toString();
        Set<VariableDeclarator> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Optional<Part> part = resolvePart(expr, visited);
        if (part.isEmpty()) {
            return new Resolved(null, Resolution.DYNAMIC, raw);
        }
        return substitute(part.get(), defaultProfileProps, raw);
    }

    private record Part(String text, Resolution origin) {}

    /**
     * {@code visited} is the identity set of {@link VariableDeclarator}s already followed in this
     * resolution chain, threaded through every recursive call from {@link #resolve}. A constant
     * whose initializer refers back to a declarator already on the chain (directly or through
     * intermediates) is a cycle: {@code visited.add(v)} returning {@code false} short-circuits to
     * {@link Optional#empty()} immediately instead of recursing further. This bounds recursion
     * depth by the number of distinct declarators in the chain, rather than relying on the
     * {@code StackOverflowError} catch below to terminate an unbounded cyclic recursion — that
     * catch stays in place purely as a backstop for cases this guard doesn't anticipate.
     */
    private static Optional<Part> resolvePart(Expression expr, Set<VariableDeclarator> visited) {
        if (expr instanceof StringLiteralExpr lit) {
            return Optional.of(new Part(lit.asString(), Resolution.LITERAL));
        }
        if (expr instanceof BinaryExpr bin && bin.getOperator() == BinaryExpr.Operator.PLUS) {
            Optional<Part> left = resolvePart(bin.getLeft(), visited);
            Optional<Part> right = resolvePart(bin.getRight(), visited);
            if (left.isPresent() && right.isPresent()) {
                Resolution origin = left.get().origin() == Resolution.CONSTANT
                        || right.get().origin() == Resolution.CONSTANT
                        ? Resolution.CONSTANT : Resolution.LITERAL;
                return Optional.of(new Part(left.get().text() + right.get().text(), origin));
            }
            return Optional.empty();
        }
        if (expr instanceof NameExpr || expr instanceof FieldAccessExpr) {
            try {
                var resolved = expr instanceof NameExpr n ? n.resolve() : ((FieldAccessExpr) expr).resolve();
                if (resolved instanceof com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration
                        || resolved instanceof com.github.javaparser.resolution.declarations.ResolvedValueDeclaration) {
                    VariableDeclarator v = declaratorOf(resolved).orElse(null);
                    if (v != null && v.getInitializer().isPresent() && visited.add(v)) {
                        return resolvePart(v.getInitializer().get(), visited)
                                .map(p -> new Part(p.text(), Resolution.CONSTANT));
                    }
                }
            } catch (Exception | StackOverflowError ignored) {
                // fall through to empty
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * On JavaParser 3.26.2, {@code ResolvedFieldDeclaration.toAst()} (inherited from
     * {@code AssociableToAST}) resolves to the enclosing {@link FieldDeclaration} node, not
     * directly to the {@link VariableDeclarator} — a field declaration can list several
     * variables ({@code static final String A = "1", B = "2";}), so the declarator matching
     * the resolved symbol's name has to be picked out explicitly.
     *
     * <p>Rung 2 of the ladder is scoped to {@code static final} fields only (spec: "a static
     * final String with a literal initializer"). A non-static field can vary per instance and a
     * non-final field can be reassigned, so neither is a safe compile-time constant to fold —
     * this method returns empty for both, which sends the caller to {@code DYNAMIC} instead of
     * mislabeling a mutable value as {@code CONSTANT}.
     */
    private static Optional<VariableDeclarator> declaratorOf(
            com.github.javaparser.resolution.declarations.ResolvedValueDeclaration resolved) {
        var node = resolved.toAst().orElse(null);
        FieldDeclaration fd;
        VariableDeclarator v;
        if (node instanceof FieldDeclaration f) {
            fd = f;
            v = f.getVariables().stream()
                    .filter(candidate -> candidate.getNameAsString().equals(resolved.getName()))
                    .findFirst()
                    .orElse(null);
        } else if (node instanceof VariableDeclarator candidate) {
            v = candidate;
            fd = candidate.getParentNode()
                    .filter(FieldDeclaration.class::isInstance)
                    .map(FieldDeclaration.class::cast)
                    .orElse(null);
        } else {
            return Optional.empty();
        }
        if (v == null || fd == null || !fd.isStatic() || !fd.isFinal()) {
            return Optional.empty();
        }
        return Optional.of(v);
    }

    private static Resolved substitute(Part part, Map<String, String> props, String raw) {
        Matcher m = PLACEHOLDER.matcher(part.text());
        if (!m.find()) {
            return new Resolved(part.text(), part.origin(), raw);
        }
        m.reset();
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String fallback = m.group(2);
            String value = props.getOrDefault(key, fallback);
            if (value == null) {
                return new Resolved(null, Resolution.DYNAMIC, raw);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return new Resolved(out.toString(), Resolution.PROPERTY, raw);
    }
}
