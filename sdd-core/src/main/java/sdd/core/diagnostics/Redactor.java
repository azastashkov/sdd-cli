package sdd.core.diagnostics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Task 8's redaction backstop: given every secret value known at the point a
 * {@link DiagnosticWriter} is opened (every resolved Atlassian token, the truststore password),
 * scrub every occurrence of every one of them from any string before it is written — "belt and
 * braces" alongside the per-call-site care {@code RestClient}/{@code RemoteGit}/{@code
 * BitbucketDecisions} already take never to pass a raw credential into a diagnostic call in the
 * first place. This class is what makes that care survive a mistake at one of those call sites: a
 * caller that DOES pass a raw token through still cannot make it reach the file, because every
 * write path funnels through here first.
 *
 * <p>Three independent redaction rules, run in order:
 * <ol>
 *   <li><b>Known secrets</b> — exact substring match against every value collected at
 *       construction, longest first (see {@link #of}'s javadoc for why order matters).</li>
 *   <li><b>URL userinfo</b> — {@code scheme://user:pass@host} becomes
 *       {@code scheme://<redacted>@host}, unconditionally, whether or not the credential inside
 *       happens to be one of the known secrets. This is the exact shape Task 6 found leaking (a
 *       git-config rewrite embedding a token that {@code git remote get-url} resolved back into
 *       printed output) — the worked example this class exists to prevent from ever reaching a
 *       diagnostic file, not just from reaching a terminal.</li>
 *   <li><b>Authorization headers and credential-looking query parameters</b> — elided by pattern,
 *       not by value, so a token this class was never told about (a bug in some future call site,
 *       or a third-party library's own error message) still cannot leak through either shape.</li>
 * </ol>
 *
 * <p>Deliberately NOT applied to hostnames, project keys, or issue keys — those are necessary for
 * diagnosis on a closed network and are expected to appear verbatim; see {@code DiagnosticHeader}'s
 * javadoc for where that is disclosed to the reader in the file itself.
 */
public final class Redactor {
    private static final String REDACTED = "<redacted>";

    // scheme://user:pass@host -> scheme://<redacted>@host. Applied to any userinfo segment, known
    // secret or not — see this class's javadoc, rule 2.
    private static final Pattern USERINFO = Pattern.compile("(https?://)[^/\\s@]+@");

    // "Authorization: <anything to end of line-ish token>" -> "Authorization: <redacted>". Case
    // insensitive: HTTP header names are conventionally capitalized but not required to be.
    private static final Pattern AUTH_HEADER = Pattern.compile("(?i)(authorization:\\s*)\\S.*");

    // Query-parameter values whose KEY name looks credential-shaped. Covers the PAT-creation
    // endpoints this repo actually has (?token=, access_token=) plus the generic names a future
    // endpoint might use, without trying to enumerate every product's exact field name.
    private static final Pattern CRED_QUERY_PARAM = Pattern.compile(
            "(?i)([?&](?:token|access_token|pat|password|secret|api_key|apikey)=)[^&\\s]+");

    private final List<String> secrets;

    private Redactor(List<String> secrets) {
        this.secrets = secrets;
    }

    /**
     * Builds a redactor from every secret value known at the point a {@link DiagnosticWriter} is
     * opened. Blank/null entries are dropped rather than redacted — an empty-string "secret" would
     * otherwise match everywhere and reduce every write to nothing, which is a bug this
     * constructor forecloses rather than something a caller could accidentally trigger by passing
     * an unresolved (null) token through. Sorted longest-first so that when one secret's characters
     * happen to be a prefix of another's (an unlikely but not impossible token-rotation artifact),
     * the longer one is redacted whole rather than leaving the shorter one's characters exposed in
     * what remains.
     */
    public static Redactor of(Collection<String> secretValues) {
        List<String> nonBlank = new ArrayList<>();
        for (String s : secretValues) {
            if (s != null && !s.isBlank()) {
                nonBlank.add(s);
            }
        }
        nonBlank.sort(Comparator.comparingInt(String::length).reversed());
        return new Redactor(List.copyOf(nonBlank));
    }

    /** Scrubs {@code text}; null in, null out (callers pass through nullable fields freely without
     *  a separate null check at every call site). */
    public String scrub(String text) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (String secret : secrets) {
            if (result.contains(secret)) {
                result = result.replace(secret, REDACTED);
            }
        }
        result = USERINFO.matcher(result).replaceAll("$1" + REDACTED + "@");
        result = AUTH_HEADER.matcher(result).replaceAll("$1" + REDACTED);
        result = CRED_QUERY_PARAM.matcher(result).replaceAll("$1" + REDACTED);
        return result;
    }
}
