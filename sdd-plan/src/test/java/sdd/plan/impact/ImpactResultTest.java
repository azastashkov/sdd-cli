package sdd.plan.impact;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImpactResultTest {

    @Test
    void recordsAreDefensiveAndNullHostile() {
        java.util.List<Seed> mutable = new java.util.ArrayList<>(
                List.of(new Seed("svc-pricing", "touchpoint", "repo:svc-pricing")));
        ImpactResult result = new ImpactResult(mutable, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
        mutable.clear();
        assertThat(result.seeds()).hasSize(1);

        assertThatThrownBy(() -> new Seed(null, "fts", "d"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AffectedRepo("r", "seed", null, List.of(), List.of()))
                .isInstanceOf(NullPointerException.class);
    }
}
