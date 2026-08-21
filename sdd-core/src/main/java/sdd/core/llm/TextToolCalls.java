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
 * <p>Four dialects are recognised, all exactly delimited: the bare JSON object or array, a single
 * ```-fenced block, {@code <tool_call>…</tool_call>} blocks (what Qwen models emit), and the
 * DeepSeek tag form, which is not JSON at all and is handled by {@link DsmlToolCalls}. Nothing
 * else — no bracket-matching, no first-brace-to-last-brace.
 *
 * <p>Within the JSON dialects the KEYS vary too. The tool is read from {@code name}, a textual
 * {@code function}, or {@code tool}; its input from {@code arguments}, {@code parameters}, or
 * {@code input}; and a nested {@code {"function": {"name": …}}} is unwrapped first. Every one of
 * those was observed from a single gateway — the first two spellings from one model and the third
 * ({@code {"tool": …, "input": …}}, fenced) from another on the same host. This is not a heuristic
 * and does not weaken rule 1: whatever key the name arrives under, it must still be one the
 * request declared.
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
        // A tag dialect never parses as JSON, so it is answered first rather than after a failure.
        if (DsmlToolCalls.present(content)) {
            return DsmlToolCalls.read(content, declared);
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
        // A nested {"function": {...}} carries the call one level down; unwrap before reading.
        JsonNode call = node.path("function").isObject() ? node.get("function") : node;
        String name = nameOf(call);
        if (name == null || !declared.contains(name)) {
            return null;
        }
        // Indexed as well as named: a model may call the same tool twice in one reply, and two
        // results sharing a tool_call_id would pair against the wrong call.
        return new ToolCall(HttpChatModel.SYNTHETIC_CALL_ID_PREFIX + index + "-" + name,
                name, argumentsOf(call));
    }

    /**
     * The called tool's name, under whichever key this model spells it.
     *
     * <p>{@code name} is the OpenAI spelling. {@code function} as a STRING is the one a live
     * gateway produced — {@code {"function": "report_status", "parameters": {…}}} — repeatedly and
     * across declaration counts, and it was being refused as prose. Accepting the alias loosens
     * nothing that matters: the name must still be one the request declared, which is the rule
     * that stops JSON in a reply from being read as a call.
     */
    private static String nameOf(JsonNode node) {
        for (String key : new String[] {"name", "function", "tool"}) {
            JsonNode value = node.path(key);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    /**
     * The call's arguments as the JSON string {@link ToolCall} holds — an object, a string
     * containing JSON, or absent for a tool that takes none.
     *
     * <p>{@code parameters} is accepted beside {@code arguments} for the same reason
     * {@link #nameOf} accepts {@code function}: it is what a live gateway emitted, and the pair
     * travels together.
     */
    private static String argumentsOf(JsonNode node) {
        for (String key : new String[] {"arguments", "parameters", "input"}) {
            JsonNode args = node.path(key);
            if (args.isMissingNode() || args.isNull()) {
                continue;
            }
            return args.isTextual() ? args.asText() : args.toString();
        }
        return "{}";
    }

    /**
     * The tool name this content calls, DECLARED OR NOT, or null when it is not a call at all.
     *
     * <p>Exists so a diagnostic can explain a refusal in the parser's own terms instead of
     * re-deriving them. {@code ToolCallProbe} used its own JSON check and reported a fenced
     * {@code ```json {"tool": "set_status", …}``` } as "answered in prose", because it never
     * unfenced — a wrong diagnosis of a real fault. Reusing this means the explanation and the
     * decision can never disagree.
     */
    static String calledName(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        List<String> blocks = taggedBlocks(content);
        if (blocks.isEmpty()) {
            blocks = List.of(unfenced(content.strip()));
        }
        for (String block : blocks) {
            JsonNode node = tree(block);
            if (node == null) {
                return null;
            }
            for (JsonNode candidate : node.isArray() ? node : List.of(node)) {
                if (!candidate.isObject()) {
                    continue;
                }
                JsonNode call = candidate.path("function").isObject()
                        ? candidate.get("function") : candidate;
                String name = nameOf(call);
                if (name != null) {
                    return name;
                }
            }
        }
        return null;
    }

    /** The content a JSON dialect actually offers, with any fence or tag stripped. */
    static String candidateJson(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        List<String> blocks = taggedBlocks(content);
        return blocks.isEmpty() ? unfenced(content.strip()) : blocks.get(0);
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
