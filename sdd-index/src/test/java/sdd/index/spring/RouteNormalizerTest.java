package sdd.index.spring;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class RouteNormalizerTest {
    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "/api,      /orders,        /api/orders",
            "/api/,     orders,         /api/orders",
            "NULL,      /orders,        /orders",
            "/api,      NULL,           /api",
            "NULL,      NULL,           /",
            "'',        orders/,        /orders",
    })
    void joins(String base, String method, String expected) {
        assertThat(RouteNormalizer.join(base, method)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "/orders/{id},              /orders/{}",
            "/orders/{orderId}/items,   /orders/{}/items",
            "orders//items/,            /orders/items",
            "/,                         /",
    })
    void normalizes(String input, String expected) {
        assertThat(RouteNormalizer.normalize(input)).isEqualTo(expected);
    }
}
