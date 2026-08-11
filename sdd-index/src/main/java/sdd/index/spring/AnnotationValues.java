package sdd.index.spring;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;

import java.util.List;
import java.util.Optional;

public final class AnnotationValues {
    private AnnotationValues() {}

    public static Optional<AnnotationExpr> annotation(NodeWithAnnotations<?> node, String simpleName) {
        return node.getAnnotations().stream()
                .filter(a -> a.getName().getIdentifier().equals(simpleName))
                .findFirst().map(a -> a);
    }

    public static Optional<Expression> attr(AnnotationExpr ann, String name) {
        if (ann instanceof SingleMemberAnnotationExpr single) {
            return name.equals("value") ? Optional.of(single.getMemberValue()) : Optional.empty();
        }
        if (ann instanceof NormalAnnotationExpr normal) {
            return normal.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals(name))
                    .findFirst().map(p -> p.getValue());
        }
        return Optional.empty();
    }

    public static List<Expression> attrList(AnnotationExpr ann, String name) {
        return attr(ann, name)
                .map(e -> e instanceof ArrayInitializerExpr arr
                        ? List.copyOf(arr.getValues())
                        : List.of(e))
                .orElse(List.of());
    }

    public static List<Expression> attrListAny(AnnotationExpr ann, String first, String second) {
        List<Expression> primary = attrList(ann, first);
        return primary.isEmpty() ? attrList(ann, second) : primary;
    }
}
