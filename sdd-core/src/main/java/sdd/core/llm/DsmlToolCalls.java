package sdd.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a tool call written in the tag dialect DeepSeek models emit.
 *
 * <p>Measured verbatim from an {@code sdd explore} turn against a gateway that does not structure
 * tool calls — the model chose the right tool, in the right order, and the call arrived as content:
 *
 * <pre>
 * &lt;|DSML|tool_calls&gt;
 * &lt;|DSML|invoke name="list_repos"&gt;
 * &lt;|DSML|parameter name="pattern" string="true"&gt;*&lt;/|DSML|parameter&gt;
 * &lt;/|DSML|invoke&gt;
 * &lt;/|DSML|tool_calls&gt;
 * </pre>
 *
 * <p>An argument's value is the text BETWEEN its parameter tags, not an attribute.
 * {@code string="true"} is a type annotation, and it is deliberately ignored: every tool argument
 * in this codebase is declared {@code "type":"string"} and every reader takes it with
 * {@code asText()}, so inferring types here would be machinery serving no caller. If a non-string
 * argument is ever declared, that attribute is where the type comes from — it is not being
 * discarded for lack of a place to put it.
 *
 * <p>Strictness matches {@link TextToolCalls}, and for the same reason — misreading prose as a call
 * RUNS A REAL TOOL. The name must be one the request declared, and a block that opens without
 * closing refuses the whole content rather than returning what survived, because a truncated reply
 * may have been cut mid-way through a second call.
 *
 * <p>The sentinel prefix is OPTIONAL, and that is not defensive generality — the same model emits
 * both forms in one run. Measured over three turns: turn 1 wrote {@code <invoke name="list_repos">}
 * and turns 2 and 3 wrote {@code <｜DSML｜invoke …>}. The bars are also U+FF5C, not ASCII; see
 * {@link #FULLWIDTH_BAR}.
 *
 * <p>The {@code tool_calls} envelope is not required either. The {@code invoke} tag plus the
 * declared-name check is what makes this exact; demanding a wrapper as well would fail a reply that
 * is otherwise unambiguous, which is strictness that buys nothing.
 */
final class DsmlToolCalls {

    /**
     * An optional sentinel between {@code <} and the tag name — {@code |DSML|} in what has been
     * observed, matched generically because the model uses BOTH forms in a single run: measured on
     * one three-turn run, turn 1 wrote {@code <invoke name="list_repos">} while turns 2 and 3 wrote
     * {@code <｜DSML｜invoke …>}. A parser that demanded the prefix would have read two turns and
     * silently skipped the third, which is worse than reading none.
     *
     * <p>Bounded on purpose: no {@code |} and no {@code >} inside, so this can only ever consume a
     * sentinel, never run past the end of a tag and swallow content.
     */
    private static final String SENTINEL = "(?:\\|[^|>]*\\|)?";
    private static final Pattern INVOKE_OPEN = Pattern.compile("<" + SENTINEL + "invoke\\b[^>]*>");
    private static final Pattern INVOKE_CLOSE = Pattern.compile("</" + SENTINEL + "invoke>");
    private static final Pattern PARAM_OPEN = Pattern.compile("<" + SENTINEL + "parameter\\b[^>]*>");
    private static final Pattern PARAM_CLOSE = Pattern.compile("</" + SENTINEL + "parameter>");
    private static final Pattern NAME = Pattern.compile("name=\"([^\"]*)\"");
    private static final ObjectMapper JSON = new ObjectMapper();

    private DsmlToolCalls() {
    }

    /**
     * The sentinel bar these tags are actually built from.
     *
     * <p>DeepSeek's tokenizer spells its control markers with U+FF5C FULLWIDTH VERTICAL LINE, not
     * ASCII {@code |} — {@code <｜begin▁of▁sentence｜>} is the same family. In a terminal the two
     * are nearly indistinguishable: the fullwidth form just looks like a bar with padding around
     * it, which is exactly how it appeared in the transcript that produced this parser.
     */
    private static final char FULLWIDTH_BAR = '\uFF5C';

    /** Whether this content is worth handing to {@link #read} at all. */
    static boolean present(String content) {
        return content != null && INVOKE_OPEN.matcher(scannable(content)).find();
    }

    /**
     * The content with fullwidth bars folded to ASCII, for LOCATING tags only.
     *
     * <p>{@link String#replace(char, char)} is one character for one character, so every index into
     * this string is also valid in the original. That is what lets tags be found here while every
     * argument VALUE is cut from the untouched original: folding a bar inside a value would be a
     * silent edit to what the model asked for, and a search-and-replace argument has to match a
     * file exactly.
     */
    private static String scannable(String content) {
        return content.indexOf(FULLWIDTH_BAR) < 0 ? content : content.replace(FULLWIDTH_BAR, '|');
    }

    /** Every call in the content, or empty when any part of it does not hold up. */
    static List<ToolCall> read(String raw, Set<String> declared) {
        String content = scannable(raw);
        Matcher open = INVOKE_OPEN.matcher(content);
        Matcher close = INVOKE_CLOSE.matcher(content);
        List<ToolCall> out = new ArrayList<>();
        int from = 0;
        while (open.find(from)) {
            if (!close.find(open.end())) {
                // An unterminated block is a truncated reply. What was cut off may have been
                // another call, so returning what survived would under-report the model's ask.
                return List.of();
            }
            String name = attribute(open.group());
            if (name == null || !declared.contains(name)) {
                return List.of();
            }
            // Located on the folded copy, cut from the original: same indices, untouched bytes.
            ObjectNode args = arguments(content.substring(open.end(), close.start()),
                    raw.substring(open.end(), close.start()));
            if (args == null) {
                return List.of();
            }
            // Indexed as well as named: a model may call the same tool twice in one reply, and two
            // results sharing a tool_call_id would pair against the wrong call.
            out.add(new ToolCall(HttpChatModel.SYNTHETIC_CALL_ID_PREFIX + out.size() + "-" + name,
                    name, args.toString()));
            from = close.end();
        }
        return List.copyOf(out);
    }

    /** Every parameter in one invoke body, or null if a parameter tag does not close.
     *  {@code body} is the folded copy used to find tags; {@code rawBody} is the original the
     *  values themselves are cut from. */
    private static ObjectNode arguments(String body, String rawBody) {
        Matcher open = PARAM_OPEN.matcher(body);
        Matcher close = PARAM_CLOSE.matcher(body);
        ObjectNode args = JSON.createObjectNode();
        int from = 0;
        while (open.find(from)) {
            if (!close.find(open.end())) {
                return null;
            }
            String name = attribute(open.group());
            if (name == null) {
                return null;
            }
            args.put(name, value(rawBody.substring(open.end(), close.start())));
            from = close.end();
        }
        return args;
    }

    /**
     * A parameter's value, with the template's own line breaks removed and nothing else.
     *
     * <p>One leading and one trailing newline, not {@code strip()}: these tags are rendered one per
     * line, so those two characters are layout rather than content — but the interior is not.
     * {@code apply_edit}'s {@code search} and {@code replace} arguments have to match a file byte
     * for byte, so an indented first line that got trimmed here would produce an edit that silently
     * does not apply.
     */
    private static String value(String raw) {
        String out = raw;
        if (out.startsWith("\r\n")) {
            out = out.substring(2);
        } else if (out.startsWith("\n")) {
            out = out.substring(1);
        }
        if (out.endsWith("\r\n")) {
            out = out.substring(0, out.length() - 2);
        } else if (out.endsWith("\n")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String attribute(String tag) {
        Matcher m = NAME.matcher(tag);
        return m.find() ? m.group(1) : null;
    }
}
