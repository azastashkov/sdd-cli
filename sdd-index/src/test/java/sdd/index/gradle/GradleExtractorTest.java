package sdd.index.gradle;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GradleExtractorTest {
    @Test
    void flagsExtractionRunOnTheWrongJvmWhenNoJdkIsConfigured() {
        String note = GradleExtractor.jdkRiskNote("6.9", Map.of(), 21);
        assertThat(note).contains("no jdk_homes entry for 11")
                .contains("wrapper 6.9")
                .contains("current JVM 21");
    }

    @Test
    void noRiskWhenTheMappedJdkIsTheJvmWeAlreadyRunOn() {
        assertThat(GradleExtractor.jdkRiskNote("8.10.2", Map.of(), 21)).isNull();
        assertThat(GradleExtractor.jdkRiskNote(null, Map.of(), 21)).isNull();
    }

    @Test
    void noRiskWhenTheMappedJdkIsConfigured() {
        assertThat(GradleExtractor.jdkRiskNote("6.9", Map.of(11, Path.of("/jdks/11")), 21)).isNull();
    }
}
