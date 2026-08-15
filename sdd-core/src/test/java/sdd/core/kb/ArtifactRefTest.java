package sdd.core.kb;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code "grp:name"} validity rule now has one owner (it used to be spelled out twice, in
 * {@code KbEntities.resolveArtifact} and {@code ConsumerFacts.artifactConsumers}), so the shapes
 * it rejects are worth pinning directly rather than only through a resolution that returns
 * nothing for several possible reasons.
 */
class ArtifactRefTest {
    @Test
    void parsesAtTheFirstColonSoAGroupIsNeverSplitOnALaterOne() {
        assertThat(ArtifactRef.parse("com.acme:lib-core"))
                .contains(new ArtifactRef("com.acme", "lib-core"));
        assertThat(ArtifactRef.parse("com.acme:lib:core"))
                .contains(new ArtifactRef("com.acme", "lib:core"));
    }

    @Test
    void rejectsEveryShapeThatNamesNoArtifact() {
        // both halves are matched against NOT NULL columns, so an empty one cannot resolve
        assertThat(ArtifactRef.parse("com.acme")).isEmpty();       // no colon
        assertThat(ArtifactRef.parse(":lib-core")).isEmpty();      // leading colon, empty group
        assertThat(ArtifactRef.parse("com.acme:")).isEmpty();      // trailing colon, empty name
        assertThat(ArtifactRef.parse(":")).isEmpty();
        assertThat(ArtifactRef.parse("")).isEmpty();
    }
}
