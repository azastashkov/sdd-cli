package sdd.core.http;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TlsConfigTest {
    @Test
    void nullProtocolsBecomesAnEmptyListNeverNull() {
        TlsConfig tls = new TlsConfig(null, null, null, null, null, null, null, null);
        assertThat(tls.protocols()).isEmpty();
    }

    @Test
    void protocolsListIsDefensivelyCopied() {
        List<String> mutable = new ArrayList<>(List.of("TLSv1.2"));
        TlsConfig tls = new TlsConfig(Path.of("/trust"), null, null, null, null, null, null, mutable);
        mutable.add("TLSv1.3");
        assertThat(tls.protocols()).containsExactly("TLSv1.2");
    }
}
