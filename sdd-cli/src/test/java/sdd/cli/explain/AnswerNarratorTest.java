package sdd.cli.explain;

import org.junit.jupiter.api.Test;
import sdd.core.kb.EntityKind;
import sdd.core.kb.Provenance;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ModelException;
import sdd.core.llm.Usage;
import sdd.core.testing.ScriptedChatModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AnswerNarrator} is call 2: it must never diverge from {@link EvidenceRenderer}'s output,
 * since that identity is what guarantees an answer can only be grounded in facts the reader also
 * sees. The pinning test below asserts that identity against the real render path, not against a
 * second string built to match it.
 */
class AnswerNarratorTest {

    private static final Provenance PROVENANCE =
            new Provenance(6, "2026-08-01T00:00:00Z", "2026-08-14T00:00:00Z");

    private static ChatResponse response(String content, String finish) {
        return new ChatResponse(ChatMessage.assistant(content), finish, new Usage(1, 1));
    }

    private static Evidence sampleEvidence() {
        RetrievalRequest request = new RetrievalRequest(Intent.DESCRIBE,
                List.of(new EntityRef(EntityKind.REPO, "svc-orders", false)),
                List.of(), "What is svc-orders?", List.of(), false);
        Section section = Section.of("Modules: svc-orders", "module", List.of(new Fact(":app (SERVICE)")));
        return new Evidence(PROVENANCE, request, List.of(section), List.of());
    }

    // --- the auditability pin: prompt == printed, proven against the real render path ----------

    @Test
    void call2UserMessageIsExactlyTheRenderedEvidence() {
        Evidence evidence = sampleEvidence();
        String expected = EvidenceRenderer.render(evidence);
        ScriptedChatModel model = new ScriptedChatModel(List.of(response("svc-orders is a service module.", "stop")));

        AnswerNarrator.narrate(evidence, model, "m", 512);

        assertThat(model.requests()).singleElement().satisfies(req ->
                assertThat(req.messages()).anySatisfy(m -> assertThat(m.content()).isEqualTo(expected)));
    }

    @Test
    void systemPromptIsSentAsTheSystemMessage() {
        Evidence evidence = sampleEvidence();
        ScriptedChatModel model = new ScriptedChatModel(List.of(response("An answer.", "stop")));

        AnswerNarrator.narrate(evidence, model, "m", 512);

        assertThat(model.requests()).singleElement().satisfies(req -> {
            assertThat(req.messages()).anySatisfy(m ->
                    assertThat(m.content()).isEqualTo(AnswerNarrator.SYSTEM_PROMPT));
            assertThat(req.maxTokens()).isEqualTo(512);
        });
    }

    // --- happy path -------------------------------------------------------------------------

    @Test
    void happyPathReturnsProseAndIsAvailable() {
        Evidence evidence = sampleEvidence();
        ScriptedChatModel model = new ScriptedChatModel(List.of(response("svc-orders is a service module.", "stop")));

        Answer answer = AnswerNarrator.narrate(evidence, model, "m", 512);

        assertThat(answer.unavailable()).isFalse();
        assertThat(answer.prose()).isEqualTo("svc-orders is a service module.");
        assertThat(answer.notes()).isEmpty();
    }

    // --- unavailable paths, each with a distinct, stated reason ------------------------------

    @Test
    void modelExceptionProducesUnavailableAnswer() {
        ChatModel refusing = req -> {
            throw new ModelException("connection refused", 0);
        };

        Answer answer = AnswerNarrator.narrate(sampleEvidence(), refusing, "m", 512);

        assertThat(answer.unavailable()).isTrue();
        assertThat(answer.prose()).isEmpty();
        assertThat(answer.notes()).anySatisfy(n -> assertThat(n).contains("answer unavailable")
                .contains("connection refused"));
    }

    @Test
    void lengthFinishReasonProducesUnavailableAnswerNotAPartialOne() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(response("svc-orders is a serv", "length")));

        Answer answer = AnswerNarrator.narrate(sampleEvidence(), model, "m", 512);

        assertThat(answer.unavailable()).isTrue();
        assertThat(answer.prose()).isEmpty();
        assertThat(answer.notes()).anySatisfy(n -> assertThat(n).contains("answer unavailable")
                .contains("length"));
    }

    @Test
    void nullContentProducesUnavailableAnswer() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(response(null, "stop")));

        Answer answer = AnswerNarrator.narrate(sampleEvidence(), model, "m", 512);

        assertThat(answer.unavailable()).isTrue();
        assertThat(answer.notes()).anySatisfy(n -> assertThat(n).contains("answer unavailable")
                .contains("empty response"));
    }

    @Test
    void blankContentProducesUnavailableAnswer() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(response("   ", "stop")));

        Answer answer = AnswerNarrator.narrate(sampleEvidence(), model, "m", 512);

        assertThat(answer.unavailable()).isTrue();
        assertThat(answer.notes()).anySatisfy(n -> assertThat(n).contains("empty response"));
    }

    @Test
    void theThreeUnavailableReasonsAreDistinct() {
        ChatModel refusing = req -> {
            throw new ModelException("boom", 0);
        };
        String modelError = AnswerNarrator.narrate(sampleEvidence(), refusing, "m", 512).notes().get(0);
        String truncated = AnswerNarrator.narrate(sampleEvidence(),
                new ScriptedChatModel(List.of(response("x", "length"))), "m", 512).notes().get(0);
        String empty = AnswerNarrator.narrate(sampleEvidence(),
                new ScriptedChatModel(List.of(response(null, "stop"))), "m", 512).notes().get(0);

        assertThat(List.of(modelError, truncated, empty)).doesNotHaveDuplicates();
    }
}
