package sdd.core.http;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@code multipart/form-data} body, for the one thing in sdd that needs one: uploading an image
 * to a model endpoint's file store before referencing it from a chat turn.
 *
 * <p>Hand-rolled because the JDK's HTTP client has no multipart encoder and this project takes no
 * dependency for eighty lines. The parts of it that are easy to get wrong, and that the tests pin:
 * every boundary line is prefixed with {@code --} and the terminator suffixed with another
 * {@code --}; line endings are CRLF everywhere, including the blank line separating a part's
 * headers from its bytes, because a bare LF is accepted by some servers and silently mangles the
 * payload on others; and the file part carries its own {@code Content-Type} so the receiver does
 * not have to sniff it.
 *
 * <p>The boundary is fixed rather than random. A random one would make the produced bytes
 * unreproducible, and this body is the kind of thing that gets diffed against a working capture
 * when a gateway rejects it. It only has to not occur in the payload, and this one cannot occur in
 * a PNG header or a form field sdd writes.
 */
public final class Multipart {
    private static final String BOUNDARY = "sdd7dc1a4e2boundary9f3b";
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);

    private final Map<String, String> fields = new LinkedHashMap<>();
    private String fileField;
    private String filename;
    private String fileContentType;
    private byte[] fileBytes;

    /** A plain text form field, in insertion order. */
    public Multipart field(String name, String value) {
        fields.put(name, value);
        return this;
    }

    /** The single file part. Calling this twice replaces the first — sdd never sends two. */
    public Multipart file(String name, String uploadName, String contentType, byte[] bytes) {
        this.fileField = name;
        this.filename = uploadName;
        this.fileContentType = contentType;
        this.fileBytes = bytes.clone();
        return this;
    }

    /** The value for the request's {@code Content-Type} header. Must match the body exactly. */
    public String contentType() {
        return "multipart/form-data; boundary=" + BOUNDARY;
    }

    /** The encoded body. */
    public byte[] body() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            write(out, "--" + BOUNDARY);
            write(out, "Content-Disposition: form-data; name=\"" + escape(entry.getKey()) + "\"");
            write(out, "");
            write(out, entry.getValue());
        }
        if (fileBytes != null) {
            write(out, "--" + BOUNDARY);
            write(out, "Content-Disposition: form-data; name=\"" + escape(fileField)
                    + "\"; filename=\"" + escape(filename) + "\"");
            write(out, "Content-Type: " + fileContentType);
            write(out, "");
            out.writeBytes(fileBytes);
            out.writeBytes(CRLF);
        }
        write(out, "--" + BOUNDARY + "--");
        return out.toByteArray();
    }

    /**
     * {@code ofByteArray}, deliberately, not {@code ofByteArrays}: the plural form reports an
     * unknown content length, so the JDK streams it and a receiver that wants a length instead
     * resets the stream — observed as {@code IOException: Received RST_STREAM: Stream cancelled}
     * on every one of six retry attempts, which reads like a network fault and is not one. The
     * body is a few hundred kilobytes at most; it is already fully in memory either way.
     */
    public HttpRequest.BodyPublisher publisher() {
        return HttpRequest.BodyPublishers.ofByteArray(body());
    }

    private static void write(ByteArrayOutputStream out, String line) {
        out.writeBytes(line.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(CRLF);
    }

    /**
     * A quote or a newline in a filename would end the header early and let the rest be read as
     * another header — the multipart equivalent of an injection. Confluence filenames are attacker-
     * adjacent (anyone who can attach to a page picks them), so they are neutralised, not trusted.
     */
    private static String escape(String value) {
        return value.replace("\r", " ").replace("\n", " ").replace("\"", "'");
    }
}
