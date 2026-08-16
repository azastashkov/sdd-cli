package sdd.core.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Redactor} is the backstop half of Task 8's redaction design (belt and braces alongside
 * per-call-site care): given every secret value known at construction time, it must scrub each
 * occurrence from any string handed to it, no matter what shape that string arrives in — a plain
 * mention, a URL userinfo segment, an Authorization header line, or a value buried in an exception
 * message. This is the single highest-risk class in Task 8 (a leaked token is a security incident,
 * not a style bug), so every test here asserts the exact secret value is ABSENT from the output,
 * not merely that some redaction happened.
 */
class RedactorTest {
    private static final String SECRET = "sk-topsecret-abcdef123456";

    @Test
    void scrubsAPlainOccurrenceOfAKnownSecret() {
        Redactor redactor = Redactor.of(Set.of(SECRET));

        String out = redactor.scrub("Authorization: Bearer " + SECRET);

        assertThat(out).doesNotContain(SECRET);
    }

    @Test
    void scrubsASecretRepeatedMultipleTimesInOneString() {
        Redactor redactor = Redactor.of(Set.of(SECRET));

        String out = redactor.scrub(SECRET + " appears twice: " + SECRET);

        assertThat(out).doesNotContain(SECRET);
    }

    @Test
    void scrubsTheLongerOfTwoSecretsFirstSoOneIsNotLeftPartiallyRedacted() {
        // A shorter secret that happens to be a substring of a longer one must not cause the
        // longer one to be redacted down to a residual fragment that still contains the shorter
        // secret's characters in a recognizable, un-redacted run.
        String shortSecret = "sk-abc";
        String longSecret = "sk-abc-and-then-some-more-characters";
        Redactor redactor = Redactor.of(Set.of(shortSecret, longSecret));

        String out = redactor.scrub("token=" + longSecret);

        assertThat(out).doesNotContain(longSecret).doesNotContain(shortSecret);
    }

    @Test
    void elidesUserinfoInAUrlEvenWithNoKnownSecretConfigured() {
        Redactor redactor = Redactor.of(Set.of());

        String out = redactor.scrub("remote: https://x-token-auth:" + SECRET + "@bb.corp.local/scm/proj/repo.git");

        assertThat(out).doesNotContain(SECRET).contains("https://<redacted>@bb.corp.local");
    }

    @Test
    void elidesAnAuthorizationHeaderLineEvenWithNoKnownSecretConfigured() {
        Redactor redactor = Redactor.of(Set.of());

        String out = redactor.scrub("Authorization: Bearer " + SECRET);

        assertThat(out).doesNotContain(SECRET).contains("Authorization: <redacted>");
    }

    @Test
    void elidesCredentialLookingQueryParameterValues() {
        Redactor redactor = Redactor.of(Set.of());

        String out = redactor.scrub("GET /rest/pat/latest/tokens?token=" + SECRET + "&other=fine");

        assertThat(out).doesNotContain(SECRET).contains("other=fine");
    }

    @Test
    void leavesOrdinaryTextAndInternalHostnamesUntouched() {
        Redactor redactor = Redactor.of(Set.of(SECRET));

        String out = redactor.scrub("GET /rest/api/2/issue/PROJ-123 on jira.corp.local status=200");

        assertThat(out).isEqualTo("GET /rest/api/2/issue/PROJ-123 on jira.corp.local status=200");
    }

    @Test
    void ignoresNullAndBlankSecretValuesRatherThanRedactingEveryCharacter() {
        Redactor redactor = Redactor.of(java.util.Arrays.asList(null, "", "   ", SECRET));

        String out = redactor.scrub("hello world " + SECRET);

        assertThat(out).doesNotContain(SECRET).contains("hello world");
    }

    @Test
    void scrubOfNullReturnsNull() {
        Redactor redactor = Redactor.of(Set.of(SECRET));

        assertThat(redactor.scrub(null)).isNull();
    }

    @Test
    void ofAcceptsAnyCollectionShapeNotJustASet() {
        Redactor redactor = Redactor.of(List.of(SECRET));

        assertThat(redactor.scrub(SECRET)).doesNotContain(SECRET);
    }
}
