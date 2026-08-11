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
}
