package sdd.core.contract;

/**
 * The single definition of the plan.md anti-forgery pattern: a literal {@code ```} inside
 * generated or human-authored text is neutralized to {@code '''} so it can never close a fence
 * early — which would let the following text escape into (or forge) whatever section comes next.
 * Idempotent, because callers compose: {@code PlanDrafter} sanitizes declared lines at parse time
 * and {@code PlanMdRenderer} neutralizes again at render time, so a second pass over already
 * -neutralized text must leave it unchanged rather than mangling it further.
 */
public final class Markdown {

    private Markdown() {
    }

    public static String neutralizeFences(String text) {
        return text.replace("```", "'''");
    }
}
