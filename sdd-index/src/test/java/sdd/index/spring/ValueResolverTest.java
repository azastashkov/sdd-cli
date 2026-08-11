package sdd.index.spring;

import com.github.javaparser.ast.expr.Expression;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.index.source.SourceParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ValueResolverTest {
    @TempDir Path repo;

    private static final Map<String, String> PROPS = Map.of(
            "billing.base-url", "http://billing:8080",
            "kafka.topic", "orders.v1");

    /** Parses a class whose field initializers are the expressions under test. */
    private List<Expression> parseInitializers(String fieldsSource) throws Exception {
        Path f = repo.resolve("src/main/java/com/acme/T.java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, """
                package com.acme;
                public class T {
                    static final String BASE = "/api";
                    static final String COMPOSED = BASE + "/v2";
                %s
                }
                """.formatted(fieldsSource));
        var session = SourceParser.parseModule(repo, repo, List.of());
        var cu = session.units().get(0).cu();
        return cu.findAll(com.github.javaparser.ast.body.FieldDeclaration.class).stream()
                .filter(fd -> fd.getVariable(0).getNameAsString().startsWith("X"))
                .map(fd -> fd.getVariable(0).getInitializer().orElseThrow())
                .toList();
    }

    @Test
    void ladderRungs() throws Exception {
        List<Expression> exprs = parseInitializers("""
                    Object X1 = "/orders";
                    Object X2 = BASE;
                    Object X3 = BASE + "/orders";
                    Object X4 = "${billing.base-url}/pay";
                    Object X5 = "${missing.key}/x";
                    Object X6 = "${missing.key:fallback}/x";
                    Object X7 = System.getenv("URL");
                    Object X8 = COMPOSED;
                """);

        assertThat(ValueResolver.resolve(exprs.get(0), PROPS))
                .isEqualTo(new ValueResolver.Resolved("/orders", ValueResolver.Resolution.LITERAL, "\"/orders\""));
        var x2 = ValueResolver.resolve(exprs.get(1), PROPS);
        assertThat(x2.value()).isEqualTo("/api");
        assertThat(x2.resolution()).isEqualTo(ValueResolver.Resolution.CONSTANT);
        var x3 = ValueResolver.resolve(exprs.get(2), PROPS);
        assertThat(x3.value()).isEqualTo("/api/orders");
        assertThat(x3.resolution()).isEqualTo(ValueResolver.Resolution.CONSTANT);
        var x4 = ValueResolver.resolve(exprs.get(3), PROPS);
        assertThat(x4.value()).isEqualTo("http://billing:8080/pay");
        assertThat(x4.resolution()).isEqualTo(ValueResolver.Resolution.PROPERTY);
        var x5 = ValueResolver.resolve(exprs.get(4), PROPS);
        assertThat(x5.value()).isNull();
        assertThat(x5.resolution()).isEqualTo(ValueResolver.Resolution.DYNAMIC);
        var x6 = ValueResolver.resolve(exprs.get(5), PROPS);
        assertThat(x6.value()).isEqualTo("fallback/x");
        assertThat(x6.resolution()).isEqualTo(ValueResolver.Resolution.PROPERTY);
        var x7 = ValueResolver.resolve(exprs.get(6), PROPS);
        assertThat(x7.value()).isNull();
        assertThat(x7.resolution()).isEqualTo(ValueResolver.Resolution.DYNAMIC);
        assertThat(x7.rawExpr()).contains("System.getenv");
        var x8 = ValueResolver.resolve(exprs.get(7), PROPS);
        assertThat(x8.value()).isEqualTo("/api/v2");
        assertThat(x8.resolution()).isEqualTo(ValueResolver.Resolution.CONSTANT);
    }

    @Test
    void instanceFieldInitializerIsDynamicNotConstant() throws Exception {
        List<Expression> exprs = parseInitializers("""
                    private String mutable = "instance-value";
                    Object X1 = mutable;
                """);
        var x1 = ValueResolver.resolve(exprs.get(0), PROPS);
        assertThat(x1.value()).isNull();
        assertThat(x1.resolution()).isEqualTo(ValueResolver.Resolution.DYNAMIC);
    }

    @Test
    void staticNonFinalFieldIsDynamic() throws Exception {
        List<Expression> exprs = parseInitializers("""
                    static String s = "x";
                    Object X1 = s;
                """);
        var x1 = ValueResolver.resolve(exprs.get(0), PROPS);
        assertThat(x1.value()).isNull();
        assertThat(x1.resolution()).isEqualTo(ValueResolver.Resolution.DYNAMIC);
    }

    @Test
    void multiVariableDeclarationResolvesEachIndependently() throws Exception {
        List<Expression> exprs = parseInitializers("""
                    static final String A = "one", B = "two";
                    Object X1 = A;
                    Object X2 = B;
                """);
        var x1 = ValueResolver.resolve(exprs.get(0), PROPS);
        assertThat(x1.value()).isEqualTo("one");
        assertThat(x1.resolution()).isEqualTo(ValueResolver.Resolution.CONSTANT);
        var x2 = ValueResolver.resolve(exprs.get(1), PROPS);
        assertThat(x2.value()).isEqualTo("two");
        assertThat(x2.resolution()).isEqualTo(ValueResolver.Resolution.CONSTANT);
    }

    @Test
    void adjacentPlaceholdersBothSubstitute() throws Exception {
        List<Expression> exprs = parseInitializers("""
                    Object X1 = "${a}${b}";
                """);
        Map<String, String> props = Map.of("a", "AA", "b", "BB");
        var x1 = ValueResolver.resolve(exprs.get(0), props);
        assertThat(x1.value()).isEqualTo("AABB");
        assertThat(x1.resolution()).isEqualTo(ValueResolver.Resolution.PROPERTY);
    }

    @Test
    void cyclicConstantsResolveToDynamicWithoutHanging() throws Exception {
        List<Expression> exprs = parseInitializers("""
                    static final String C1 = C2;
                    static final String C2 = C1;
                    Object X1 = C1;
                """);
        var x1 = ValueResolver.resolve(exprs.get(0), PROPS);
        assertThat(x1.resolution()).isEqualTo(ValueResolver.Resolution.DYNAMIC);
    }

    /**
     * Pins the visited-set cycle guard's externally observable contract for a true cycle
     * (DYNAMIC/null): a distinct two-node cycle referenced through a third field, so the fix is
     * exercised via a fresh declarator pair rather than reusing
     * {@link #cyclicConstantsResolveToDynamicWithoutHanging}'s. For a genuine cycle specifically,
     * this outcome is unchanged from the pre-guard {@code StackOverflowError}-catch behavior — see
     * {@link #diamondConstantDependenciesResolveNotFalseCycle} for a case (a DAG, not a cycle)
     * where the guard's implementation *does* change the observable result, and must be
     * path-scoped (with backtracking) rather than "everything ever visited" to get it right.
     */
    @Test
    void cyclicConstantsResolveDynamicWithoutDeepRecursion() throws Exception {
        List<Expression> exprs = parseInitializers("""
                    static final String CYC_A = CYC_B;
                    static final String CYC_B = CYC_A;
                    Object X1 = CYC_A;
                """);
        var r = ValueResolver.resolve(exprs.get(0), PROPS);
        assertThat(r.resolution()).isEqualTo(ValueResolver.Resolution.DYNAMIC);
        assertThat(r.value()).isNull();
    }

    /**
     * B and C both reach D through independent branches; that's a diamond-shaped DAG, not a
     * cycle, and must fold to CONSTANT. A visited set that tracks "everything ever visited" in
     * the whole resolution (rather than just the current resolution path, backtracking as each
     * subtree finishes) would wrongly treat C's legitimate revisit of D — after B's branch
     * already resolved through D and returned — as a false cycle.
     */
    @Test
    void diamondConstantDependenciesResolveNotFalseCycle() throws Exception {
        List<Expression> exprs = parseInitializers("""
                    static final String DIA_D = "leaf";
                    static final String DIA_B = DIA_D;
                    static final String DIA_C = DIA_D;
                    Object X1 = DIA_B + DIA_C;
                """);
        var r = ValueResolver.resolve(exprs.get(0), PROPS);
        assertThat(r.resolution()).isEqualTo(ValueResolver.Resolution.CONSTANT);
        assertThat(r.value()).isEqualTo("leafleaf");
    }
}
