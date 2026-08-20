package sdd.plan.openspec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangeIdTest {

    @Test
    void isTheRunIdInKebabForm() {
        // ImplementCommand derives runId = sanitize(specId) + "-v" + planVersion, so a human
        // reading openspec/changes/spec-101-v2/ and .sdd/runs/SPEC-101-v2/ needs no lookup table.
        assertThat(ChangeId.of("SPEC-101", 2)).isEqualTo("spec-101-v2");
        assertThat(ChangeId.of("SPEC_TIER_SPREADS", 1)).isEqualTo("spec-tier-spreads-v1");
    }

    @Test
    void aVersionBumpIsADifferentChange() {
        // Deliberate: a v1 change may already be committed in some repo. Reusing the id would have
        // v2 silently overwrite a landed change; a new id leaves both visible so a human can
        // archive one and apply the other.
        assertThat(ChangeId.of("SPEC-101", 1)).isNotEqualTo(ChangeId.of("SPEC-101", 2));
    }

    @Test
    void everyIdIsLegalOpenSpecEvenFromAHostileSpecId() {
        for (String specId : new String[] {"SPEC-101", "a b c", "...", "Ünïcödé", "9"}) {
            String id = ChangeId.of(specId, 1);
            assertThat(Kebab.VALID.matcher(id).matches())
                    .as("changeId(%s) = '%s'", specId, id).isTrue();
        }
    }

    @Test
    void aNonPositiveVersionIsARefusalNotAStrangeId() {
        assertThatThrownBy(() -> ChangeId.of("SPEC-1", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plan version must be positive");
    }
}
