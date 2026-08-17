package sdd.core.diagnostics;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate review I3: common JGit/JDK exceptions carry a {@code null} {@link Throwable#getMessage()},
 * and printing the literal string "null" to a human's terminal ({@code "  warn: bitbucket:
 * trading-api: null"}) is worse than useless. {@link Failures#message} is the one place this is
 * fixed for every best-effort catch site that used to concatenate {@code e.getMessage()} directly.
 */
class FailuresTest {
    @Test
    void aNullMessageDegradesToTheExceptionsSimpleClassName() {
        Throwable noMessage = new IOException();

        assertThat(Failures.message(noMessage)).isEqualTo("IOException");
    }

    @Test
    void aPresentMessageIsReturnedUnchanged() {
        Throwable withMessage = new IllegalStateException("token expired");

        assertThat(Failures.message(withMessage)).isEqualTo("token expired");
    }
}
