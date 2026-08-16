package sdd.core.http;

import org.junit.jupiter.api.Test;
import sdd.core.config.AtlassianProxy;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link HttpClients#describeEffectiveProxy} — Fix 5 (Task 8 review): the same "effective proxy"
 *  fact {@code RestClient}'s failure diagnostics now surface, extracted here so it reuses {@link
 *  HttpClients#proxySelector}'s exact match rule via the shared {@code bypasses} helper rather than
 *  re-deriving it. Additive: {@link HttpClientsTest} is untouched. */
class HttpClientsProxyDescriptionTest {
    @Test
    void noProxyConfiguredReportsPlainly() {
        assertThat(HttpClients.describeEffectiveProxy(null, "jira.corp.local")).isEqualTo("no proxy configured");
    }

    @Test
    void describesTheConfiguredProxyForAHostThatIsNotBypassed() {
        AtlassianProxy proxy = new AtlassianProxy("corp-proxy.local", 8080, List.of());

        assertThat(HttpClients.describeEffectiveProxy(proxy, "jira.corp.local"))
                .isEqualTo("proxy corp-proxy.local:8080");
    }

    @Test
    void describesADirectConnectionForAHostMatchingNoProxy() {
        AtlassianProxy proxy = new AtlassianProxy("corp-proxy.local", 8080, List.of("corp.local"));

        assertThat(HttpClients.describeEffectiveProxy(proxy, "jira.corp.local"))
                .isEqualTo("direct (no_proxy matches jira.corp.local)");
    }
}
