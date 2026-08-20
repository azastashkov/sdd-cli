package sdd.core.http;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
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
     * Parses a {@code Retry-After} header value, in either RFC 9110 form: delta-seconds, or an
     * HTTP-date.
     *
     * <p>The date form was previously unparsed, on the reasoning that no target was known to send
     * it. That reasoning does not survive contact with a corporate network: a WAF or reverse proxy
     * in front of Jira answers a 429 or 503 itself, and those commonly send the date form. The
     * consequence was mild — the caller fell back to the exponential curve — but it meant ignoring
     * the one number the server actually gave us.
     *
     * <p>A date in the past, or one absurdly far ahead, yields a delay of zero and
     * {@link #MAX_BACKOFF_MILLIS} respectively, so a clock skew between us and the server cannot
     * turn into either a busy loop or an effectively permanent hang.
     */
    public static Long retryAfterMillis(Optional<String> headerValue) {
        return retryAfterMillis(headerValue, Instant.now());
    }

    /** As above, against an explicit "now" so the date form is testable without wall-clock races. */
    public static Long retryAfterMillis(Optional<String> headerValue, Instant now) {
        return headerValue.map(v -> {
            String trimmed = v.trim();
            try {
                return Long.parseLong(trimmed) * 1000L;
            } catch (NumberFormatException notSeconds) {
                return httpDateMillis(trimmed, now);
            }
        }).orElse(null);
    }

    private static Long httpDateMillis(String value, Instant now) {
        try {
            long millis = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value))
                    .toEpochMilli() - now.toEpochMilli();
            return Math.clamp(millis, 0L, MAX_BACKOFF_MILLIS);
        } catch (DateTimeException e) {
            return null;
        }
    }
}
