package sdd.agent.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavaSyntaxTest {
    @Test
    void acceptsValidAndReportsFirstProblemForInvalid() {
        assertThat(JavaSyntax.firstError("class A { void m() {} }")).isEmpty();
        assertThat(JavaSyntax.firstError("class A { void m( {} }")).isPresent();
    }
}
