package sdd.index.gradle;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ModeClassifierTest {
    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "2.3.0,          false, PINNED",
            "2.4.0-SNAPSHOT, false, SNAPSHOT",
            "2.+,            false, DYNAMIC",
            "latest.release, false, DYNAMIC",
            "'[2.0,3.0)',    false, DYNAMIC",
            "NULL,           false, BOM_MANAGED",
            "2.3.0,          true,  COMPOSITE",
            "NULL,           true,  COMPOSITE",
    })
    void classifies(String declared, boolean composite, ConsumptionMode expected) {
        assertThat(ModeClassifier.classify(declared, composite)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "2.3.0, false, DIRECT",
            "2.3.0, true,  CATALOG",
            "NULL,  false, BOM",
            "NULL,  true,  BOM",
    })
    void declaredVia(String declared, boolean inCatalog, String expected) {
        assertThat(ModeClassifier.declaredVia(declared, inCatalog)).isEqualTo(expected);
    }
}
