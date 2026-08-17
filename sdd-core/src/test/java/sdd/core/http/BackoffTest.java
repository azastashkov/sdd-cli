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
    void retryAfterMillisFallsBackToNullOnHttpDateValue() {
        assertThat(Backoff.retryAfterMillis(Optional.of("Wed, 21 Oct 2026 07:28:00 GMT"))).isNull();
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
}
