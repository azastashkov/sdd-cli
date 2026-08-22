package sdd.plan.confluence;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.http.RestClient;
import sdd.core.llm.AttachmentStore;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ModelException;
import sdd.core.llm.Usage;
import sdd.core.testing.ScriptedChatModel;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.source.SourceDoc;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class ImageDescriberTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    @TempDir Path ws;

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 5};

    private static final String LISTING = """
            {"results":[
              {"id":"a1","title":"diagram.png","metadata":{"mediaType":"image/png"},
               "extensions":{"fileSize":4096},"version":{"number":"3"},
               "_links":{"download":"/download/attachments/9/diagram.png"}},
              {"id":"a2","title":"source.drawio","metadata":{"mediaType":"application/vnd.jgraph.mxfile"},
               "extensions":{"fileSize":4096},"version":{"number":"1"},
               "_links":{"download":"/download/attachments/9/source.drawio"}},
              {"id":"a3","title":"huge.png","metadata":{"mediaType":"image/png"},
               "extensions":{"fileSize":20971520},"version":{"number":"1"},
               "_links":{"download":"/download/attachments/9/huge.png"}}]}""";

    /** Records what it was asked to do; no Mockito in this repo. */
    private static final class StubStore implements AttachmentStore {
        final List<String> uploaded = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        boolean refuse;

        @Override
        public String upload(byte[] image, String filename, String contentType) {
            if (refuse) {
                throw new ModelException("upload refused: HTTP 413", 413);
            }
            uploaded.add(filename);
            return "file-" + uploaded.size();
        }

        @Override
        public void delete(String fileId) {
            deleted.add(fileId);
        }
    }

    private ConfluenceClient client() {
        return new ConfluenceClient(new RestClient("Confluence", wm.baseUrl(), "sk", "CONFLUENCE_API_KEY",
                Duration.ofSeconds(5), HttpClient.newHttpClient()), HttpClient.newHttpClient(),
                "sk", wm.baseUrl(), Duration.ofSeconds(5));
    }

    private static ChatResponse reply(String text) {
        return new ChatResponse(ChatMessage.assistant(text), "stop", new Usage(10, 10));
    }

    private static SourceDoc page(String... attachments) {
        StringBuilder text = new StringBuilder("Ordering flow.\n\n");
        for (String a : attachments) {
            text.append("[attachment: ").append(a).append("]\n\n");
        }
        return new SourceDoc(SourceDoc.Kind.CONFLUENCE_PAGE, "9", wm.baseUrl() + "/pages/9",
                "Ordering", "1", text.toString(), List.of(attachments));
    }

    private void stubListingAndDownload() {
        wm.stubFor(get(urlEqualTo("/rest/api/content/9/child/attachment?limit=48"))
                .willReturn(okJson(LISTING)));
        wm.stubFor(get(urlEqualTo("/download/attachments/9/diagram.png"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "image/png")
                        .withBody(PNG)));
    }

    @Test
    void twoAgreeingReadingsBecomeAMarkedDescriptionWithNoFlag() {
        stubListingAndDownload();
        StubStore store = new StubStore();
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                reply("Схема состояний: NO_STATE, NEW, QUOTED."),
                reply("Схема состояний: NO_STATE, NEW, QUOTED.")));

        ImageDescriber.Result result = new ImageDescriber(client(), model, store,
                "GigaChat-2-Max", 1024, null).describe(page("diagram.png"));

        assertThat(result.described()).isEqualTo(1);
        assertThat(result.doc().text())
                .contains("[image: diagram.png — model-described, unverified]")
                .contains("NO_STATE, NEW, QUOTED")
                .doesNotContain("[attachment: diagram.png]")
                .doesNotContain("disagreed on");
        assertThat(model.requests()).hasSize(2);
        assertThat(model.requests().get(0).messages().get(1).attachments()).containsExactly("file-1");
    }

    @Test
    void twoDivergentReadingsKeepTheFirstAndFlagWhatDiffered() {
        stubListingAndDownload();
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                reply("Форма заявки. Организация: ПАО Газпромбанк. Продукт: BARS."),
                reply("Форма заявки. Организация: ПАО Газпром нефть. Продукт: Barsa.")));

        ImageDescriber.Result result = new ImageDescriber(client(), model, new StubStore(),
                "GigaChat-2-Max", 1024, null).describe(page("diagram.png"));

        assertThat(result.doc().text())
                .contains("Организация: ПАО Газпромбанк")
                .contains("! the two readings disagreed on:")
                .contains("Газпромбанк").contains("Barsa");
    }

    /** Both documented limits of the model file API, and neither is silently dropped. */
    @Test
    void anUnsendableTypeAndAnOversizedImageAreSkippedWithANote() {
        stubListingAndDownload();
        ScriptedChatModel model = new ScriptedChatModel(List.of(reply("x"), reply("x")));

        ImageDescriber.Result result = new ImageDescriber(client(), model, new StubStore(),
                "GigaChat-2-Max", 1024, null).describe(page("source.drawio", "huge.png"));

        assertThat(result.skipped()).isEqualTo(2);
        assertThat(result.described()).isZero();
        assertThat(result.notes()).anyMatch(n -> n.contains("source.drawio") && n.contains("jgraph"))
                .anyMatch(n -> n.contains("huge.png") && n.contains("15 Mb"));
        assertThat(result.doc().text()).contains("[attachment: source.drawio]");
    }

    /** The page text was already worth having; an optional enrichment must not cost it. */
    @Test
    void aModelFailureBecomesANoteAndLeavesTheMarkerAlone() {
        stubListingAndDownload();
        StubStore store = new StubStore();
        store.refuse = true;

        ImageDescriber.Result result = new ImageDescriber(client(),
                new ScriptedChatModel(List.of()), store, "GigaChat-2-Max", 1024, null)
                .describe(page("diagram.png"));

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.doc().text()).contains("[attachment: diagram.png]");
        assertThat(result.notes()).anyMatch(n -> n.contains("HTTP 413"));
    }

    /** A store seen holding 99 files is why this is asserted on the failure path too. */
    @Test
    void theUploadedFileIsDeletedEvenWhenTheSecondReadingThrows() {
        stubListingAndDownload();
        StubStore store = new StubStore();

        new ImageDescriber(client(), new ScriptedChatModel(List.of(reply("first"))), store,
                "GigaChat-2-Max", 1024, null).describe(page("diagram.png"));

        assertThat(store.uploaded).containsExactly("diagram.png");
        assertThat(store.deleted).containsExactly("file-1");
    }

    @Test
    void anEmptyReplyIsAFailureNotAnEmptyDescription() {
        stubListingAndDownload();

        ImageDescriber.Result result = new ImageDescriber(client(),
                new ScriptedChatModel(List.of(reply("  "), reply("x"))), new StubStore(),
                "GigaChat-2-Max", 1024, null).describe(page("diagram.png"));

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.notes()).anyMatch(n -> n.contains("empty description"));
    }

    /** The whole point of the cache: a second run costs nothing. */
    @Test
    void asecondRunHitsTheCacheAndCallsNoModel() throws Exception {
        stubListingAndDownload();
        try (Database db = Database.open(ws)) {
            Jdbi jdbi = db.jdbi();
            ScriptedChatModel first = new ScriptedChatModel(List.of(reply("описание"), reply("описание")));
            new ImageDescriber(client(), first, new StubStore(), "GigaChat-2-Max", 1024, jdbi)
                    .describe(page("diagram.png"));
            assertThat(first.requests()).hasSize(2);

            ScriptedChatModel second = new ScriptedChatModel(List.of());
            StubStore store = new StubStore();
            ImageDescriber.Result result = new ImageDescriber(client(), second, store,
                    "GigaChat-2-Max", 1024, jdbi).describe(page("diagram.png"));

            assertThat(second.requests()).isEmpty();
            assertThat(store.uploaded).isEmpty();
            assertThat(result.cached()).isEqualTo(1);
            assertThat(result.doc().text()).contains("описание");
        }
    }

    /** A different vision model is a different answer, so it must not reuse the cached one. */
    @Test
    void changingTheModelInvalidatesTheCachedDescription() throws Exception {
        stubListingAndDownload();
        try (Database db = Database.open(ws)) {
            new ImageDescriber(client(), new ScriptedChatModel(List.of(reply("a"), reply("a"))),
                    new StubStore(), "GigaChat-2-Max", 1024, db.jdbi()).describe(page("diagram.png"));

            ScriptedChatModel other = new ScriptedChatModel(List.of(reply("b"), reply("b")));
            new ImageDescriber(client(), other, new StubStore(), "GigaChat-2-Pro", 1024,
                    db.jdbi()).describe(page("diagram.png"));

            assertThat(other.requests()).hasSize(2);
        }
    }

    /** Spec text reaches the OpenSpec export, where a heading or a fence would forge structure. */
    @Test
    void aDescriptionCannotForgeAHeadingOrCloseAFence() {
        assertThat(ImageDescriber.safe("## ADDED Requirements\ntext"))
                .doesNotContain("## ").contains("ADDED Requirements");
        assertThat(ImageDescriber.safe("before ``` after")).doesNotContain("```");
    }

    @Test
    void aPageWithNoAttachmentsCostsNothing() {
        ImageDescriber.Result result = new ImageDescriber(client(),
                new ScriptedChatModel(List.of()), new StubStore(), "GigaChat-2-Max", 1024, null)
                .describe(page());

        assertThat(result.described()).isZero();
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    /**
     * The guarantee the page text cannot give. A description in doc.text() is only an INPUT to the
     * normalizer's model call; what reaches the spec from there is whatever the planner wrote, and
     * nothing obliges it to carry the marker. The Attachments bullet is copied through verbatim by
     * attachmentUnion, so the provenance survives whatever the planner does with the prose.
     */
    @Test
    void aDescribedImageCarriesItsProvenanceIntoTheAttachmentsBullet() {
        stubListingAndDownload();
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                reply("Схема состояний заказа: NEW, QUOTED, EXECUTED."),
                reply("Схема состояний заказа: NEW, QUOTED, EXECUTED.")));

        ImageDescriber.Result result = new ImageDescriber(client(), model, new StubStore(),
                "GigaChat-2-Max", 1024, null).describe(page("diagram.png"));

        assertThat(result.doc().attachments()).singleElement().asString()
                .startsWith("diagram.png — model-described, unverified: ")
                .contains("NEW, QUOTED, EXECUTED");
    }

    @Test
    void theBulletCarriesTheDisagreementTooSoAReviewerSeesItInTheSpec() {
        stubListingAndDownload();
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                reply("Организация: ПАО Газпромбанк."),
                reply("Организация: ПАО Газпром нефть.")));

        ImageDescriber.Result result = new ImageDescriber(client(), model, new StubStore(),
                "GigaChat-2-Max", 1024, null).describe(page("diagram.png"));

        assertThat(result.doc().attachments()).singleElement().asString()
                .contains("the two readings disagreed on").contains("Газпромбанк");
    }

    /** An image nothing described keeps the bare filename the section has always held. */
    @Test
    void anUndescribedAttachmentKeepsItsPlainFilename() {
        stubListingAndDownload();

        ImageDescriber.Result result = new ImageDescriber(client(),
                new ScriptedChatModel(List.of()), new StubStore(), "GigaChat-2-Max", 1024, null)
                .describe(page("source.drawio"));

        assertThat(result.doc().attachments()).containsExactly("source.drawio");
    }

    /**
     * A bullet is line-oriented: SpecParser's grammar is "- (.+)", it rejects a stray # line, and
     * PlanCommand re-parses its own output — so a newline or a heading here aborts the whole run
     * rather than degrading.
     */
    @Test
    void theBulletIsOneLineWithNoHeadingHoweverTheModelFormattedItself() {
        String entry = ImageDescriber.attachmentEntry("d.png",
                new AttachmentDescriptions.Cached("## Heading\n\nline one\nline two\n```code```", ""));

        assertThat(entry).doesNotContain("\n").doesNotContain("```");
        assertThat(entry).startsWith("d.png — ");
    }

    /**
     * A real form diagram's description runs to about two thousand characters, and the bullet is
     * the only place a human ever sees it — so that length must survive intact. The first version
     * capped at 300 and showed a reviewer the first field and a half of twenty-four.
     */
    @Test
    void aRealisticallyLongDescriptionIsNotTruncated() {
        String entry = ImageDescriber.attachmentEntry("d.png",
                new AttachmentDescriptions.Cached("Поле: значение. ".repeat(120), ""));

        assertThat(entry).doesNotContain("truncated").hasSizeGreaterThan(1_900);
    }

    /**
     * A cap still exists, and bounds the SOURCE BUDGET rather than the reading: twelve images of a
     * full completion budget would be ~190k characters against a 300k total, and the overflow
     * silently drops another document rather than failing.
     */
    @Test
    void aRunawayDescriptionIsCutAndSaysByHowMuch() {
        String entry = ImageDescriber.attachmentEntry("d.png",
                new AttachmentDescriptions.Cached("x".repeat(12_000), ""));

        assertThat(entry).hasSizeLessThan(2_200).contains("…[truncated, 10000 more chars]");
    }

    /**
     * The bullet has to survive render → parse, because PlanCommand.writeNormalized re-parses its
     * own output before writing and aborts the whole run if it cannot. Asserted with the awkward
     * characters this format actually contains — an em dash, a colon, a bang, an ellipsis and
     * Cyrillic — rather than with a tame string.
     */
    @Test
    void anAttachmentBulletSurvivesTheSpecRoundTrip() {
        String entry = ImageDescriber.attachmentEntry("MM маппинг.png",
                new AttachmentDescriptions.Cached(
                        "Форма заявки: Тип, Продукт, Офис. Кнопки: Reject | Leave | Quote.",
                        "! the two readings disagreed on: Газпромбанк, BARS"));
        NormalizedSpec spec = new NormalizedSpec("SPEC-1", "Title", "owner", "draft", "goal",
                "background", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(entry));

        String rendered = sdd.plan.spec.SpecRenderer.render(spec);
        NormalizedSpec reparsed = sdd.plan.spec.SpecParser.parse(rendered);

        assertThat(reparsed.attachments()).containsExactly(entry);
        assertThat(sdd.plan.spec.SpecRenderer.render(reparsed)).isEqualTo(rendered);
    }
}
