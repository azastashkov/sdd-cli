package sdd.plan.confluence;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ChatRequest;
import sdd.core.llm.ChatResponse;
import sdd.plan.source.SourceBudget;
import sdd.plan.source.SourceBundle;
import sdd.plan.source.SourceDoc;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;
import sdd.plan.spec.Touchpoint;

import java.util.ArrayList;
import java.util.List;

/**
 * The single model call site of Phase 3A: maps extracted Confluence text into the internal
 * spec model. IDs are assigned here, never trusted from the model; anything the model could
 * not place confidently lands in Open Questions. The result is ALWAYS human-gated — the
 * caller renders it to a file for Gate-1 review; nothing consumes it directly.
 */
public final class ConfluenceNormalizer {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Gate review I2: the pre-Task-3 single-document prompt, byte-for-byte — with no
     *  {@code atlassian:} block at all, {@code sdd plan &lt;export&gt;.html} is a pre-existing path
     *  and its default-off promise means the planner must see EXACTLY this, not a superset with one
     *  more rule tacked on. The multi-source conflict-resolution rule below only makes sense once
     *  there is more than one document to disagree with each other, so it is appended only then —
     *  see {@link #systemPrompt}. */
    static final String SYSTEM_PROMPT_BASE = """
            You convert one raw feature-specification document into strict JSON for a \
            spec-driven development pipeline. Return exactly ONE JSON object - no markdown \
            fences, no commentary - with exactly these fields:
            {"title": string, "owner": string, "status": string, "goal": string,
             "background": string, "requirements": [string, ...], "acceptance": [string, ...],
             "constraints": [string, ...],
             "touchpoints": [{"kind": "repo"|"endpoint"|"topic"|"class"|"artifact"|"config", "value": string}, ...],
             "out_of_scope": [string, ...], "open_questions": [string, ...], "unmapped": [string, ...]}
            Rules:
            - Use only information present in the document. Never invent requirements.
            - "requirements" are behaviours to build; "acceptance" are checks that prove them.
            - "goal" is 1-3 sentences; longer context belongs in "background".
            - Use "" for owner/status when the document does not state them.
            - Anything you cannot confidently place goes into "unmapped" verbatim.
            """;

    /** The multi-source addendum — noise for a single-source spec (there is nothing for a lone
     *  document to conflict WITH), so it is appended only when {@link #normalize} is handed more
     *  than one document. */
    private static final String MULTI_SOURCE_ADDENDUM =
            "- When multiple sources disagree, prefer the Confluence page over the Jira "
            + "description, and record the conflict in \"unmapped\" so a human resolves it.\n";

    /** The full, multi-source prompt — {@link #SYSTEM_PROMPT_BASE} plus {@link
     *  #MULTI_SOURCE_ADDENDUM}. Exposed as its own constant (rather than only computed inline in
     *  {@link #systemPrompt}) because it is also the shape existing tests and callers pin. */
    static final String SYSTEM_PROMPT = SYSTEM_PROMPT_BASE + MULTI_SOURCE_ADDENDUM;

    private ConfluenceNormalizer() {
    }

    /**
     * Converts a {@link SourceBundle} — one or more Jira/Confluence/free-text documents — into
     * the internal spec model with a single model call. The bundle is budget-capped first
     * ({@link SourceBudget}): documents dropped there arrive as {@link SourceBundle#notes()}
     * exactly like any other bundle-level note, so both flow into Open Questions the same way.
     */
    public static NormalizedSpec normalize(SourceBundle bundle, ChatModel planner,
                                           String modelName, int maxTokens, String fallbackId) {
        SourceBundle capped = SourceBudget.apply(bundle);
        boolean multiSource = capped.docs().size() > 1;
        String systemPrompt = multiSource ? SYSTEM_PROMPT : SYSTEM_PROMPT_BASE;
        ChatResponse response = planner.complete(new ChatRequest(modelName,
                List.of(ChatMessage.system(systemPrompt), ChatMessage.user(renderDocs(capped.docs()))),
                List.of(), maxTokens, 0.15));
        if ("length".equals(response.finishReason())) {
            throw new SpecNormalizationException(
                    "planner response truncated (finish_reason=length) — raise models.planner.max_tokens");
        }
        JsonNode root = parseJson(response.message().content());

        List<String> questionTexts = new ArrayList<>(strings(root, "open_questions"));
        for (String unmapped : strings(root, "unmapped")) {
            questionTexts.add("[unmapped] " + unmapped);
        }
        List<Touchpoint> touchpoints = new ArrayList<>();
        for (JsonNode node : root.path("touchpoints")) {
            String kindText = oneLine(node.path("kind").asText());
            Touchpoint.Kind kind = Touchpoint.Kind.fromKey(kindText);
            String value = oneLine(node.path("value").asText());
            if (kind == null || value.isBlank()) {
                questionTexts.add("[unmapped touchpoint] " + kindText + ": " + value);
            } else {
                touchpoints.add(new Touchpoint(kind, value));
            }
        }
        for (String note : capped.notes()) {
            questionTexts.add("[source] " + oneLine(note));
        }
        return new NormalizedSpec(
                fallbackId,
                text(root, "title", fallbackId),
                text(root, "owner", "unknown"),
                text(root, "status", "draft"),
                prose(root, "goal"),
                prose(root, "background"),
                numbered("R", strings(root, "requirements")),
                numbered("A", strings(root, "acceptance")),
                numbered("C", strings(root, "constraints")),
                touchpoints,
                strings(root, "out_of_scope"),
                numbered("Q", questionTexts),
                attachmentUnion(capped.docs()));
    }

    /** "## Source N: <label>\n<text>" per document, blank-line separated — the model sees the
     *  same document boundaries a human reviewer would, instead of one undifferentiated blob
     *  once a spec has more than one source.
     *
     *  <p>Gate review I2: a single document renders as its bare {@code text()}, with no header at
     *  all — byte-identical to the pre-Task-3 user message ({@code extracted.text()}). A header
     *  naming "Source 1" is meaningless noise when there is no Source 2 to distinguish it from, and
     *  the default-off promise for the single-document path means the planner must see exactly what
     *  it always did. */
    private static String renderDocs(List<SourceDoc> docs) {
        if (docs.size() == 1) {
            return docs.get(0).text();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            if (i > 0) {
                sb.append("\n\n");
            }
            SourceDoc doc = docs.get(i);
            sb.append("## Source ").append(i + 1).append(": ").append(doc.label())
                    .append('\n').append(doc.text());
        }
        return sb.toString();
    }

    /** Every kept document's attachments, de-duplicated, in first-seen order — a document
     *  dropped for budget contributes no attachments either, since the model never saw it.
     *
     *  <p>Gate review I2: a single document instead maps straight through with no de-duplication
     *  pass — byte-identical to the pre-Task-3 {@code extracted.attachments().stream().map(...)
     *  .toList()}. De-duplication only exists to merge attachments ACROSS documents; running it
     *  over one document's own list is an unasked-for behaviour change (a document that legitimately
     *  lists the same attachment name twice would see it collapsed to one) for a path that promised
     *  not to change at all. */
    private static List<String> attachmentUnion(List<SourceDoc> docs) {
        if (docs.size() == 1) {
            return docs.get(0).attachments().stream().map(ConfluenceNormalizer::oneLine).toList();
        }
        List<String> attachments = new ArrayList<>();
        for (SourceDoc doc : docs) {
            for (String attachment : doc.attachments()) {
                String clean = oneLine(attachment);
                if (!attachments.contains(clean)) {
                    attachments.add(clean);
                }
            }
        }
        return attachments;
    }

    private static JsonNode parseJson(String content) {
        if (content == null) {
            throw new SpecNormalizationException("planner returned no content");
        }
        String stripped = content.strip();
        if (stripped.startsWith("```")) {
            int firstNewline = stripped.indexOf('\n');
            int lastFence = stripped.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                stripped = stripped.substring(firstNewline + 1, lastFence).strip();
            }
        }
        try {
            JsonNode root = JSON.readTree(stripped);
            if (!root.isObject()) {
                throw new SpecNormalizationException("planner output is not a JSON object");
            }
            return root;
        } catch (JacksonException e) {
            String snippet = stripped.length() > 200 ? stripped.substring(0, 200) + "..." : stripped;
            throw new SpecNormalizationException("planner output is not valid JSON: " + snippet, e);
        }
    }

    private static List<String> strings(JsonNode root, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode node : root.path(field)) {
            String value = oneLine(node.asText());
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private static String text(JsonNode root, String field, String fallback) {
        String value = oneLine(root.path(field).asText());
        return value.isBlank() ? fallback : value;
    }

    /** Bullets and front-matter scalars are one-line by the spec grammar. */
    private static String oneLine(String value) {
        return value.replaceAll("(?U)\\s+", " ").strip();
    }

    /** Prose keeps newlines but may never collide with the '#' section grammar. */
    private static String prose(JsonNode root, String field) {
        return root.path(field).asText().replaceAll("(?m)^\\s*#+\\s*", "").strip();
    }

    private static List<SpecItem> numbered(String prefix, List<String> texts) {
        List<SpecItem> items = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            items.add(new SpecItem(prefix + (i + 1), texts.get(i)));
        }
        return items;
    }
}
