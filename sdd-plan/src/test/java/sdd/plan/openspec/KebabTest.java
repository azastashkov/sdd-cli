package sdd.plan.openspec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KebabTest {

    @Test
    void coercesTheShapesSddSpecIdsActuallyTake() {
        assertThat(Kebab.of("SPEC-101")).isEqualTo("spec-101");
        assertThat(Kebab.of("SPEC_TIER_SPREADS")).isEqualTo("spec-tier-spreads");
        assertThat(Kebab.of("spec-tier-invalidation")).isEqualTo("spec-tier-invalidation");
        assertThat(Kebab.of("Order API v2")).isEqualTo("order-api-v2");
        assertThat(Kebab.of("com.trading:pricing-core")).isEqualTo("com-trading-pricing-core");
    }

    @Test
    void collapsesRunsAndTrimsEdges() {
        assertThat(Kebab.of("--a__b  c--")).isEqualTo("a-b-c");
        assertThat(Kebab.of("...leading")).isEqualTo("leading");
        assertThat(Kebab.of("trailing...")).isEqualTo("trailing");
    }

    @Test
    void anInputThatCoercesToNothingBecomesAVisiblePlaceholder() {
        // Not the empty string: an empty path segment produces openspec/changes//proposal.md,
        // which is a worse failure than a placeholder somebody can see and rename.
        assertThat(Kebab.of("...")).isEqualTo(Kebab.FALLBACK);
        assertThat(Kebab.of("")).isEqualTo(Kebab.FALLBACK);
        assertThat(Kebab.of(null)).isEqualTo(Kebab.FALLBACK);
        assertThat(Kebab.of("需求")).isEqualTo(Kebab.FALLBACK);
    }

    @Test
    void everyOutputIsALegalOpenSpecIdWhateverGoesIn() {
        // The property that matters: OpenSpec rejects an id failing its KEBAB_ID_REGEX outright,
        // so no input may produce one. Non-ASCII is dropped rather than transliterated, because
        // transliteration is locale-dependent and would make the id a function of the machine.
        String[] corpus = {
            "SPEC-101", "SPEC_TIER_SPREADS", "spec--double", "-lead", "trail-", "MiXeD CaSe",
            "tabs\tand\nnewlines", "emoji 🎯 here", "Ünïcödé", "a", "9", "9-lives",
            "com.trading:pricing-core:0.2.0-SNAPSHOT", "  ", "//", "a__________b",
        };
        for (String raw : corpus) {
            String kebab = Kebab.of(raw);
            assertThat(Kebab.VALID.matcher(kebab).matches())
                    .as("kebab(%s) = '%s' must match OpenSpec's id grammar", raw, kebab)
                    .isTrue();
            // Idempotent: coercing an already-legal id must not change it.
            assertThat(Kebab.of(kebab)).isEqualTo(kebab);
        }
    }

    @Test
    void isValidRecognisesWhatOpenSpecAccepts() {
        assertThat(Kebab.isValid("spec-101-v1")).isTrue();
        assertThat(Kebab.isValid("9-lives")).isTrue();   // a leading digit is explicitly allowed
        assertThat(Kebab.isValid("Spec")).isFalse();
        assertThat(Kebab.isValid("a--b")).isFalse();
        assertThat(Kebab.isValid("-a")).isFalse();
        assertThat(Kebab.isValid("a-")).isFalse();
        assertThat(Kebab.isValid("")).isFalse();
        assertThat(Kebab.isValid(null)).isFalse();
    }
}
