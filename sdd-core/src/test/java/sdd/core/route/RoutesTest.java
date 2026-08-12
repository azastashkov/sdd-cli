package sdd.core.route;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class RoutesTest {
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
        assertThat(Routes.join(base, method)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "/orders/{id},              /orders/{}",
            "/orders/{orderId}/items,   /orders/{}/items",
            "orders//items/,            /orders/items",
            "/,                         /",
    })
    void normalizes(String input, String expected) {
        assertThat(Routes.normalize(input)).isEqualTo(expected);
    }

    @Test
    void nullTemplateNormalizesToRoot() {
        assertThat(Routes.normalize(null)).isEqualTo("/");
    }

    @Test
    void templateAndVerbHelpers() {
        assertThat(Routes.templatesMatch("/a/{}/c", "/a/{}/c")).isTrue();
        assertThat(Routes.templatesMatch("/a/42/c", "/a/{}/c")).isTrue();
        assertThat(Routes.templatesMatch("/a/{}/c", "/a/b")).isFalse();
        assertThat(Routes.verbsCompatible("ANY", "GET")).isTrue();
        assertThat(Routes.verbsCompatible("GET", "ANY")).isTrue();
        assertThat(Routes.verbsCompatible("GET", "POST")).isFalse();
    }
}
