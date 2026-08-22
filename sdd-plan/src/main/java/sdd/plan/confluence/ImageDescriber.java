package sdd.plan.confluence;

import org.jdbi.v3.core.Jdbi;
import sdd.core.llm.AttachmentStore;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ChatRequest;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ModelException;
import sdd.plan.source.SourceDoc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Expands a Confluence page's {@code [attachment: name]} markers into model-written descriptions.
 *
 * <p>Runs as a post-pass over an already-fetched {@link SourceDoc}, never inside
 * {@code ConfluenceExtract}: that class is a pure static function shared with Jira whose javadoc
 * promises no model involvement, and the promise is worth more than the convenience of being closer
 * to the marker.
 *
 * <p>The description goes into the page TEXT rather than into the spec's Attachments section,
 * because that is the only place it reaches OpenSpec. {@code OpenSpecInput} has no attachments
 * component, so anything written there would reach a human and stop; text is read by the
 * normalizer's own model call, which turns it into requirements.
 *
 * <p><b>Every description is marked, and unverified.</b> Measured 2026-08-22: two readings of one
 * mapping form disagreed on the counterparty. So each image is read TWICE, the first reading is
 * used, and {@link Disagreement} names what the two did not agree on — a reviewer's attention goes
 * exactly where the model was unstable. Nothing here decides which reading was right.
 */
public final class ImageDescriber {

    static final String SYSTEM_PROMPT = """
            Ты описываешь изображение из технической документации. Опиши подробно и буквально то, \
            что видно: элементы, подписи, значения, связи между ними. Не додумывай и не обобщай. \
            Если текст на изображении неразборчив, так и скажи, а не угадывай. Ответь обычной \
            прозой, без markdown-заголовков и без блоков кода.""";

    private static final String USER_PROMPT = "Опиши подробно, что изображено на этой картинке.";

    /** How many images one page may cost. A real page in this estate carried 26 attachments. */
    static final int MAX_IMAGES_PER_PAGE = 12;

    /** Two, and the whole design rests on it — see the class javadoc. */
    static final int READS = 2;

    /**
     * One misconfigured endpoint must not burn one upload and two chat calls per image learning the
     * same thing twelve times. Same guard, same number, as {@code RepoCardGenerator}.
     */
    static final int MAX_CONSECUTIVE_FAILURES = 3;

    /** What a page cost, for the caller to print. Mirrors {@code RepoCardGenerator.CardResult}. */
    public record Result(SourceDoc doc, int described, int cached, int skipped, int failed,
                         List<String> notes) {
    }

    private final ConfluenceClient client;
    private final ChatModel model;
    private final AttachmentStore store;
    private final String modelName;
    private final int maxTokens;
    private final Jdbi jdbi;

    public ImageDescriber(ConfluenceClient client, ChatModel model, AttachmentStore store,
            String modelName, int maxTokens, Jdbi jdbi) {
        this.client = client;
        this.model = model;
        this.store = store;
        this.modelName = modelName;
        this.maxTokens = maxTokens;
        this.jdbi = jdbi;
    }

    /**
     * A copy of {@code doc} whose markers carry descriptions, plus what it cost.
     *
     * <p>Never throws. A page that was fetched successfully must survive a gateway that will not
     * describe its pictures — the text is already worth having, and the alternative is losing it to
     * an optional enrichment. Same degradation {@code PlanDrafter} uses for its own model call.
     */
    public Result describe(SourceDoc doc) {
        if (doc.attachments().isEmpty()) {
            return new Result(doc, 0, 0, 0, 0, List.of());
        }
        List<ConfluenceClient.Attachment> listing;
        try {
            listing = client.listAttachments(doc.id(), MAX_IMAGES_PER_PAGE * 4);
        } catch (RuntimeException e) {
            return new Result(doc, 0, 0, 0, 0,
                    List.of("[image] could not list attachments of page " + doc.id() + ": " + e.getMessage()));
        }
        Map<String, ConfluenceClient.Attachment> byName = new LinkedHashMap<>();
        for (ConfluenceClient.Attachment a : listing) {
            byName.putIfAbsent(a.filename(), a);
        }

        List<String> notes = new ArrayList<>();
        Map<String, String> replacements = new LinkedHashMap<>();
        int described = 0;
        int cached = 0;
        int skipped = 0;
        int failed = 0;
        int consecutiveFailures = 0;

        for (String filename : doc.attachments()) {
            if (described + cached >= MAX_IMAGES_PER_PAGE) {
                notes.add("[image] stopped after " + MAX_IMAGES_PER_PAGE + " image(s) on page "
                        + doc.id() + "; " + (doc.attachments().size() - described - cached - skipped)
                        + " not described");
                break;
            }
            ConfluenceClient.Attachment attachment = byName.get(filename);
            if (attachment == null) {
                skipped++;
                notes.add("[image] " + filename + " is referenced by the page but not attached to it");
                continue;
            }
            if (!attachment.isSendableImage()) {
                skipped++;
                notes.add("[image] " + filename + " not described (" + attachment.mediaType() + ", "
                        + attachment.bytes() / 1024 + "K) — the model API takes jpeg, png, tiff or "
                        + "bmp under 15 Mb");
                continue;
            }
            String key = AttachmentDescriptions.key(doc.id(), filename, attachment.version(),
                    modelName, SYSTEM_PROMPT);
            Optional<AttachmentDescriptions.Cached> hit =
                    jdbi == null ? Optional.empty() : AttachmentDescriptions.lookup(jdbi, key);
            if (hit.isPresent()) {
                cached++;
                replacements.put(filename, render(filename, hit.get()));
                continue;
            }
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                skipped++;
                continue;
            }
            try {
                AttachmentDescriptions.Cached fresh = readTwice(attachment);
                if (jdbi != null) {
                    AttachmentDescriptions.store(jdbi, key, doc.id(), filename,
                            attachment.version(), modelName, fresh);
                }
                replacements.put(filename, render(filename, fresh));
                described++;
                consecutiveFailures = 0;
            } catch (RuntimeException e) {
                failed++;
                consecutiveFailures++;
                notes.add("[image] " + filename + " could not be described: " + e.getMessage());
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    notes.add("[image] giving up after " + MAX_CONSECUTIVE_FAILURES
                            + " consecutive failures — the rest of this page's images were skipped");
                }
            }
        }

        return new Result(withDescriptions(doc, replacements), described, cached, skipped, failed,
                List.copyOf(notes));
    }

    /**
     * Upload once, read twice, delete. The delete runs whatever happened: a live store was observed
     * holding 99 files from runs that never cleaned up after themselves.
     */
    private AttachmentDescriptions.Cached readTwice(ConfluenceClient.Attachment attachment) {
        byte[] bytes = client.download(attachment);
        String fileId = store.upload(bytes, attachment.filename(), attachment.mediaType());
        try {
            List<String> readings = new ArrayList<>();
            for (int i = 0; i < READS; i++) {
                readings.add(oneReading(fileId));
            }
            return new AttachmentDescriptions.Cached(readings.get(0),
                    Disagreement.line(readings.get(0), readings.get(readings.size() - 1)));
        } finally {
            store.delete(fileId);
        }
    }

    private String oneReading(String fileId) {
        ChatResponse response = model.complete(new ChatRequest(modelName,
                List.of(ChatMessage.system(SYSTEM_PROMPT),
                        ChatMessage.user(USER_PROMPT, List.of(fileId))),
                List.of(), maxTokens, 0.0));
        String content = response.message().content();
        if (content == null || content.isBlank()) {
            throw new ModelException("the model returned an empty description"
                    + ("length".equals(response.finishReason())
                            ? " (finish_reason=length — raise this endpoint's max_tokens)" : ""), 0);
        }
        return content;
    }

    /**
     * The marker, its provenance, the description, and the flag.
     *
     * <p>Provenance is not decoration. Two readings of a real form disagreed on which bank the
     * document was about; a description that reached a spec looking like human-written text would
     * make that indistinguishable from a requirement somebody checked.
     */
    private static String render(String filename, AttachmentDescriptions.Cached value) {
        StringBuilder out = new StringBuilder("[image: ").append(filename)
                .append(" — model-described, unverified]\n").append(safe(value.description()));
        if (!value.disagreement().isBlank()) {
            out.append('\n').append(value.disagreement());
        }
        return out.toString();
    }

    /**
     * Model prose is untrusted input to a markdown document.
     *
     * <p>This text is inserted into a page body that becomes a spec, and spec text reaches the
     * OpenSpec export — where a line beginning {@code ## } would invent a section and a code fence
     * would close one. {@code SpecParser} also rejects any line starting with {@code #} outright, so
     * an unneutralised heading aborts the run at the re-parse self-check rather than at the model.
     */
    static String safe(String description) {
        return description
                .replaceAll("(?m)^\\s*#+\\s*", "")
                .replace("```", "'''")
                .strip();
    }

    private static SourceDoc withDescriptions(SourceDoc doc, Map<String, String> replacements) {
        if (replacements.isEmpty()) {
            return doc;
        }
        String text = doc.text();
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            text = text.replace("[attachment: " + entry.getKey() + "]", entry.getValue());
        }
        return new SourceDoc(doc.kind(), doc.id(), doc.url(), doc.title(), doc.version(), text,
                doc.attachments());
    }
}
