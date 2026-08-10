package sdd.core.retrieve;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class IdentifierWordsTest {
    @ParameterizedTest
    @CsvSource({
            "LoyaltyTier, loyalty tier",
            "PriceCalculator, price calculator",
            "HTTPClient, http client",
            "order_controller, order controller",
            "OrderV2Handler, order v 2 handler",
    })
    void splitIdentifierCorrectly(String identifier, String expected) {
        assertThat(IdentifierWords.split(identifier)).isEqualTo(expected);
    }
}
