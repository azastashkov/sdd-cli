package sdd.core.kb;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class EntityKindTest {
    @Test
    void labelLowercasesWithLocaleRootNotTheJvmDefault() {
        // Under a Turkish default locale, "CLASS".toLowerCase() (no Locale argument) maps 'I' to
        // the dotless 'ı' -- the label a reader sees must not depend on the rendering machine.
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr"));
        try {
            assertThat(EntityKind.CLASS.label()).isEqualTo("class");
            assertThat(EntityKind.ENDPOINT.label()).isEqualTo("endpoint");
        } finally {
            Locale.setDefault(previous);
        }
    }
}
