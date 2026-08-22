package sdd.plan.confluence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What two readings of the same image do not agree on.
 *
 * <p>Measured 2026-08-22, and this class exists entirely because of it. A state diagram sent to the
 * same model twice came back with the same seven states and the same transitions both times. A
 * dense mapping form did not: one read called it a loan application for "ПАО Газпромбанк" with
 * product "BARS", the other a securities trade for "ПАО «Газпром нефть»" with type "Barsa", and
 * invented an exchange the first never mentioned. Structure survives; dense values do not.
 *
 * <p>So a description is written twice and the difference is reported rather than resolved. No
 * third model call decides which read was right — that would be a referee whose own answer nobody
 * can check, on exactly the material already shown to be unreliable. Nobody is going to open the
 * diagram and verify it either, which is the whole reason the flag has to be automatic.
 *
 * <p>The comparison is over SALIENT TOKENS, not prose. Every failure actually observed was one:
 * a proper noun, an identifier, or a number. Ordinary words differ between two fluent paragraphs
 * describing the same thing and mean nothing by it, so including them would flag every image and
 * the flag would stop being read.
 */
public final class Disagreement {

    /** Capitalised words (any script), ALL-CAPS or underscored identifiers, and numbers. */
    private static final Pattern SALIENT = Pattern.compile(
            "\\p{Lu}[\\p{L}\\p{Nd}]{2,}(?:[-_][\\p{L}\\p{Nd}]+)*"    // Газпромбанк, Barsa, NO_STATE
                    + "|[\\p{Lu}\\p{Nd}]{2,}(?:_[\\p{Lu}\\p{Nd}]+)+"  // RFQ_REQUESTED
                    + "|\\p{Nd}[\\p{Nd}.,]*\\p{Nd}|\\p{Nd}");         // 2289, 10 000, 15,00

    /**
     * Words that are capitalised only because a sentence started, or that name the medium rather
     * than its content. Left in, they flag two paragraphs that agree completely — measured: the two
     * agreeing reads of the state diagram both opened with "На изображении" and "Схема".
     */
    private static final Set<String> IGNORED = Set.of(
            "на", "в", "и", "не", "the", "a", "an", "this", "it", "there", "here",
            "изображении", "изображение", "изображена", "изображено", "картинке", "картинка",
            "схема", "схеме", "диаграмма", "диаграмме", "рисунке", "форма", "форме",
            "image", "picture", "diagram", "figure", "screenshot", "form");

    private static final int MAX_REPORTED = 12;

    private Disagreement() {
    }

    /**
     * Tokens present in exactly one of the two readings, first-seen order, capped.
     *
     * <p>Deliberately symmetric and deliberately not a similarity score: the caller needs to print
     * WHICH words were unstable, because that is what tells a reviewer where to look. A number
     * would not.
     */
    public static List<String> between(String first, String second) {
        Set<String> a = salient(first);
        Set<String> b = salient(second);
        List<String> only = new ArrayList<>();
        for (String token : a) {
            if (!b.contains(token)) {
                only.add(token);
            }
        }
        for (String token : b) {
            if (!a.contains(token) && !only.contains(token)) {
                only.add(token);
            }
        }
        return only.size() <= MAX_REPORTED ? List.copyOf(only)
                : List.copyOf(only.subList(0, MAX_REPORTED));
    }

    /** One line for a spec, or empty when the two readings agreed. Never multi-line: a spec bullet
     *  is line-oriented and an embedded newline aborts the whole run at the re-parse self-check. */
    public static String line(String first, String second) {
        List<String> tokens = between(first, second);
        return tokens.isEmpty() ? ""
                : "! the two readings disagreed on: " + String.join(", ", tokens);
    }

    private static Set<String> salient(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null) {
            return tokens;
        }
        Matcher matcher = SALIENT.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            if (!IGNORED.contains(token.toLowerCase(Locale.ROOT))) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}
