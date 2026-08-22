package sdd.core.llm;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import sdd.core.config.ModelEndpoint;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The file store half of the GigaChat image flow. Shapes taken from the OpenAPI spec and confirmed
 * live 2026-08-22: {@code POST /files} multipart with fields {@code file} and {@code purpose},
 * answering an object with a UUID {@code id}; deletion is {@code POST /files/{id}/delete}, not
 * {@code DELETE}.
 */
class AttachmentUploadTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 7};

    private static HttpChatModel model(WireFormat wire) {
        ModelEndpoint ep = new ModelEndpoint(wm.baseUrl() + "/v1", "GigaChat-2-Max", "sk-key",
                256, 0.0, Duration.ofSeconds(5), Map.of(), null, null, wire);
        return new HttpChatModel(ep, HttpClient.newHttpClient(), millis -> { });
    }

    @Test
    void uploadPostsMultipartToFilesAndReturnsTheId() {
        wm.stubFor(post("/v1/files").willReturn(okJson("""
                {"id":"636ff4c5-7401-4359-9364-ecccc3171847","object":"file","bytes":9,
                 "created_at":1,"filename":"probe.png","purpose":"general"}""")));

        String id = model(WireFormat.GIGACHAT).upload(PNG, "probe.png", "image/png");

        assertThat(id).isEqualTo("636ff4c5-7401-4359-9364-ecccc3171847");
        String body = wm.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertThat(body).contains("name=\"purpose\"").contains("general")
                .contains("name=\"file\"; filename=\"probe.png\"").contains("Content-Type: image/png");
        wm.verify(postRequestedFor(urlEqualTo("/v1/files"))
                .withHeader("Content-Type",
                        com.github.tomakehurst.wiremock.client.WireMock.containing("multipart/form-data; boundary="))
                .withHeader("Authorization",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("Bearer sk-key")));
    }

    /**
     * The store is documented as OPTIONAL in the response — the official SDK's own fixture omits
     * {@code modalities} — but an absent id is not optional, and silently returning null would
     * surface as a chat request referencing "null" much later.
     */
    @Test
    void anUploadWithNoIdInTheReplyIsAnError() {
        wm.stubFor(post("/v1/files").willReturn(okJson("{\"object\":\"file\",\"bytes\":9}")));

        assertThatThrownBy(() -> model(WireFormat.GIGACHAT).upload(PNG, "probe.png", "image/png"))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("returned no file id");
    }

    /** Refused before the request, so a caller is told before it pays for a download. */
    @Test
    void aWireWithNoFileStoreRefusesWithoutSendingAnything() {
        assertThatThrownBy(() -> model(WireFormat.OPENAI).upload(PNG, "probe.png", "image/png"))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("has no file store");

        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void aRefusedUploadCarriesTheGatewaysOwnMessage() {
        wm.stubFor(post("/v1/files").willReturn(aResponse().withStatus(413)
                .withBody("{\"status\":413,\"message\":\"Payload too large\"}")));

        assertThatThrownBy(() -> model(WireFormat.GIGACHAT).upload(PNG, "big.png", "image/png"))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("HTTP 413")
                .hasMessageContaining("Payload too large");
    }

    @Test
    void deleteIsAPostToTheDeleteSubresource() {
        wm.stubFor(post("/v1/files/abc/delete").willReturn(okJson("{\"id\":\"abc\",\"deleted\":true}")));

        model(WireFormat.GIGACHAT).delete("abc");

        wm.verify(postRequestedFor(urlEqualTo("/v1/files/abc/delete")));
    }

    /**
     * Cleanup is a courtesy, and the caller already has its description by the time it runs. A
     * store that refuses the delete must not turn a good result into a failure — but it must still
     * have been ATTEMPTED, which is the half that matters when a store accumulates 99 files.
     */
    @Test
    void aFailedDeleteDoesNotThrow() {
        wm.stubFor(post("/v1/files/abc/delete").willReturn(aResponse().withStatus(403)
                .withBody("{\"status\":403,\"message\":\"Access to file 'abc' is denied\"}")));

        model(WireFormat.GIGACHAT).delete("abc");

        wm.verify(postRequestedFor(urlEqualTo("/v1/files/abc/delete")));
    }
}
