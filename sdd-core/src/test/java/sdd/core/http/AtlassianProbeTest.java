package sdd.core.http;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import sdd.core.config.AtlassianSite;

import javax.net.ssl.SSLHandshakeException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class AtlassianProbeTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    private AtlassianSite site(String token, String tokenVar, String tokenError) {
        return new AtlassianSite(wm.baseUrl(), token, tokenVar, Duration.ofSeconds(5), tokenError);
    }

    @Test
    void reportsHttp200AsTheResolvedLabelField() {
        wm.stubFor(get("/rest/api/2/myself").willReturn(okJson("{\"name\":\"jsmith\"}")));

        AtlassianProbe.ProbeResult r = AtlassianProbe.probe("Jira", site("sk-x", "JIRA_PAT", null),
                "/rest/api/2/myself", HttpClient.newHttpClient(), null, "name", "displayName");

        assertThat(r.ok()).isTrue();
        assertThat(r.detail()).isEqualTo("HTTP 200 as jsmith");
        wm.verify(getRequestedFor(urlEqualTo("/rest/api/2/myself"))
                .withHeader("Authorization", equalTo("Bearer sk-x")));
    }

    @Test
    void probeHeaderLabelReportsHttp200AsTheHeaderValue() {
        wm.stubFor(get("/rest/api/1.0/projects/TRADING").willReturn(okJson("{\"key\":\"TRADING\"}")
                .withHeader("X-AUSERNAME", "jsmith")));

        AtlassianProbe.ProbeResult r = AtlassianProbe.probeHeaderLabel("Bitbucket",
                site("sk-x", "BITBUCKET_PAT", null), "/rest/api/1.0/projects/TRADING",
                HttpClient.newHttpClient(), null, "X-AUSERNAME");

        assertThat(r.ok()).isTrue();
        assertThat(r.detail()).isEqualTo("HTTP 200 as jsmith");
    }

    @Test
    void probeHeaderLabelFallsBackToAQuestionMarkWhenTheHeaderIsAbsent() {
        wm.stubFor(get("/rest/api/1.0/projects/TRADING").willReturn(okJson("{\"key\":\"TRADING\"}")));

        AtlassianProbe.ProbeResult r = AtlassianProbe.probeHeaderLabel("Bitbucket",
                site("sk-x", "BITBUCKET_PAT", null), "/rest/api/1.0/projects/TRADING",
                HttpClient.newHttpClient(), null, "X-AUSERNAME");

        assertThat(r.detail()).isEqualTo("HTTP 200 as ?");
    }

    @Test
    void fallsBackToTheNextLabelFieldWhenTheFirstIsAbsent() {
        wm.stubFor(get("/rest/api/user/current").willReturn(okJson("{\"displayName\":\"Jane Smith\"}")));

        AtlassianProbe.ProbeResult r = AtlassianProbe.probe("Confluence", site("sk-x", "CONFLUENCE_PAT", null),
                "/rest/api/user/current", HttpClient.newHttpClient(), null, "username", "displayName");

        assertThat(r.detail()).isEqualTo("HTTP 200 as Jane Smith");
    }

    @Test
    void aDeferredTokenErrorSurfacesWithoutMakingACall() {
        AtlassianProbe.ProbeResult r = AtlassianProbe.probe("Jira",
                site(null, "JIRA_PAT", "atlassian.jira.token: environment variable JIRA_PAT is not set"),
                "/rest/api/2/myself", HttpClient.newHttpClient(), null, "name");

        assertThat(r.ok()).isFalse();
        assertThat(r.detail()).isEqualTo("atlassian.jira.token: environment variable JIRA_PAT is not set");
        wm.verify(0, getRequestedFor(urlEqualTo("/rest/api/2/myself")));
    }

    @Test
    void a401SurfacesTheTokenRejectionMessage() {
        wm.stubFor(get("/rest/api/2/myself").willReturn(unauthorized()));

        AtlassianProbe.ProbeResult r = AtlassianProbe.probe("Jira", site("sk-x", "JIRA_PAT", null),
                "/rest/api/2/myself", HttpClient.newHttpClient(), null, "name");

        assertThat(r.ok()).isFalse();
        assertThat(r.detail()).isEqualTo("Jira rejected the token in $JIRA_PAT (HTTP 401) — reissue it");
    }

    @Test
    void anSslHandshakeFailureSurfacesTheHostAndTruststoreDiagnostic() {
        HttpClient sslRefusing = new HttpClient() {
            @Override public java.util.Optional<Duration> connectTimeout() { return java.util.Optional.empty(); }
            @Override public Redirect followRedirects() { return Redirect.NEVER; }
            @Override public java.util.Optional<java.net.ProxySelector> proxy() { return java.util.Optional.empty(); }
            @Override public javax.net.ssl.SSLContext sslContext() { return null; }
            @Override public javax.net.ssl.SSLParameters sslParameters() { return null; }
            @Override public java.util.Optional<java.net.Authenticator> authenticator() { return java.util.Optional.empty(); }
            @Override public java.util.Optional<java.net.CookieHandler> cookieHandler() { return java.util.Optional.empty(); }
            @Override public Version version() { return Version.HTTP_1_1; }
            @Override public java.util.Optional<java.util.concurrent.Executor> executor() { return java.util.Optional.empty(); }
            @Override public <T> java.net.http.HttpResponse<T> send(java.net.http.HttpRequest req,
                    java.net.http.HttpResponse.BodyHandler<T> h) throws java.io.IOException {
                throw new SSLHandshakeException("PKIX path building failed");
            }
            @Override public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                    java.net.http.HttpRequest req, java.net.http.HttpResponse.BodyHandler<T> h) {
                throw new UnsupportedOperationException();
            }
            @Override public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                    java.net.http.HttpRequest req, java.net.http.HttpResponse.BodyHandler<T> h,
                    java.net.http.HttpResponse.PushPromiseHandler<T> p) {
                throw new UnsupportedOperationException();
            }
        };
        AtlassianSite jira = new AtlassianSite("https://jira.corp.local", "sk-x", "JIRA_PAT",
                Duration.ofSeconds(5), null);

        AtlassianProbe.ProbeResult r = AtlassianProbe.probe("Jira", jira, "/rest/api/2/myself",
                sslRefusing, Path.of("/etc/ssl/corp-ca.jks"), "name");

        assertThat(r.ok()).isFalse();
        assertThat(r.detail()).isEqualTo(
                "TLS handshake with jira.corp.local failed using truststore /etc/ssl/corp-ca.jks: "
                        + "PKIX path building failed");
    }

    @Test
    void anUnreachableHostIsNotOkAndDoesNotThrow() {
        AtlassianSite dead = new AtlassianSite("http://127.0.0.1:1", "sk-x", "JIRA_PAT",
                Duration.ofSeconds(1), null);

        AtlassianProbe.ProbeResult r = AtlassianProbe.probe("Jira", dead, "/rest/api/2/myself",
                HttpClient.newHttpClient(), null, "name");

        assertThat(r.ok()).isFalse();
        assertThat(r.detail()).isNotBlank();
    }
}
