package sdd.agent.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavaSyntaxTest {
    @Test
    void acceptsValidAndReportsFirstProblemForInvalid() {
        assertThat(JavaSyntax.firstError("class A { void m() {} }")).isEmpty();
        assertThat(JavaSyntax.firstError("class A { void m( {} }")).isPresent();
    }

    @Test
    void acceptsTheJavaTheEstateIsActuallyWrittenIn() {
        // Measured from a real run's transcript
        // (estate11 SPEC-203-v1, trading-core): apply_edit was rejected 45 times out of 108 calls,
        // 37 of them "Record Declarations are not supported". The estate is Java 21 and the edits
        // were valid; the gate was parsing at JavaParser's default language level. An agent whose
        // correct edit is reverted rewrites it and is reverted again, which is how 42 of that run's
        // 301 turns were spent.
        assertThat(JavaSyntax.firstError("record Point(int x, int y) {}"))
                .as("record declaration").isEmpty();
        assertThat(JavaSyntax.firstError("""
                class A {
                    String s = \"""
                        hello
                        \""";
                }""")).as("text block").isEmpty();
        assertThat(JavaSyntax.firstError(
                "class A { boolean m(Object o) { return o instanceof String s && !s.isEmpty(); } }"))
                .as("instanceof pattern").isEmpty();
        assertThat(JavaSyntax.firstError("""
                sealed interface Shape permits Circle {}
                record Circle(double r) implements Shape {}""")).as("sealed types").isEmpty();
        assertThat(JavaSyntax.firstError("""
                class A {
                    String m(Object o) {
                        return switch (o) {
                            case Integer i -> "int " + i;
                            default -> "other";
                        };
                    }
                }""")).as("pattern switch").isEmpty();
    }

    @Test
    void aGenuineSyntaxErrorInModernJavaIsStillCaught() {
        // The gate must not become permissive in the course of becoming correct.
        assertThat(JavaSyntax.firstError("record Point(int x, int y {}")).isPresent();
    }
}
