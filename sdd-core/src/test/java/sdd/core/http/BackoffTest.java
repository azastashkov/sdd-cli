package sdd.core.http;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class BackoffTest {
    @Test
    void delayMillisPrefersRetryAfterOverExponential() {
        assertThat(Backoff.delayMillis(1, 2_000L)).isEqualTo(2_000L);
    }

    @Test
    void delayMillisCapsRetryAfterAtMaxBackoff() {
        assertThat(Backoff.delayMillis(1, 86_400_000L)).isEqualTo(60_000L);
    }

    @Test
    void delayMillisWithoutRetryAfterUsesExponentialBackoffWithJitterBoundedByMax() {
        // attempt 1: base * 2^0 = 250, plus jitter in [0, 250) -> [250, 500)
        long delay = Backoff.delayMillis(1, null);
        assertThat(delay).isBetween(250L, 500L);

        // attempt 5: base * 2^4 = 4000, plus jitter in [0, 250) -> [4000, 4250), well under the cap
        long delay5 = Backoff.delayMillis(5, null);
        assertThat(delay5).isBetween(4_000L, 4_250L);
    }

    @Test
    void delayMillisWithoutRetryAfterNeverExceedsMaxBackoff() {
        // attempt 10: base * 2^9 = 128_000, would blow past the cap without the Math.min guard
        assertThat(Backoff.delayMillis(10, null)).isLessThanOrEqualTo(60_000L);
    }

    @Test
    void retryAfterMillisParsesDeltaSeconds() {
        assertThat(Backoff.retryAfterMillis(Optional.of("1"))).isEqualTo(1_000L);
    }

    @Test
    void retryAfterMillisNowHonoursTheHttpDateFormItUsedToDiscard() {
        // Was pinned to null, on the recorded reasoning that none of our targets send the date
        // form. That held for model routers and does not survive a corporate network, where a WAF
        // or reverse proxy in front of Jira answers the 429 itself. Behaviour deliberately changed;
        // the boundary cases live alongside the other Retry-After tests at the end of this class.
        assertThat(Backoff.retryAfterMillis(Optional.of("Wed, 21 Oct 2026 07:28:00 GMT"),
                java.time.Instant.parse("2026-10-21T07:27:45Z"))).isEqualTo(15_000L);
    }

    @Test
    void retryAfterMillisIsNullWhenHeaderAbsent() {
        assertThat(Backoff.retryAfterMillis(Optional.empty())).isNull();
    }

    @Test
    void constantsMatchHttpChatModelsPreExtractionValues() {
        assertThat(Backoff.DEFAULT_MAX_ATTEMPTS).isEqualTo(6);
        assertThat(Backoff.BASE_BACKOFF_MILLIS).isEqualTo(250L);
        assertThat(Backoff.MAX_BACKOFF_MILLIS).isEqualTo(60_000L);
    }

    @Test
    void retryAfterAcceptsTheHttpDateFormNotJustDeltaSeconds() {
        // Valid per RFC 9110 and previously unparsed, on the reasoning that no target sends it.
        // A WAF or reverse proxy in front of Jira answers a 429 or 503 itself and commonly does --
        // so we were discarding the one number the server actually gave us.
        java.time.Instant now = java.time.Instant.parse("2026-10-21T07:28:00Z");

        assertThat(Backoff.retryAfterMillis(
                java.util.Optional.of("Wed, 21 Oct 2026 07:28:30 GMT"), now)).isEqualTo(30_000L);
    }

    @Test
    void aRetryAfterDateInThePastIsZeroNotNegative() {
        // Clock skew between us and the server must not become a busy loop.
        java.time.Instant now = java.time.Instant.parse("2026-10-21T07:30:00Z");

        assertThat(Backoff.retryAfterMillis(
                java.util.Optional.of("Wed, 21 Oct 2026 07:28:00 GMT"), now)).isZero();
    }

    @Test
    void aRetryAfterDateFarInTheFutureIsClampedToTheCeiling() {
        java.time.Instant now = java.time.Instant.parse("2026-10-21T07:28:00Z");

        assertThat(Backoff.retryAfterMillis(
                java.util.Optional.of("Thu, 22 Oct 2026 07:28:00 GMT"), now))
                .isEqualTo(Backoff.MAX_BACKOFF_MILLIS);
    }

    @Test
    void anUnparseableRetryAfterStillFallsBackToTheCurve() {
        assertThat(Backoff.retryAfterMillis(java.util.Optional.of("soon"))).isNull();
    }
}
