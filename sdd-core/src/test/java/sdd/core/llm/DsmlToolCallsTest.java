package sdd.core.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixtures are the exact strings a live {@code sdd explore} turn produced, copied out of
 * {@code transcript.jsonl} — not reconstructions of what the format probably looks like.
 */
class DsmlToolCallsTest {

    private static final Set<String> DECLARED =
            Set.of("list_repos", "search_code", "read_file", "apply_edit");

    /** Turn 1, verbatim: a parameter the tool does not take, with an empty value. */
    private static final String TURN_1 =
            "<|DSML|tool_calls>\n<|DSML|invoke name=\"list_repos\">\n"
            + "<|DSML|parameter name=\"pattern\" string=\"true\"></|DSML|parameter>\n"
            + "</|DSML|invoke>\n</|DSML|tool_calls>";

    /** Turns 2 and 3, verbatim: no parameters at all. */
    private static final String TURN_2 =
            "<|DSML|tool_calls>\n<|DSML|invoke name=\"list_repos\">\n\n"
            + "</|DSML|invoke>\n</|DSML|tool_calls>";

    @Test
    void theCallSddMissedIsRead() {
        assertThat(TextToolCalls.read(TURN_1, DECLARED)).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("list_repos");
            // Faithful, not tidied: the model sent an empty `pattern`, so that is what is
            // recorded. Dropping it would be a silent edit to what the model actually asked for.
            assertThat(c.argumentsJson()).isEqualTo("{\"pattern\":\"\"}");
            assertThat(c.id()).startsWith(HttpChatModel.SYNTHETIC_CALL_ID_PREFIX);
        });
    }

    @Test
    void aCallWithNoParametersIsRead() {
        assertThat(TextToolCalls.read(TURN_2, DECLARED)).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("list_repos");
            assertThat(c.argumentsJson()).isEqualTo("{}");
        });
    }

    @Test
    void aParameterValueIsTheTextBetweenItsTags() {
        assertThat(TextToolCalls.read(
                "<|DSML|invoke name=\"search_code\">\n"
                + "<|DSML|parameter name=\"regex\" string=\"true\">tier\\.lvc\\.map</|DSML|parameter>\n"
                + "</|DSML|invoke>", DECLARED))
                .singleElement()
                .satisfies(c -> assertThat(c.argumentsJson())
                        .isEqualTo("{\"regex\":\"tier\\\\.lvc\\\\.map\"}"));
    }

    // apply_edit's search/replace must match a file byte for byte. Stripping would trim an
    // indented first line and produce an edit that silently does not apply — so exactly one
    // layout newline goes at each end, and the interior is untouched.
    @Test
    void onlyTheTemplatesOwnLineBreaksAreRemovedFromAValue() {
        List<ToolCall> calls = TextToolCalls.read(
                "<|DSML|invoke name=\"apply_edit\">\n"
                + "<|DSML|parameter name=\"search\" string=\"true\">\n"
                + "    int x = 1;\n"
                + "</|DSML|parameter>\n"
                + "</|DSML|invoke>", DECLARED);

        assertThat(calls).singleElement()
                .satisfies(c -> assertThat(c.argumentsJson()).isEqualTo("{\"search\":\"    int x = 1;\"}"));
    }

    @Test
    void severalInvokeBlocksAreSeveralCallsWithDistinctIds() {
        List<ToolCall> calls = TextToolCalls.read(
                "<|DSML|tool_calls>\n"
                + "<|DSML|invoke name=\"read_file\">"
                + "<|DSML|parameter name=\"path\">a/A.java</|DSML|parameter></|DSML|invoke>\n"
                + "<|DSML|invoke name=\"read_file\">"
                + "<|DSML|parameter name=\"path\">a/B.java</|DSML|parameter></|DSML|invoke>\n"
                + "</|DSML|tool_calls>", DECLARED);

        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).id()).isNotEqualTo(calls.get(1).id());
        assertThat(calls.get(1).argumentsJson()).isEqualTo("{\"path\":\"a/B.java\"}");
    }

    // DeepSeek's tokenizer spells its sentinels with U+FF5C FULLWIDTH VERTICAL LINE, not ASCII
    // '|'. In a terminal the two are nearly indistinguishable — the fullwidth form just looks like
    // a bar with padding — so a transcript can read as ASCII while matching nothing.
    private static String fullwidth(String ascii) {
        return ascii.replace('|', '\uFF5C');
    }

    @Test
    void fullwidthSentinelBarsAreReadTheSameAsAsciiOnes() {
        assertThat(TextToolCalls.read(fullwidth(TURN_1), DECLARED)).singleElement()
                .satisfies(c -> {
                    assertThat(c.name()).isEqualTo("list_repos");
                    assertThat(c.argumentsJson()).isEqualTo("{\"pattern\":\"\"}");
                });
        assertThat(TextToolCalls.read(fullwidth(TURN_2), DECLARED)).hasSize(1);
    }

    // Folding happens on a copy used only to LOCATE tags. A bar inside a value is content — an
    // apply_edit search argument has to match a file exactly — so it must survive untouched.
    @Test
    void aFullwidthBarInsideAnArgumentValueIsNotFoldedToAscii() {
        String call = fullwidth("<|DSML|invoke name=\"search_code\">"
                + "<|DSML|parameter name=\"regex\">PLACEHOLDER</|DSML|parameter>"
                + "</|DSML|invoke>").replace("PLACEHOLDER", "a\uFF5Cb");

        assertThat(TextToolCalls.read(call, DECLARED)).singleElement()
                .satisfies(c -> assertThat(c.argumentsJson()).isEqualTo("{\"regex\":\"a\uFF5Cb\"}"));
    }

    // Measured: the SAME three-turn run wrote a bare <invoke> on turn 1 and <｜DSML｜invoke> on
    // turns 2 and 3. A parser that demanded the prefix would read two turns and silently skip the
    // third — worse than reading none, because the run would look intermittently broken.
    private static final String TURN_1_NO_SENTINEL =
            "<\uFF5CDSML\uFF5Ctool_calls>\n<invoke name=\"list_repos\">\n"
            + "<parameter name=\"pattern\" string=\"true\"></parameter>\n"
            + "</invoke>\n</\uFF5CDSML\uFF5Ctool_calls>";

    @Test
    void aBareInvokeTagWithNoSentinelPrefixIsRead() {
        assertThat(TextToolCalls.read(TURN_1_NO_SENTINEL, DECLARED)).singleElement()
                .satisfies(c -> {
                    assertThat(c.name()).isEqualTo("list_repos");
                    assertThat(c.argumentsJson()).isEqualTo("{\"pattern\":\"\"}");
                });
    }

    @Test
    void bothFormsAreReadWithinOneReply() {
        List<ToolCall> calls = TextToolCalls.read(
                "<invoke name=\"read_file\">"
                + "<parameter name=\"path\">a/A.java</parameter></invoke>\n"
                + fullwidth("<|DSML|invoke name=\"read_file\">"
                        + "<|DSML|parameter name=\"path\">a/B.java</|DSML|parameter></|DSML|invoke>"),
                DECLARED);

        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).argumentsJson()).isEqualTo("{\"path\":\"a/A.java\"}");
        assertThat(calls.get(1).argumentsJson()).isEqualTo("{\"path\":\"a/B.java\"}");
    }

    // The sentinel pattern is bounded — no '|' and no '>' inside — so it can only ever consume a
    // sentinel, never run past a tag's end and swallow content as if it were a name.
    @Test
    void aTagNameThatMerelyEndsInInvokeIsNotAnInvokeTag() {
        assertThat(TextToolCalls.read(
                "<notaninvoke name=\"list_repos\"></notaninvoke>", DECLARED)).isEmpty();
        assertThat(TextToolCalls.read(
                "<invoked name=\"list_repos\"></invoked>", DECLARED)).isEmpty();
    }

    // Verbatim from a live turn the parser skipped: self-closing, no separate </invoke>. The same
    // model writes both this and the paired form, so demanding a close tag reads some turns and
    // silently drops others — which reads as an intermittently broken endpoint.
    @Test
    void aSelfClosingInvokeTagIsACallWithNoArguments() {
        assertThat(TextToolCalls.read(fullwidth(
                "<|DSML|tool_calls>\n<|DSML|invoke name=\"list_repos\"/>\n</|DSML|tool_calls>"),
                DECLARED))
                .singleElement().satisfies(c -> {
                    assertThat(c.name()).isEqualTo("list_repos");
                    assertThat(c.argumentsJson()).isEqualTo("{}");
                });
    }

    @Test
    void selfClosingAndPairedInvokesMixInOneReply() {
        List<ToolCall> calls = TextToolCalls.read(
                "<invoke name=\"list_repos\"/>\n"
                + "<invoke name=\"read_file\">"
                + "<parameter name=\"path\">a/A.java</parameter></invoke>", DECLARED);

        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).argumentsJson()).isEqualTo("{}");
        assertThat(calls.get(1).argumentsJson()).isEqualTo("{\"path\":\"a/A.java\"}");
    }

    @Test
    void aSelfClosingParameterIsAnEmptyValue() {
        assertThat(TextToolCalls.read(
                "<invoke name=\"search_code\"><parameter name=\"repo\"/></invoke>", DECLARED))
                .singleElement()
                .satisfies(c -> assertThat(c.argumentsJson()).isEqualTo("{\"repo\":\"\"}"));
    }

    @Test
    void aNameTheRequestNeverDeclaredIsNotACall() {
        assertThat(TextToolCalls.read(
                "<|DSML|invoke name=\"rm_rf\"><|DSML|parameter name=\"path\">/</|DSML|parameter>"
                + "</|DSML|invoke>", DECLARED)).isEmpty();
    }

    @Test
    void aReplyToARequestThatDeclaredNoToolsIsNeverACall() {
        assertThat(TextToolCalls.read(TURN_2, Set.of())).isEmpty();
    }

    // A truncated reply may have been cut mid-way through a second call, so returning what
    // survived would under-report what the model asked for.
    @Test
    void anUnterminatedBlockRefusesTheWholeContent() {
        assertThat(TextToolCalls.read(
                "<|DSML|invoke name=\"list_repos\">\n", DECLARED)).isEmpty();
        assertThat(TextToolCalls.read(
                "<|DSML|invoke name=\"read_file\">"
                + "<|DSML|parameter name=\"path\">a/A.java</|DSML|invoke>", DECLARED)).isEmpty();
    }

    @Test
    void anInvokeTagWithNoNameIsNotACall() {
        assertThat(TextToolCalls.read("<|DSML|invoke ></|DSML|invoke>", DECLARED)).isEmpty();
    }

    // The envelope is not required: the invoke tag plus the declared-name check is what makes this
    // exact, and demanding a wrapper too would reject a reply that is already unambiguous.
    @Test
    void theToolCallsEnvelopeIsOptional() {
        assertThat(TextToolCalls.read(
                "<|DSML|invoke name=\"list_repos\"></|DSML|invoke>", DECLARED)).hasSize(1);
    }

    // Prose that merely mentions the tags is not a call — nothing here opens an invoke block.
    @Test
    void proseAboutTheFormatIsNotACall() {
        assertThat(TextToolCalls.read(
                "I would normally emit a <|DSML|tool_calls> block here, but I cannot.", DECLARED))
                .isEmpty();
    }
}
