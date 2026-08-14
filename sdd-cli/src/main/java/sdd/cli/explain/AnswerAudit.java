package sdd.cli.explain;

import org.jdbi.v3.core.Jdbi;
import sdd.core.kb.KbEntities;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A cheap, deterministic backstop that runs after {@link AnswerNarrator}: every {@code repo.name}
 * and {@code kafka_topic.name} in the knowledge base (both {@code UNIQUE}, and — in a real
 * estate — distinctive identifiers) is checked for a whole-word appearance in the answer text
 * with no matching whole-word appearance in the evidence text the answer was supposed to be
 * grounded in. A name that clears that bar is a plausible hallucination: the model named
 * something it was never shown.
 *
 * <p><b>What this is not: a hallucination smoke alarm, not a correctness check.</b> It can only
 * ever flag a bare NAME the model introduced from nowhere. It has no way to notice an invented
 * RELATIONSHIP between two names that both legitimately appear in the evidence — e.g. an answer
 * claiming "svc-billing calls svc-notify" when the evidence never asserts that edge, but both
 * repo names are individually present elsewhere in it (each mentioned in its own, unrelated
 * section). Catching that would require actually understanding the sentence, which this class
 * does not attempt; a false "no notes" here is not proof the answer is correct.
 *
 * <p><b>Known false-positive sources, accepted rather than solved</b> — an NLP-grade name
 * recognizer is out of scope for a cheap backstop, so these are documented rather than papered
 * over:
 * <ul>
 *   <li>A repo or topic named after an ordinary English word (this codebase's own test fixture
 *       has one: {@code platform}) is flagged whenever the narrator uses that word at all, in any
 *       sense, whether or not it is referring to the repo. Whole-word matching (not plain
 *       substring) at least keeps a longer word that merely contains it, e.g. "platformer", from
 *       tripping the check — but it cannot tell "the platform library" from "our platform for
 *       growth".</li>
 *   <li>A name that is itself a hyphen/dot-delimited component of a different, longer name (e.g.
 *       a repo {@code orders} next to a repo {@code svc-orders}) can be reported even though only
 *       the longer name was meant, because {@code -} and {@code .} are not word characters in a
 *       {@code \b} boundary and so the shorter name still reads as a whole word inside the
 *       longer one.</li>
 * </ul>
 * A note from this class is therefore worth a second look, not proof of a hallucination.
 */
public final class AnswerAudit {

    private AnswerAudit() {
    }

    public static List<String> check(String answer, String evidence, Jdbi jdbi) {
        List<String> notes = new ArrayList<>();
        for (String repo : KbEntities.repoNames(jdbi)) {
            noteIfHallucinated(notes, "repo", repo, answer, evidence);
        }
        for (String topic : KbEntities.topicNames(jdbi)) {
            noteIfHallucinated(notes, "topic", topic, answer, evidence);
        }
        return notes;
    }

    private static void noteIfHallucinated(List<String> notes, String kind, String name,
                                           String answer, String evidence) {
        if (mentionsWholeWord(answer, name) && !mentionsWholeWord(evidence, name)) {
            notes.add("answer names " + kind + " '" + name + "', which does not appear in the "
                    + "evidence above -- possible hallucination");
        }
    }

    private static boolean mentionsWholeWord(String text, String name) {
        return Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(text).find();
    }
}
