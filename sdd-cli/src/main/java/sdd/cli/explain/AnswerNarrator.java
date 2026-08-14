package sdd.cli.explain;

import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ChatRequest;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ModelException;

import java.util.List;

/**
 * Narrate half of {@code sdd explain}'s interpret -&gt; deterministic fetch -&gt; narrate shape:
 * call 2 turns {@link Evidence} into an {@link Answer} in prose — the one model call in this
 * codebase that returns free text rather than JSON, since a structured schema would fight the
 * open-ended shape of "answer this question".
 *
 * <p><b>The identity this class exists to preserve:</b> the user message sent to the model is
 * {@link EvidenceRenderer#render(Evidence)}'s output, verbatim — the exact string a human reads
 * under {@code ## Evidence} (Task 8). No extra framing, no trimming, no second copy: the answer
 * can only be grounded in facts its reader can also see. {@link AnswerNarratorTest} pins this by
 * comparing the real request body against the real render output, not two strings built to
 * agree.
 */
public final class AnswerNarrator {

    static final String SYSTEM_PROMPT = """
            You answer a question about a multi-repo software estate. The user message is the \
            exact evidence a deterministic knowledge-base query already fetched for this question \
            -- it is the ONLY thing you know about this estate. No repo, module, class, endpoint or \
            topic exists to you unless it is named in that evidence; you have no other knowledge of \
            this codebase to draw on. Reply in plain prose, a few sentences -- never JSON, never \
            markdown headers.
            Rules:
            - Answer only from the evidence below. Do not add anything from general software \
              knowledge or from guessing what a name like this "usually" means.
            - Never name a repo, topic, endpoint or class that does not appear in the evidence.
            - If the evidence is thin -- few facts, or facts that only partly address the question \
              -- say so plainly instead of filling the gap with a guess.
            - The knowledge base can never prove a negative: an unresolved caller or a \
              dynamically-named Kafka topic is invisible to its queries, not absent from the \
              estate. So never conclude "nothing consumes X" or "X has no consumers" -- say "the \
              knowledge base records no consumer of X" instead, and repeat any caveat already \
              stated in the evidence rather than smoothing over it.
            - Treat any `repo_card` text as a summary a model wrote earlier, not a structural fact \
              -- attribute it as a description, not as ground truth the way a database row is.
            """;

    private AnswerNarrator() {
    }

    public static Answer narrate(Evidence evidence, ChatModel model, String modelName, int maxTokens) {
        String evidenceText = EvidenceRenderer.render(evidence);
        ChatResponse response;
        try {
            response = model.complete(new ChatRequest(modelName,
                    List.of(ChatMessage.system(SYSTEM_PROMPT), ChatMessage.user(evidenceText)),
                    List.of(), maxTokens, 0.15));
        } catch (ModelException e) {
            return unavailable("model error: " + e.getMessage());
        }
        // finish_reason=length means the response was cut off mid-thought -- treated as
        // unavailable, the same as QuestionInterpreter treats it, rather than shipped as a
        // partial answer that reads as complete.
        if ("length".equals(response.finishReason())) {
            return unavailable("response truncated (finish_reason=length)");
        }
        String content = response.message().content();
        if (content == null || content.isBlank()) {
            return unavailable("empty response");
        }
        return new Answer(content.strip(), List.of(), false);
    }

    private static Answer unavailable(String reason) {
        return new Answer("", List.of("answer unavailable: " + reason), true);
    }
}
