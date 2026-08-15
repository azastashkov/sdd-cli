package sdd.cli.explain;

import java.util.regex.Pattern;

/**
 * Whether a piece of free text names a KB identifier, shared by the two places in {@code sdd
 * explain} that ask it of the same identifiers: {@link QuestionInterpreter#fallback} (does the
 * question name this repo or topic?) and {@link AnswerAudit} (does the answer name one the
 * evidence never showed?). One definition, because the two must agree — a name the fallback
 * counts as mentioned and the audit does not, or the reverse, would make the audit's notes
 * inconsistent with the entities the same estate's names resolved.
 */
final class Mentions {
    private Mentions() {
    }

    /**
     * Whole-word, not substring: a longer word that merely contains the name (e.g. "platformer"
     * for a repo {@code platform}) must not count as naming it. {@link Pattern#quote} because KB
     * names carry regex metacharacters — {@code .} in a topic name is the common one.
     *
     * <p>{@code \b} boundaries are the accepted limit of this check: {@code -} and {@code .} are
     * not word characters, so a repo {@code orders} still reads as a whole word inside {@code
     * svc-orders}. {@link AnswerAudit}'s Javadoc records that false-positive source in full.
     */
    static boolean wholeWord(String text, String name) {
        return Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(text).find();
    }
}
