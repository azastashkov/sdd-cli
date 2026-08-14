package sdd.cli.review;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShasTest {

    @Test
    void takesTheFirstSevenCharacters() {
        assertThat(Shas.shortSha("0123456789abcdef")).isEqualTo("0123456");
    }

    @Test
    void aShaShorterThanSevenCharactersIsReturnedWhole() {
        assertThat(Shas.shortSha("abc")).isEqualTo("abc");
    }

    @Test
    void nullReturnsNoneRatherThanThrowing() {
        assertThat(Shas.shortSha(null)).isEqualTo("(none)");
    }

    @Test
    void blankReturnsNoneRatherThanThrowing() {
        assertThat(Shas.shortSha("")).isEqualTo("(none)");
        assertThat(Shas.shortSha("   ")).isEqualTo("(none)");
    }
}
