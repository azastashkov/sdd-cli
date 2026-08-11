package sdd.index.spring;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ValueResolver {
    public enum Resolution { LITERAL, CONSTANT, PROPERTY, DYNAMIC }
    public record Resolved(String value, Resolution resolution, String rawExpr) {}

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");

    private ValueResolver() {}

    public static Resolved resolve(Expression expr, Map<String, String> defaultProfileProps) {
        String raw = expr.toString();
        Optional<Part> part = resolvePart(expr);
        if (part.isEmpty()) {
            return new Resolved(null, Resolution.DYNAMIC, raw);
        }
        return substitute(part.get(), defaultProfileProps, raw);
    }

    private record Part(String text, Resolution origin) {}

    private static Optional<Part> resolvePart(Expression expr) {
        if (expr instanceof StringLiteralExpr lit) {
            return Optional.of(new Part(lit.asString(), Resolution.LITERAL));
        }
        if (expr instanceof BinaryExpr bin && bin.getOperator() == BinaryExpr.Operator.PLUS) {
            Optional<Part> left = resolvePart(bin.getLeft());
            Optional<Part> right = resolvePart(bin.getRight());
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
                    if (v != null && v.getInitializer().isPresent()) {
                        return resolvePart(v.getInitializer().get())
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
     */
    private static Optional<VariableDeclarator> declaratorOf(
            com.github.javaparser.resolution.declarations.ResolvedValueDeclaration resolved) {
        var node = resolved.toAst().orElse(null);
        if (node instanceof VariableDeclarator v) {
            return Optional.of(v);
        }
        if (node instanceof FieldDeclaration fd) {
            return fd.getVariables().stream()
                    .filter(v -> v.getNameAsString().equals(resolved.getName()))
                    .findFirst();
        }
        return Optional.empty();
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
