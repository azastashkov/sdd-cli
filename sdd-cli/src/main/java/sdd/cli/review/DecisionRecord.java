package sdd.cli.review;

/** One repo's persisted decision. {@code reason} is {@code ""} when none was given — a
 *  rejection's reason is half the decision and must survive round-tripping through
 *  {@code decisions.json}, not just be printed once and lost. */
public record DecisionRecord(Decision decision, String reason) {
}
