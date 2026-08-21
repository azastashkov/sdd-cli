package sdd.core.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The strictness rules, pinned. Everything this class refuses to read is a tool call it would
 * otherwise have fabricated out of a model's prose, and a fabricated call runs a real tool.
 */
class TextToolCallsTest {

    private static final Set<String> DECLARED = Set.of("sdd_probe_ack", "read_file");

    // The exact reply measured on the corp gateway: the model complied, and only the structuring
    // step was missing.
    @Test
    void aBareJsonCallIsRead() {
        List<ToolCall> calls = TextToolCalls.read(
                "{\"name\": \"sdd_probe_ack\", \"arguments\": {\"status\": \"ok\"}}", DECLARED);

        assertThat(calls).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("sdd_probe_ack");
            assertThat(c.argumentsJson()).isEqualTo("{\"status\":\"ok\"}");
            assertThat(c.id()).startsWith(HttpChatModel.SYNTHETIC_CALL_ID_PREFIX);
        });
    }

    // THE load-bearing rule. Without it any JSON answer — which is most of what sdd asks for —
    // could be misread as a call.
    @Test
    void aNameTheRequestNeverDeclaredIsNotACall() {
        assertThat(TextToolCalls.read(
                "{\"name\": \"rm_rf\", \"arguments\": {\"path\": \"/\"}}", DECLARED)).isEmpty();
    }

    @Test
    void aReplyToARequestThatDeclaredNoToolsIsNeverACall() {
        assertThat(TextToolCalls.read(
                "{\"name\": \"sdd_probe_ack\", \"arguments\": {}}", Set.of())).isEmpty();
    }

    // Prose around a blob is a model talking ABOUT a call, not making one. Scanning for the blob
    // inside it is the fuzzy-machinery-for-exact-work mistake this codebase keeps re-learning.
    @Test
    void proseWrappedAroundACallIsNotACall() {
        assertThat(TextToolCalls.read(
                "I will now call {\"name\": \"sdd_probe_ack\", \"arguments\": {}} for you.", DECLARED))
                .isEmpty();
    }

    @Test
    void anOrdinaryJsonAnswerWithNoNameFieldIsNotACall() {
        assertThat(TextToolCalls.read("{\"card_md\":\"ok\",\"card_line\":\"ok\"}", DECLARED)).isEmpty();
    }

    @Test
    void plainProseIsNotACall() {
        assertThat(TextToolCalls.read("I cannot help with that.", DECLARED)).isEmpty();
        assertThat(TextToolCalls.read("   ", DECLARED)).isEmpty();
        assertThat(TextToolCalls.read(null, DECLARED)).isEmpty();
    }

    @Test
    void aFencedBlockIsUnwrapped() {
        assertThat(TextToolCalls.read("""
                ```json
                {"name": "read_file", "arguments": {"path": "A.java"}}
                ```""", DECLARED))
                .singleElement()
                .satisfies(c -> assertThat(c.argumentsJson()).isEqualTo("{\"path\":\"A.java\"}"));
    }

    // What Qwen models emit, and this estate's gateway serves Qwen.
    @Test
    void toolCallTagsAreRead() {
        assertThat(TextToolCalls.read(
                "<tool_call>{\"name\": \"read_file\", \"arguments\": {\"path\": \"A.java\"}}</tool_call>",
                DECLARED)).singleElement().satisfies(c -> assertThat(c.name()).isEqualTo("read_file"));
    }

    @Test
    void severalTaggedBlocksAreSeveralCalls() {
        List<ToolCall> calls = TextToolCalls.read("""
                <tool_call>{"name": "read_file", "arguments": {"path": "A.java"}}</tool_call>
                <tool_call>{"name": "read_file", "arguments": {"path": "B.java"}}</tool_call>""",
                DECLARED);

        assertThat(calls).hasSize(2);
        // The same tool twice: two results sharing one id would pair against the wrong call.
        assertThat(calls.get(0).id()).isNotEqualTo(calls.get(1).id());
        assertThat(calls.get(1).argumentsJson()).isEqualTo("{\"path\":\"B.java\"}");
    }

    @Test
    void aJsonArrayIsSeveralCalls() {
        assertThat(TextToolCalls.read("""
                [{"name": "read_file", "arguments": {"path": "A.java"}},
                 {"name": "sdd_probe_ack", "arguments": {"status": "ok"}}]""", DECLARED))
                .hasSize(2);
    }

    // All or nothing: a partially-read batch would silently drop a call the model made, which is a
    // wrong answer rather than a failure.
    @Test
    void oneBadEntryRejectsTheWholeBatch() {
        assertThat(TextToolCalls.read("""
                [{"name": "read_file", "arguments": {}},
                 {"name": "not_declared", "arguments": {}}]""", DECLARED)).isEmpty();
    }

    // An unterminated block is a truncated reply. What was cut off may have been another call, so
    // reading the part that survived would under-report what the model asked for.
    @Test
    void anUnterminatedTagBlockIsRefusedEntirely() {
        assertThat(TextToolCalls.read(
                "<tool_call>{\"name\": \"read_file\", \"arguments\": {}}", DECLARED)).isEmpty();
    }

    @Test
    void argumentsMayBeAJsonStringOrAbsent() {
        assertThat(TextToolCalls.read(
                "{\"name\": \"read_file\", \"arguments\": \"{\\\"path\\\":\\\"A.java\\\"}\"}", DECLARED))
                .singleElement()
                .satisfies(c -> assertThat(c.argumentsJson()).isEqualTo("{\"path\":\"A.java\"}"));
        assertThat(TextToolCalls.read("{\"name\": \"sdd_probe_ack\"}", DECLARED))
                .singleElement()
                .satisfies(c -> assertThat(c.argumentsJson()).isEqualTo("{}"));
    }

    // ------------------------------------------------- key spellings seen from one live gateway

    /** {"function": "<name>", "parameters": {...}} — refused as prose until it was measured. */
    @Test
    void functionAndParametersAreReadLikeNameAndArguments() {
        List<ToolCall> calls = TextToolCalls.read(
                "{\"function\": \"read_file\", \"parameters\": {\"path\": \"A.java\"}}",
                Set.of("read_file"));

        assertThat(calls).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("read_file");
            assertThat(c.argumentsJson()).contains("A.java");
        });
    }

    /** The same reply mixed spellings across one sweep, so name+parameters must work too. */
    @Test
    void nameWithParametersIsRead() {
        assertThat(TextToolCalls.read(
                "{\"name\": \"read_file\", \"parameters\": {\"path\": \"B.java\"}}",
                Set.of("read_file")))
                .singleElement().satisfies(c -> assertThat(c.argumentsJson()).contains("B.java"));
    }

    /** OpenAI's own nested shape, arriving as content rather than as tool_calls. */
    @Test
    void aNestedFunctionObjectIsUnwrapped() {
        assertThat(TextToolCalls.read(
                "{\"function\": {\"name\": \"read_file\", \"arguments\": \"{}\"}}",
                Set.of("read_file")))
                .singleElement().satisfies(c -> assertThat(c.name()).isEqualTo("read_file"));
    }

    /**
     * The rule the aliases must NOT weaken. A live gateway named report_system_status against a
     * declared sdd_probe_ack; accepting it would run a tool nobody offered.
     */
    @Test
    void anUndeclaredNameIsStillRefusedUnderEverySpelling() {
        assertThat(TextToolCalls.read(
                "{\"function\": \"report_system_status\", \"parameters\": {\"status\": \"ok\"}}",
                Set.of("sdd_probe_ack"))).isEmpty();
        assertThat(TextToolCalls.read(
                "{\"name\": \"report_system_status\", \"parameters\": {}}",
                Set.of("sdd_probe_ack"))).isEmpty();
    }

    @Test
    void argumentsStillWinsWhenBothKeysArePresent() {
        assertThat(TextToolCalls.read(
                "{\"name\": \"read_file\", \"arguments\": {\"path\": \"real\"}, "
                        + "\"parameters\": {\"path\": \"decoy\"}}",
                Set.of("read_file")))
                .singleElement().satisfies(c -> assertThat(c.argumentsJson()).contains("real"));
    }
}
