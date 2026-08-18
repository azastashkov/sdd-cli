package sdd.core.llm;

import sdd.core.config.ModelEndpoint;

import java.util.List;

/**
 * Asks a model tier to actually produce something, and reports what came back.
 *
 * <p>{@link EndpointProbe} calls {@code /models}: it proves the endpoint is reachable, the API key
 * is accepted and TLS negotiates — and says nothing about whether the model can emit a usable
 * answer. That gap is exactly where repo-card generation fails, and the failure a reader sees
 * ({@code finish_reason=length}) names a symptom without the numbers needed to act on it: how much
 * of the budget went to reasoning, whether any answer was produced at all, and what was actually
 * returned.
 *
 * <p>The probe deliberately mirrors a card request's SHAPE — a system prompt plus a short user
 * message, sent with the tier's configured {@code max_tokens} — so it reproduces the real failure
 * rather than a cheaper one that might succeed where cards do not.
 */
public final class CompletionProbe {

    /**
     * @param finishReason what the provider said ended the response — {@code "length"} is the
     *     truncation this probe exists to diagnose
     * @param reasoningChars characters found inside {@code <think>} blocks. Non-zero on a reasoning
     *     model with no request-side off switch, and the number that explains a truncated card:
     *     the budget was spent here rather than on an answer.
     * @param answerChars characters left after reasoning is stripped — zero alongside a large
     *     {@code reasoningChars} is the unambiguous signature of "thought until it ran out"
     * @param rawExcerpt the head of the raw content, before stripping, so a reader can SEE what the
     *     model returned rather than infer it
     */
    public record Result(boolean ok, String detail, String finishReason, int promptTokens,
                         int completionTokens, int maxTokensSent, int reasoningChars,
                         int answerChars, String rawExcerpt) {
    }

    /** Enough to recognise a think block or a refusal; short enough to paste into a ticket. */
    private static final int EXCERPT = 300;
    private static final String SYSTEM =
            "You summarize source repositories for an engineering knowledge base.";
    private static final String USER =
            "Reply with exactly this JSON and nothing else: {\"card_md\":\"ok\",\"card_line\":\"ok\"}";

    private CompletionProbe() {
    }

    public static Result probe(ModelEndpoint endpoint, ChatModel model) {
        int maxTokens = endpoint.maxTokens();
        try {
            ChatResponse response = model.complete(new ChatRequest(endpoint.model(),
                    List.of(ChatMessage.system(SYSTEM), ChatMessage.user(USER)),
                    List.of(), maxTokens, 0.15));
            // The transport already strips <think>; ask it again on the raw text so the two halves
            // can be reported separately. A model that emits none leaves reasoningChars at 0.
            String content = response.message().content();
            String answer = ReasoningContent.strip(content);
            int answerChars = answer == null ? 0 : answer.length();
            int rawChars = content == null ? 0 : content.length();
            boolean truncated = "length".equals(response.finishReason());
            String detail = truncated
                    ? "TRUNCATED: spent " + response.usage().completionTokens() + " of "
                            + maxTokens + " tokens and produced " + answerChars + " chars of answer"
                    : "produced " + answerChars + " chars, finish_reason="
                            + response.finishReason();
            return new Result(!truncated && answerChars > 0, detail, response.finishReason(),
                    response.usage().promptTokens(), response.usage().completionTokens(),
                    maxTokens, Math.max(0, rawChars - answerChars), answerChars,
                    excerpt(content));
        } catch (ModelException e) {
            return new Result(false, e.getMessage(), null, 0, 0, maxTokens, 0, 0, "");
        }
    }

    private static String excerpt(String content) {
        if (content == null) {
            return "(null content)";
        }
        String oneLine = content.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= EXCERPT ? oneLine : oneLine.substring(0, EXCERPT) + "…";
    }
}
