package sdd.plan.confluence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fixtures are real. Both pairs were produced on 2026-08-22 by sending one Confluence image to
 * GigaChat-2-Max twice through the corp gateway, and they are the reason this class exists: the
 * state diagram agreed, the mapping form did not. A synthetic fixture would have let the thresholds
 * be tuned to whatever made the test pass.
 */
class DisagreementTest {

    /** The dense mapping form. These two cannot both be right. */
    private static final String FORM_READ_ONE = """
            На изображении представлена форма заявки на предоставление кредита или займа.
            Тип заявки: MM MBK RFQ. Продукт: BARS (Bank Acceptance). Офис: OSA_FI.
            Название организации: ПАО Газпромбанк. Валюта: RUB. Тип депозита: DEPO1.
            Сумма: 10 000 000 рублей. Базовая ставка: 15,00%.
            Внизу формы расположены три кнопки: Reject, Leave и Quote.""";

    private static final String FORM_READ_TWO = """
            На изображении представлена форма заявки на сделку с ценными бумагами.
            Тип сделки: MM. Биржа: ММВБ. Тип инструмента: RFQ. Тип заявки: Barsa.
            Название организации: ПАО Газпром нефть. Валюта: RUB. Тип депозита: DEPO1.
            Сумма сделки: 10 000 000 рублей. Базовая ставка: 15,00%.
            Внизу формы расположены три кнопки: Reject, Leave и Quote.""";

    /** The state diagram. Two independent reads, same seven states, same transitions. */
    private static final String DIAGRAM_READ_ONE = """
            На изображении представлена схема, иллюстрирующая процесс обработки запроса
            котировки (RFQ) в торговой системе. NO_STATE: начальное состояние запроса.
            NEW: запрос создан и ожидает обработки. RFQ: запрос отправлен трейдеру.
            RFQ_REQUESTED: запрос получен трейдером. PICKED_UP: трейдер начал обработку.
            QUOTED: трейдер предоставил котировку. EXECUTED: сделка выполнена.""";

    private static final String DIAGRAM_READ_TWO = """
            На изображении представлена схема, описывающая процесс обработки запроса
            котировки (RFQ) в торговой системе. NO_STATE: начальное состояние запроса.
            NEW: запрос создан и ожидает обработки. RFQ: запрос отправлен трейдеру.
            RFQ_REQUESTED: запрос получен трейдером. PICKED_UP: трейдер начал обработку.
            QUOTED: трейдер предоставил котировку. EXECUTED: запрос выполнен.""";

    @Test
    void namesTheValuesTwoReadingsOfTheSameFormContradictedEachOtherOn() {
        List<String> flagged = Disagreement.between(FORM_READ_ONE, FORM_READ_TWO);

        assertThat(flagged).contains("Газпромбанк", "BARS", "Barsa", "ММВБ");
    }

    /**
     * The one that decides whether the flag is worth reading. Two genuinely agreeing readings must
     * produce NOTHING — a detector that fires on the stable diagram too would flag every image, and
     * a flag on everything is a flag on nothing.
     */
    @Test
    void twoAgreeingReadingsOfTheStateDiagramAreNotFlagged() {
        assertThat(Disagreement.between(DIAGRAM_READ_ONE, DIAGRAM_READ_TWO)).isEmpty();
        assertThat(Disagreement.line(DIAGRAM_READ_ONE, DIAGRAM_READ_TWO)).isEmpty();
    }

    @Test
    void theLineIsOneLineBecauseASpecBulletCannotHoldTwo() {
        String line = Disagreement.line(FORM_READ_ONE, FORM_READ_TWO);

        assertThat(line).startsWith("! the two readings disagreed on: ").doesNotContain("\n");
    }

    /** A misread number is the cheapest possible instance of the same failure. */
    @Test
    void aDigitLostBetweenTwoReadingsIsFlagged() {
        assertThat(Disagreement.between("На изображении число 2289.", "На изображении число 229."))
                .containsExactlyInAnyOrder("2289", "229");
    }

    @Test
    void identicalTextDisagreesAboutNothing() {
        assertThat(Disagreement.between(FORM_READ_ONE, FORM_READ_ONE)).isEmpty();
    }

    /** Symmetric: which reading was made first is not information about which is wrong. */
    @Test
    void theOrderOfTheTwoReadingsDoesNotChangeWhatIsFlagged() {
        assertThat(Disagreement.between(FORM_READ_ONE, FORM_READ_TWO))
                .containsExactlyInAnyOrderElementsOf(Disagreement.between(FORM_READ_TWO, FORM_READ_ONE));
    }

    @Test
    void aNullReadingIsNotACrash() {
        assertThat(Disagreement.between(null, FORM_READ_ONE)).isNotEmpty();
        assertThat(Disagreement.between(null, null)).isEmpty();
    }
}
