package sdd.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Picking a model out of a thirty-six-name listing.
 *
 * <p>The live case this exists for: configured {@code deepseek-v4-flash}, served
 * {@code DeepSeek-V4-Pro} — same family, different case, different separators, different variant.
 */
class DoctorCommandNearestModelTest {

    /** Exactly what the corp gateway returned. */
    private static final List<String> SERVED = List.of(
            "DeepSeek-V4-Pro", "Gemma-4-26b", "GigaChat-2", "GigaChat-2-Max",
            "GigaChat-2-Max-legacy", "GigaChat-2-Pro", "GigaChat-2-Reasoning",
            "GigaChat-3-Ultra", "MiniMax-M2.7", "Qwen3-Next", "Qwen3.5-397b",
            "glm-5.1", "Embeddings", "SaluteEmbeddings");

    @Test
    void theRetiredNameFindsItsFamily() {
        assertThat(DoctorCommand.nearest("deepseek-v4-flash", SERVED))
                .containsExactly("DeepSeek-V4-Pro");
    }

    @Test
    void caseAndPunctuationDoNotHideAMatch() {
        assertThat(DoctorCommand.nearest("qwen3.5-397b", SERVED)).first()
                .isEqualTo("Qwen3.5-397b");
        assertThat(DoctorCommand.nearest("GIGACHAT_2_MAX", SERVED)).first()
                .isEqualTo("GigaChat-2-Max");
    }

    /**
     * Ranked by how much they share, and never more than three.
     *
     * <p>{@code GigaChat-2-Max} and {@code GigaChat-2-Max-legacy} share the SAME twelve characters
     * with the query, so the tie is broken by listing order — which puts the plainer name first,
     * the better of the two answers.
     */
    @Test
    void theClosestComeFirstAndTheListIsShort() {
        List<String> near = DoctorCommand.nearest("gigachat-2-max-preview", SERVED);
        assertThat(near).hasSizeLessThanOrEqualTo(3);
        assertThat(near).first().isEqualTo("GigaChat-2-Max");
        assertThat(near).contains("GigaChat-2-Max-legacy");
    }

    /** Better to say nothing than to propose an unrelated model. */
    @Test
    void anUnrelatedNameSuggestsNothing() {
        assertThat(DoctorCommand.nearest("llama-3-70b", SERVED)).isEmpty();
        assertThat(DoctorCommand.nearest("", SERVED)).isEmpty();
        assertThat(DoctorCommand.nearest(null, SERVED)).isEmpty();
    }

    /**
     * A case-only difference is a DIFFERENT diagnosis, handled before this runs — but nearest must
     * still find it, since that is what makes the exact spelling printable.
     */
    @Test
    void aCaseOnlyDifferenceIsTheNearestOfAll() {
        assertThat(DoctorCommand.nearest("deepseek-v4-pro", SERVED))
                .first().isEqualTo("DeepSeek-V4-Pro");
    }

    /** Three shared characters is a coincidence, not a suggestion. */
    @Test
    void aThreeCharacterOverlapIsNotEnough() {
        assertThat(DoctorCommand.nearest("gemini-ultra", List.of("Gemma-4-26b"))).isEmpty();
    }
}
