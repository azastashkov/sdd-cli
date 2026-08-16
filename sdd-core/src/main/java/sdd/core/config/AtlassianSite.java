package sdd.core.config;

import java.time.Duration;

/**
 * One Jira, Confluence or Bitbucket endpoint under {@code atlassian:}. Each site is independently
 * optional — {@code atlassian.jira} can be present with no {@code atlassian.bitbucket}, and
 * {@code sdd index} must not be blocked by a Bitbucket token it will never use.
 *
 * <p>{@code baseUrl} is structural (like {@code models.<name>.base_url}) and fails config loading
 * loudly when missing or when its {@code ${VAR}} is unset — a command cannot work around a site it
 * does not know the address of.
 *
 * <p>{@code token}/{@code tokenVar}/{@code tokenError} copy the deferred-credential idiom from
 * {@link ModelEndpoint#apiKey()}/{@code apiKeyError} exactly, and for the same reason: a PAT is
 * only ever needed by something about to make a network call (a {@code RestClient}), never by
 * config loading itself, so an unset {@code ${VAR}} must not block a read-only command that never
 * touches Jira/Confluence/Bitbucket at all. {@code tokenVar} is the extra piece {@code ModelEndpoint}
 * does not need: PATs expire, so the 401 raised at point of use has to name the environment
 * variable to reissue (e.g. {@code $JIRA_API_KEY}), not just say "unauthorized". It is the env-var name
 * parsed out of a whole-value {@code ${VAR}} reference, and null when the token was a literal —
 * in which case the point-of-use message omits the variable name it does not have.
 */
public record AtlassianSite(String baseUrl, String token, String tokenVar, Duration timeout, String tokenError) {}
