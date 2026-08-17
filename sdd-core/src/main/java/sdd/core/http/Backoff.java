package sdd.core.http;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The retry/backoff math shared by every HTTP-speaking client in this repo (currently
 * {@code sdd.core.llm.HttpChatModel} and {@code sdd.core.http.RestClient}).
 *
 * <p>Extracted from {@code HttpChatModel}, which had this inlined first (Ruling R3): the two
 * static methods here are a byte-for-byte lift of {@code HttpChatModel.backoff} and
 * {@code HttpChatModel.retryAfterMillis} — same constants, same jitter shape, same
 * {@code Retry-After}-seconds-with-exponential-fallback handling. {@code HttpChatModel} now calls
 * these instead of carrying its own copy; {@code HttpChatModelTest} was not touched to make that
 * true, which is the point — a refactor that has to edit its own characterization tests proves
 * nothing about behaviour preservation.
 *
 * <p>What stays OUT of this class, deliberately: the attempt cap. {@code attempt >= maxAttempts}
 * depends on a per-instance {@code maxAttempts} (see {@code HttpChatModelTest.attemptCapBoundsRetries},
 * which overrides the 6-attempt default to 2), so the cap decision belongs to the caller's retry
 * loop, not to this pure-math helper. {@link #DEFAULT_MAX_ATTEMPTS} is exposed only as the shared
 * default both callers start from.
 */
public final class Backoff {
    public static final int DEFAULT_MAX_ATTEMPTS = 6;
    public static final long BASE_BACKOFF_MILLIS = 250;
    public static final long MAX_BACKOFF_MILLIS = 60_000;

    private Backoff() {}

    /**
     * How long to sleep before the next attempt (1-based {@code attempt} is the attempt that just
     * failed). When {@code retryAfterMillis} is present (a 429 carrying a {@code Retry-After}
     * delta-seconds value) it wins over the exponential curve, still capped at
     * {@link #MAX_BACKOFF_MILLIS} — a misbehaving server asking for a day-long wait must not stall
     * the caller for a day. Otherwise: base * 2^(attempt-1), plus up to one base-interval of jitter
     * so a fleet of clients retrying the same outage does not do so in lockstep, capped the same way.
     */
    public static long delayMillis(int attempt, Long retryAfterMillis) {
        return retryAfterMillis != null
                ? Math.min(retryAfterMillis, MAX_BACKOFF_MILLIS)
                : Math.min(MAX_BACKOFF_MILLIS,
                        BASE_BACKOFF_MILLIS * (1L << (attempt - 1))
                                + ThreadLocalRandom.current().nextLong(BASE_BACKOFF_MILLIS));
    }

    /**
     * Parses a {@code Retry-After} header value expressed as delta-seconds. Null when the header is
     * absent, or when it is present but not a delta-seconds integer — the HTTP-date form
     * ({@code Retry-After: Wed, 21 Oct 2026 07:28:00 GMT}) is valid per RFC 9110 but is not parsed
     * here; the caller falls back to exponential backoff instead of teaching this pure-math class a
     * date parser for a form none of our targets (model routers, and now Jira/Confluence/Bitbucket)
     * are known to send.
     */
    public static Long retryAfterMillis(Optional<String> headerValue) {
        return headerValue.map(v -> {
            try {
                return Long.parseLong(v.trim()) * 1000L;
            } catch (NumberFormatException e) {
                return null;
            }
        }).orElse(null);
    }
}
