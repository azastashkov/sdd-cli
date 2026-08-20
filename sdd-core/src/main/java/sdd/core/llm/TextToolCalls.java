package sdd.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reads a tool call an endpoint left sitting in {@code content} instead of structuring it.
 *
 * <p>Only reached under {@link ToolCallStyle#TEXT}, and only when the reply carried no
 * {@code tool_calls} and no {@code function_call}. See that enum for the measurement that forced
 * this to exist.
 *
 * <p><b>Strict by construction, because the alternative is fabricating tool calls out of prose.</b>
 * Three rules do the work, and none of them is a heuristic:
 *
 * <ol>
 *   <li>The name must be one the REQUEST actually declared. This is the load-bearing one: a reply
 *       whose entire content is a JSON object naming a tool this very request offered, carrying
 *       that tool's arguments, is a tool call under any reading. Without it, any model that
 *       answered with JSON — which is most of what sdd asks for — could be misread as calling
 *       something.</li>
 *   <li>The WHOLE content must be consumed. Prose wrapped around a JSON blob is not a call, it is
 *       a model talking about one, and scanning for the blob inside it is exactly the fuzzy-
 *       machinery-for-exact-work mistake this codebase keeps re-learning.</li>
 *   <li>All or nothing. If any element of a multi-call reply fails to validate, none is returned —
 *       a partially-read batch would silently drop a call the model made, which is a wrong answer
 *       rather than a failure.</li>
 * </ol>
 *
 * <p>Three delimiters are recognised, all exact: the bare JSON object or array, a single
 * ```-fenced block, and {@code <tool_call>…</tool_call>} blocks (what Qwen models emit, and this
 * estate's gateway serves Qwen). Nothing else — no bracket-matching, no first-brace-to-last-brace.
 */
final class TextToolCalls {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TAG_OPEN = "<tool_call>";
    private static final String TAG_CLOSE = "</tool_call>";
    private static final String FENCE = "```";

    private TextToolCalls() {
    }

    /**
     * Every tool call spelled out in {@code content}, or an empty list when it is not one.
     *
     * @param declared the names this request offered; an empty set can never match, so a reply to
     *     a request that declared no tools is never read as a call
     */
    static List<ToolCall> read(String content, Set<String> declared) {
        if (content == null || content.isBlank() || declared.isEmpty()) {
            return List.of();
        }
        List<String> blocks = taggedBlocks(content);
        if (blocks.isEmpty()) {
            blocks = List.of(unfenced(content.strip()));
        }
        List<ToolCall> out = new ArrayList<>();
        for (String block : blocks) {
            JsonNode node = tree(block);
            if (node == null) {
                return List.of();
            }
            for (JsonNode candidate : node.isArray() ? node : List.of(node)) {
                ToolCall call = one(candidate, declared, out.size());
                if (call == null) {
                    return List.of();
                }
                out.add(call);
            }
        }
        return List.copyOf(out);
    }

    /** One candidate object, or null if it is not a call this request could have provoked. */
    private static ToolCall one(JsonNode node, Set<String> declared, int index) {
        if (!node.isObject()) {
            return null;
        }
        JsonNode name = node.path("name");
        if (!name.isTextual() || !declared.contains(name.asText())) {
            return null;
        }
        // Indexed as well as named: a model may call the same tool twice in one reply, and two
        // results sharing a tool_call_id would pair against the wrong call.
        return new ToolCall(HttpChatModel.SYNTHETIC_CALL_ID_PREFIX + index + "-" + name.asText(),
                name.asText(), argumentsOf(node));
    }

    /** {@code arguments} as the JSON string {@link ToolCall} holds — an object, a string
     *  containing JSON, or absent for a tool that takes none. */
    private static String argumentsOf(JsonNode node) {
        JsonNode args = node.path("arguments");
        if (args.isMissingNode() || args.isNull()) {
            return "{}";
        }
        return args.isTextual() ? args.asText() : args.toString();
    }

    private static JsonNode tree(String text) {
        try {
            JsonNode node = JSON.readTree(text);
            return node.isObject() || node.isArray() ? node : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** The contents of every {@code <tool_call>} block, in order; empty when there are none. */
    private static List<String> taggedBlocks(String content) {
        List<String> blocks = new ArrayList<>();
        int from = 0;
        while (true) {
            int open = content.indexOf(TAG_OPEN, from);
            if (open < 0) {
                return blocks;
            }
            int close = content.indexOf(TAG_CLOSE, open + TAG_OPEN.length());
            if (close < 0) {
                // An unterminated block is a truncated reply, not a call. Refusing the whole
                // content is right: what was cut off may have been a second call.
                return List.of();
            }
            blocks.add(content.substring(open + TAG_OPEN.length(), close).strip());
            from = close + TAG_CLOSE.length();
        }
    }

    /** The body of a single ```-fenced block, or the input unchanged when it is not fenced. An
     *  info string ({@code ```json}) is dropped with the opening line. */
    private static String unfenced(String content) {
        if (!content.startsWith(FENCE) || !content.endsWith(FENCE) || content.length() < 2 * FENCE.length()) {
            return content;
        }
        int firstNewline = content.indexOf('\n');
        if (firstNewline < 0) {
            return content;
        }
        return content.substring(firstNewline + 1, content.length() - FENCE.length()).strip();
    }
}
