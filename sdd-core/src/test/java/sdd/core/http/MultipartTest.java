package sdd.core.http;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The body is parsed back by splitting on the boundary rather than by asserting a literal string:
 * a test that pins the exact bytes passes just as happily when both the encoder and the expectation
 * are wrong in the same way, which is the failure mode this whole class exists to avoid.
 */
class MultipartTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n',
            0, 0, (byte) 0xff, '\r', '\n', '-', '-', 0};

    @Test
    void carriesTheFileBytesThroughUnchanged() {
        Multipart form = new Multipart()
                .field("purpose", "general")
                .file("file", "probe.png", "image/png", PNG);

        Part file = parts(form).stream().filter(p -> "file".equals(p.name)).findFirst().orElseThrow();

        assertThat(file.body).isEqualTo(PNG);
        assertThat(file.headers).contains("filename=\"probe.png\"").contains("Content-Type: image/png");
    }

    @Test
    void fieldsComeOutInInsertionOrderWithTheirValues() {
        Multipart form = new Multipart().field("purpose", "general").field("second", "zulu");

        List<Part> parts = parts(form);

        assertThat(parts).extracting(p -> p.name).containsExactly("purpose", "second");
        assertThat(new String(parts.get(0).body, StandardCharsets.UTF_8)).isEqualTo("general");
        assertThat(new String(parts.get(1).body, StandardCharsets.UTF_8)).isEqualTo("zulu");
    }

    @Test
    void theContentTypeHeaderNamesTheBoundaryTheBodyActuallyUses() {
        Multipart form = new Multipart().field("purpose", "general");
        String boundary = form.contentType().substring(form.contentType().indexOf("boundary=") + 9);

        assertThat(new String(form.body(), StandardCharsets.UTF_8))
                .startsWith("--" + boundary + "\r\n")
                .endsWith("--" + boundary + "--\r\n");
    }

    /** Every line ending is CRLF. A bare LF is tolerated by some servers and corrupts the payload
     *  on others, which is the worst kind of bug to ship to a closed network. */
    @Test
    void everyLineEndingIsCrlf() {
        String body = new String(new Multipart().field("purpose", "general")
                .file("file", "a.png", "image/png", new byte[]{1, 2, 3}).body(),
                StandardCharsets.ISO_8859_1);

        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) == '\n') {
                assertThat(i).isGreaterThan(0);
                assertThat(body.charAt(i - 1)).isEqualTo('\r');
            }
        }
    }

    /**
     * A quote or a newline in a filename would close the header early and let the remainder be read
     * as a further header. Neutralised, not rejected — the text survives INSIDE the quoted filename,
     * which is harmless; what must not survive is its structure. So this asserts per LINE, not by
     * substring: "X-Evil" appearing as part of a filename is fine, "X-Evil:" starting a header is
     * not, and a substring assertion cannot tell those apart.
     */
    @Test
    void aHostileFilenameCannotEndTheHeaderEarly() {
        Multipart form = new Multipart()
                .file("file", "a\"; name=\"purpose\r\nX-Evil: 1", "image/png", new byte[]{1});

        List<Part> parts = parts(form);

        assertThat(parts).hasSize(1);
        assertThat(parts.get(0).name).isEqualTo("file");
        assertThat(parts.get(0).headers.lines())
                .allSatisfy(line -> assertThat(line)
                        .matches("^(Content-Disposition|Content-Type): .*"));
    }

    private record Part(String name, String headers, byte[] body) {
    }

    /** Split on the boundary and pull each part's headers and raw bytes back out. */
    private static List<Part> parts(Multipart form) {
        String boundary = form.contentType().substring(form.contentType().indexOf("boundary=") + 9);
        byte[] body = form.body();
        String latin = new String(body, StandardCharsets.ISO_8859_1);
        List<Part> out = new ArrayList<>();
        String delimiter = "--" + boundary;
        int at = latin.indexOf(delimiter) + delimiter.length() + 2;
        while (true) {
            int next = latin.indexOf("\r\n" + delimiter, at);
            if (next < 0) {
                break;
            }
            String chunk = latin.substring(at, next);
            int blank = chunk.indexOf("\r\n\r\n");
            String headers = chunk.substring(0, blank);
            byte[] raw = latin.substring(at + blank + 4, next).getBytes(StandardCharsets.ISO_8859_1);
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("name=\"([^\"]*)\"").matcher(headers);
            out.add(new Part(m.find() ? m.group(1) : "?", headers, raw));
            at = next + 2 + delimiter.length() + 2;
            if (latin.startsWith("--", next + 2 + delimiter.length())) {
                break;
            }
        }
        return out;
    }
}
