package sdd.core.http;

import com.fasterxml.jackson.databind.JsonNode;
import sdd.core.config.AtlassianSite;
import sdd.core.config.ConfigException;

import javax.net.ssl.SSLException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * The {@code sdd doctor} health check for one Jira/Confluence/Bitbucket site — the
 * {@code sdd.core.http} analogue of {@code sdd.core.llm.EndpointProbe}, mirroring its
 * catch-everything-return-a-result style so one unreachable site never aborts the rest of the
 * probe loop.
 *
 * <p>{@code ProbeResult} deliberately duplicates {@code EndpointProbe.ProbeResult}'s shape
 * ({@code boolean ok, String detail}) rather than reusing it: the two report the same shape
 * because they solve the same UI problem (one line in {@code sdd doctor}'s output), not because
 * Atlassian probing and LLM endpoint probing are the same concept. Reusing it would make
 * {@code sdd.core.http} depend on {@code sdd.core.llm} for a trivial two-field record — the same
 * cross-package-independence trade this class already makes for {@link RestClient.Sleeper} versus
 * {@code HttpChatModel.Sleeper}.
 *
 * <p>Single attempt, no retry: a probe is a point-in-time health check, not a request {@code sdd}
 * needs to succeed, so it uses {@code RestClient} with {@code maxAttempts=1} rather than the
 * multi-attempt default every real Jira/Confluence/Bitbucket call gets — {@code doctor} should
 * report "down" quickly, not spend several seconds backing off a site that just failed.
 */
public final class AtlassianProbe {
    public record ProbeResult(boolean ok, String detail) {}

    private AtlassianProbe() {}

    /**
     * @param labelFields the response fields to try, in order, for the "as &lt;label&gt;" text on
     *                     success — different Atlassian products name the login identifier
     *                     differently (Jira/Bitbucket Data Center use {@code name}, Confluence
     *                     Server uses {@code username}), so the caller supplies the candidates
     *                     rather than this class guessing product-specific shapes it has no other
     *                     reason to know about.
     * @param truststore   the configured {@code atlassian.tls.truststore}, or null — used only to
     *                     name it in the diagnostic on an SSL handshake failure.
     */
    public static ProbeResult probe(String siteName, AtlassianSite site, String path, HttpClient client,
            Path truststore, String... labelFields) {
        return run(siteName, site, client, truststore, rc -> firstText(rc.get(path), labelFields));
    }

    /**
     * Like {@link #probe}, but the "as &lt;label&gt;" text comes from a response HEADER instead of
     * a body field. Bitbucket Data Center's REST 1.0 API has no {@code /users/self} resource —
     * {@code /users/{userSlug}} needs a real slug sdd doctor does not have — but it returns the
     * authenticated username in the {@code X-AUSERNAME} header of any authenticated request, so
     * the one Bitbucket probe ({@code GET /rest/api/1.0/projects/{project}}) reads it from there
     * instead of making a second, unreliable call.
     */
    public static ProbeResult probeHeaderLabel(String siteName, AtlassianSite site, String path, HttpClient client,
            Path truststore, String headerName) {
        return run(siteName, site, client, truststore,
                rc -> rc.getWithHeaders(path).headers().firstValue(headerName).orElse("?"));
    }

    private static ProbeResult run(String siteName, AtlassianSite site, HttpClient client, Path truststore,
            Function<RestClient, String> label) {
        try {
            // Deferred from ConfigLoader: an unset token ${VAR} does not fail config loading (a
            // read-only command may never touch this site), so it is raised here instead — the
            // generic catch below turns it into a failed ProbeResult with the exact deferred
            // message, exactly like EndpointProbe does for a model endpoint's apiKeyError.
            if (site.tokenError() != null) {
                throw new ConfigException(site.tokenError());
            }
            RestClient rc = new RestClient(siteName, site.baseUrl(), site.token(), site.tokenVar(),
                    site.timeout(), 1, client, Thread::sleep);
            return new ProbeResult(true, "HTTP 200 as " + label.apply(rc));
        } catch (AtlassianException e) {
            if (e.getCause() instanceof SSLException ssl) {
                return new ProbeResult(false, HttpClients.tlsFailureMessage(hostOf(site.baseUrl()), truststore, ssl));
            }
            return new ProbeResult(false, e.getMessage());
        } catch (RuntimeException e) {
            return new ProbeResult(false, String.valueOf(e.getMessage()));
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode v = node.path(field);
            if (!v.isMissingNode() && !v.isNull() && !v.asText().isBlank()) {
                return v.asText();
            }
        }
        return "?";
    }

    private static String hostOf(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            return host != null ? host : baseUrl;
        } catch (IllegalArgumentException e) {
            return baseUrl;
        }
    }
}
