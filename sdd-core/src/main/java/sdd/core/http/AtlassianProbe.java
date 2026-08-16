package sdd.core.http;

import com.fasterxml.jackson.databind.JsonNode;
import sdd.core.config.AtlassianSite;
import sdd.core.config.ConfigException;

import javax.net.ssl.SSLException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;

/**
 * The {@code sdd doctor} health check for one Jira/Confluence/Bitbucket site — the
 * {@code sdd.core.http} analogue of {@code sdd.core.llm.EndpointProbe}, mirroring its
 * catch-everything-return-a-result style so one unreachable site never aborts the rest of the
 * probe loop.
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
            JsonNode resp = rc.get(path);
            return new ProbeResult(true, "HTTP 200 as " + firstText(resp, labelFields));
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
