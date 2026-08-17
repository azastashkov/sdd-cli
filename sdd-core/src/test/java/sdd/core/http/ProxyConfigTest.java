package sdd.core.http;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyConfigTest {
    @Test
    void noProxyListIsDefensivelyCopied() {
        List<String> mutable = new ArrayList<>(List.of("corp.local"));
        ProxyConfig proxy = new ProxyConfig("proxy.corp.local", 8080, mutable);
        mutable.add("other.local");
        assertThat(proxy.noProxy()).containsExactly("corp.local");
    }
}
