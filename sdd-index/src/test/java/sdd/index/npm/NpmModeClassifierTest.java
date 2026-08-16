package sdd.index.npm;

import org.junit.jupiter.api.Test;
import sdd.index.gradle.ConsumptionMode;
import sdd.index.gradle.ModeClassifier;

import static org.assertj.core.api.Assertions.assertThat;

class NpmModeClassifierTest {

    @Test
    void exactVersionsArePinned() {
        assertThat(NpmModeClassifier.classify("18.3.1", false)).isEqualTo(ConsumptionMode.PINNED);
        assertThat(NpmModeClassifier.classify("1.0.0-beta.1", false)).isEqualTo(ConsumptionMode.PINNED);
    }

    @Test
    void rangesAreDynamic() {
        for (String range : new String[]{"^0.2.1", "~5.5", ">=1.0.0", "1.x", "*", "latest",
                ">=1.0.0 <2.0.0", "1.0.0 || 2.0.0"}) {
            assertThat(NpmModeClassifier.classify(range, false))
                    .as("%s", range).isEqualTo(ConsumptionMode.DYNAMIC);
        }
    }

    @Test
    void sourceProtocolsAndWorkspaceSiblingsAreComposite() {
        assertThat(NpmModeClassifier.classify("file:../design-system", false)).isEqualTo(ConsumptionMode.COMPOSITE);
        assertThat(NpmModeClassifier.classify("workspace:*", false)).isEqualTo(ConsumptionMode.COMPOSITE);
        assertThat(NpmModeClassifier.classify("link:../x", false)).isEqualTo(ConsumptionMode.COMPOSITE);
        assertThat(NpmModeClassifier.classify("^1.0.0", true)).isEqualTo(ConsumptionMode.COMPOSITE);
    }

    @Test
    void aliasesAndRemoteRefsAreDynamicNotPinned() {
        assertThat(NpmModeClassifier.classify("npm:other@^1", false)).isEqualTo(ConsumptionMode.DYNAMIC);
        assertThat(NpmModeClassifier.classify("git+https://x/y.git#v1.0.0", false)).isEqualTo(ConsumptionMode.DYNAMIC);
        assertThat(NpmModeClassifier.classify("https://x/y.tgz", false)).isEqualTo(ConsumptionMode.DYNAMIC);
    }

    /**
     * The reason this class exists. Every internal specifier in the real estate is a caret range,
     * and the Gradle grammar reads all of them as pinned — no {@code +}, no {@code -SNAPSHOT}, no
     * leading bracket. Sharing one classifier would mislabel every npm edge in the estate and
     * report nothing.
     */
    @Test
    void gradleGrammarWouldMisreadEveryCaretRangeAsPinned() {
        assertThat(ModeClassifier.classify("^0.2.1", false)).isEqualTo(ConsumptionMode.PINNED);
        assertThat(NpmModeClassifier.classify("^0.2.1", false)).isEqualTo(ConsumptionMode.DYNAMIC);
    }
}
