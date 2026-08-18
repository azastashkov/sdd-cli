package sdd.core.llm;

/**
 * Separates a reasoning model's inline thinking from the answer it wraps.
 *
 * <p>Some providers have no request-side switch to turn reasoning off. GigaChat is the case that
 * forced this: its entire additional-field surface is
 * {@code flags, function_ranker, profanity_check, repetition_penalty, storage, update_interval} —
 * there is no {@code chat_template_kwargs.enable_thinking}, so the advice sdd prints when a card is
 * truncated is unactionable there. Reasoning instead arrives INSIDE the content, wrapped in
 * {@code <think>…</think>}, and the official {@code gpt2giga} proxy strips exactly these tags into a
 * separate field rather than asking the model to stop.
 *
 * <p>That breaks sdd in a quieter way than truncation: every consumer of a response parses it as
 * JSON — repo cards, the impact seeder, the plan drafter — so a reply that opens with a think block
 * is not truncated but "unparseable". Stripping here, at the transport boundary, fixes all of them
 * at once and costs nothing on a model that never emits the tags.
 *
 * <p>Kept deliberately literal: it removes a known wire artifact, and is not a general-purpose
 * content cleaner. Unbalanced tags are handled the way a stream would end — an unclosed
 * {@code <think>} swallows the rest, because content after it is reasoning that was cut off, not
 * answer.
 */
public final class ReasoningContent {
    private static final String OPEN = "<think>";
    private static final String CLOSE = "</think>";

    private ReasoningContent() {
    }

    /** The answer with every {@code <think>…</think>} block removed; null and blank pass through. */
    public static String strip(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        String lower = content.toLowerCase(java.util.Locale.ROOT);
        if (!lower.contains(OPEN)) {
            return content;
        }
        StringBuilder out = new StringBuilder(content.length());
        int i = 0;
        while (i < content.length()) {
            int open = lower.indexOf(OPEN, i);
            if (open < 0) {
                out.append(content, i, content.length());
                break;
            }
            out.append(content, i, open);
            int close = lower.indexOf(CLOSE, open + OPEN.length());
            if (close < 0) {
                // Unclosed: the remainder is reasoning the model never finished, not an answer.
                break;
            }
            i = close + CLOSE.length();
        }
        return out.toString().trim();
    }
}
