package sdd.plan.confluence;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.diagnostics.DiagnosticWriter;
import sdd.core.http.AtlassianException;
import sdd.core.http.RestClient;
import sdd.plan.source.SourceDoc;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fixtures under {@code src/test/resources/confluence} are hand-written from the documented
 * Data Center storage-format/content-search shapes; see the Task 3 report for which details are
 * least certain (in particular the exact {@code /rest/api/content} search response shape, and
 * whether a tiny-link {@code /x/AbCd} 302's {@code Location} is always relative on Data Center).
 */
class ConfluenceClientTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    @TempDir Path tmp;

    private ConfluenceClient client() {
        return client(Duration.ofSeconds(5));
    }

    private ConfluenceClient client(Duration timeout) {
        RestClient rc = new RestClient("Confluence", wm.baseUrl(), "sk-token", "CONFLUENCE_PAT",
                Duration.ofSeconds(5), HttpClient.newHttpClient());
        return new ConfluenceClient(rc, HttpClient.newHttpClient(), "sk-token", wm.baseUrl(), timeout);
    }

    private static String fixture(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/confluence/" + name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void fetchPageParsesStorageFormatBodyTitleAndVersion() {
        wm.stubFor(get(urlEqualTo("/rest/api/content/65601?expand=body.storage,version,space"))
                .willReturn(okJson(fixture("page.json"))));

        SourceDoc doc = client().fetchPage("65601");

        assertThat(doc.kind()).isEqualTo(SourceDoc.Kind.CONFLUENCE_PAGE);
        assertThat(doc.id()).isEqualTo("65601");
        assertThat(doc.title()).isEqualTo("Order API spec");
        assertThat(doc.version()).isEqualTo("7");
        assertThat(doc.url()).isEqualTo(wm.baseUrl() + "/pages/viewpage.action?pageId=65601");
        assertThat(doc.text()).contains("Pagination uses opaque cursors");
        wm.verify(getRequestedFor(urlEqualTo("/rest/api/content/65601?expand=body.storage,version,space"))
                .withHeader("Authorization", com.github.tomakehurst.wiremock.client.WireMock.equalTo("Bearer sk-token")));
    }

    @Test
    void fetchPageOn404PropagatesTheAtlassianException() {
        wm.stubFor(get(urlEqualTo("/rest/api/content/404?expand=body.storage,version,space"))
                .willReturn(notFound()));

        assertThatThrownBy(() -> client().fetchPage("404")).isInstanceOf(AtlassianException.class);
    }

    @Test
    void resolvesAViewpageActionUrlDirectlyFromTheQueryParameter() {
        String id = client().resolvePageId(wm.baseUrl() + "/pages/viewpage.action?pageId=65601");

        assertThat(id).isEqualTo("65601");
    }

    @Test
    void resolvesASpacesPagesUrlDirectlyFromThePathSegment() {
        String id = client().resolvePageId(wm.baseUrl() + "/spaces/ENG/pages/65601/Order+API+spec");

        assertThat(id).isEqualTo("65601");
    }

    @Test
    void resolvesADisplayUrlByTitleSearch() {
        wm.stubFor(get(urlEqualTo(
                "/rest/api/content?spaceKey=ENG&title=Order%20API%20spec&expand=version"))
                .willReturn(okJson(fixture("content-search-by-title.json"))));

        String id = client().resolvePageId(wm.baseUrl() + "/display/ENG/Order+API+spec");

        assertThat(id).isEqualTo("65602");
    }

    /** A Data Center install served under a context path, e.g. https://wiki.corp.local/confluence. */
    private ConfluenceClient contextPathClient() {
        String base = wm.baseUrl() + "/confluence";
        RestClient rc = new RestClient("Confluence", base, "sk-token", "CONFLUENCE_PAT",
                Duration.ofSeconds(5), HttpClient.newHttpClient());
        return new ConfluenceClient(rc, HttpClient.newHttpClient(), "sk-token", base,
                Duration.ofSeconds(5));
    }

    @Test
    void everyUrlShapeResolvesWhenConfluenceIsServedUnderAContextPath() {
        // Nothing in this suite has ever exercised a non-root base_url: wm.baseUrl() is always
        // http://localhost:PORT. TINY_LINK and DISPLAY were anchored with ^, so on an install at
        // /confluence they matched nothing and every tiny link and display URL in the estate would
        // have come back "unresolvable" -- while /pages/ kept working, because PAGES_ID uses find().
        wm.stubFor(get(urlEqualTo("/confluence/x/AbCd")).willReturn(aResponse().withStatus(302)
                .withHeader("Location", "/confluence/pages/viewpage.action?pageId=65601")));
        wm.stubFor(get(urlEqualTo(
                "/confluence/rest/api/content?spaceKey=ENG&title=Order%20API%20spec&expand=version"))
                .willReturn(okJson(fixture("content-search-by-title.json"))));

        ConfluenceClient client = contextPathClient();

        assertThat(client.resolvePageId(wm.baseUrl() + "/confluence/pages/viewpage.action?pageId=65601"))
                .isEqualTo("65601");
        assertThat(client.resolvePageId(wm.baseUrl() + "/confluence/x/AbCd")).isEqualTo("65601");
        assertThat(client.resolvePageId(wm.baseUrl() + "/confluence/display/ENG/Order+API+spec"))
                .isEqualTo("65602");
    }

    @Test
    void aSameHostUrlOutsideTheContextPathIsRefused() {
        // Fails closed, the same way a host or scheme mismatch does: the configured install lives
        // under /confluence, so /display/... on that host is some other application.
        assertThat(contextPathClient().resolvePageId(wm.baseUrl() + "/display/ENG/Order+API+spec"))
                .isNull();
    }

    @Test
    void aTitleContainingAPlusSignIsNotTurnedIntoASpace() {
        // uri.getPath() has ALREADY percent-decoded, so %2B arrives as a literal '+', and the
        // second URLDecoder pass then reads it as a space -- the search goes out for the wrong
        // title and quietly finds nothing.
        wm.stubFor(get(urlEqualTo(
                "/rest/api/content?spaceKey=ENG&title=C%2B%2B%20style&expand=version"))
                .willReturn(okJson(fixture("content-search-by-title.json"))));

        assertThat(client().resolvePageId(wm.baseUrl() + "/display/ENG/C%2B%2B+style"))
                .isEqualTo("65602");
    }

    @Test
    void aTitleContainingAPercentSignDoesNotBlowUpTheRun() {
        // Same double decode, worse outcome: %25 decodes to a bare '%', which URLDecoder rejects
        // with IllegalArgumentException. LinkHarvester catches only AtlassianException, so this
        // took the whole sdd plan run down rather than becoming one unresolvable note.
        wm.stubFor(get(urlEqualTo(
                "/rest/api/content?spaceKey=ENG&title=100%25%20uptime&expand=version"))
                .willReturn(okJson(fixture("content-search-by-title.json"))));

        assertThat(client().resolvePageId(wm.baseUrl() + "/display/ENG/100%25+uptime"))
                .isEqualTo("65602");
    }

    @Test
    void aMalformedEscapeInATitleIsUnresolvableRatherThanFatal() {
        assertThat(client().resolvePageId(wm.baseUrl() + "/display/ENG/broken%ZZ")).isNull();
    }

    @Test
    void aDisplayUrlWithNoMatchingTitleIsUnresolvable() {
        wm.stubFor(get(urlEqualTo(
                "/rest/api/content?spaceKey=ENG&title=Nonexistent&expand=version"))
                .willReturn(okJson(fixture("content-search-empty.json"))));

        String id = client().resolvePageId(wm.baseUrl() + "/display/ENG/Nonexistent");

        assertThat(id).isNull();
    }

    @Test
    void resolvesATinyLinkByFollowingItsRedirectThenReResolving() {
        wm.stubFor(get(urlEqualTo("/x/AbCd")).willReturn(aResponse().withStatus(302)
                .withHeader("Location", "/pages/viewpage.action?pageId=65601")));

        String id = client().resolvePageId(wm.baseUrl() + "/x/AbCd");

        assertThat(id).isEqualTo("65601");
    }

    @Test
    void resolvingATinyLinkThatHangsTimesOutInsteadOfBlockingForever() {
        // Task 3 review Fix 3: the tiny-link redirect request bypasses RestClient (the one case
        // that needs a raw Location header) and so did not inherit RestClient.execute's own
        // .timeout(...) call. A short client-side timeout here proves the request now actually
        // bounds its wait rather than hanging on a server that never responds.
        wm.stubFor(get(urlEqualTo("/x/AbCd")).willReturn(aResponse().withStatus(302)
                .withHeader("Location", "/pages/viewpage.action?pageId=65601")
                .withFixedDelay(2000)));

        assertThatThrownBy(() -> client(Duration.ofMillis(200)).resolvePageId(wm.baseUrl() + "/x/AbCd"))
                .isInstanceOf(AtlassianException.class)
                .hasMessageContaining("transport error resolving tiny link");
    }

    @Test
    void anUnrecognizedUrlShapeIsUnresolvableWithoutAnyNetworkCall() {
        String id = client().resolvePageId("https://confluence.corp.local/some/other/thing");

        assertThat(id).isNull();
        wm.verify(0, getRequestedFor(urlEqualTo("/some/other/thing")));
    }

    // --- Gate review C1: the Confluence PAT must never reach a host other than the configured one --

    @Test
    void aRefWhoseHostIsNotTheConfiguredConfluenceHostIsRejectedBeforeAnyRequest() {
        // Task 3's fix round added a second resolvePageId caller (PlanCommand, resolving a direct
        // CONFLUENCE_PAGE ref) that — unlike LinkHarvester — performed no host check of its own.
        // The guarantee has to live in resolvePageId itself, or a ref naming an arbitrary host on
        // the command line (sdd plan https://evil.example/x/AbCd) would carry the bearer token
        // straight there. No stub is registered for evil.example — a network attempt on the wrong
        // host would fail this test with a connection error, not just a wrong assertion.
        String id = client().resolvePageId("https://evil.example/x/AbCd");

        assertThat(id).isNull();
        wm.verify(0, getRequestedFor(urlEqualTo("/x/AbCd")));
    }

    @Test
    void aTinyLinkRedirectToAnotherHostIsRefusedRatherThanFollowedWithTheBearerToken() {
        // The second C1 vector: the hand-rolled redirect chain used to re-send the Authorization
        // header to uri.resolve(location) with no host re-check, so one off-host Location anywhere
        // in the chain handed the token away. The Location below points at a different host
        // entirely; only the FIRST hop (to wm's own host) may ever be requested.
        wm.stubFor(get(urlEqualTo("/x/AbCd")).willReturn(aResponse().withStatus(302)
                .withHeader("Location", "https://evil.example/pages/viewpage.action?pageId=65601")));

        String id = client().resolvePageId(wm.baseUrl() + "/x/AbCd");

        assertThat(id).isNull();
        wm.verify(1, getRequestedFor(urlEqualTo("/x/AbCd")));
    }

    @Test
    void aCleartextHttpLinkToTheSameHostAsAnHttpsConfiguredSiteIsRejectedBeforeAnyRequest() {
        // Gate re-review Fix 1: hostMatches used to compare HOST only. LinkHarvester's URL regex
        // is https?://\S+, so any Jira commenter could plant a plain http:// link to the
        // configured host in an issue/comment — same host, so it used to pass every check — and
        // downgrade the bearer token to cleartext (and, with atlassian.proxy configured, straight
        // through that proxy's access logs). This client is configured against an https site;
        // resolving a same-host http:// candidate must fail closed with zero requests, not
        // "successfully" reach a real host (confluence.corp.local does not resolve here — a real
        // attempt would fail this test with a connection/DNS error, not a wrong assertion).
        RestClient rc = new RestClient("Confluence", "https://confluence.corp.local", "sk-token",
                "CONFLUENCE_PAT", Duration.ofSeconds(5), HttpClient.newHttpClient());
        ConfluenceClient httpsClient = new ConfluenceClient(rc, HttpClient.newHttpClient(), "sk-token",
                "https://confluence.corp.local", Duration.ofSeconds(5));

        String id = httpsClient.resolvePageId("http://confluence.corp.local/x/AbCd");

        assertThat(id).isNull();
    }

    @Test
    void aLinkToTheConfiguredHostOnADifferentPortIsRejectedBeforeAnyRequest() {
        // Gate re-review Fix 1's other half: same host, same scheme, wrong port — must also fail
        // closed with no request sent.
        int wrongPort = wm.getPort() + 1;
        String wrongPortUrl = "http://127.0.0.1:" + wrongPort + "/x/AbCd";

        String id = client().resolvePageId(wrongPortUrl);

        assertThat(id).isNull();
        wm.verify(0, getRequestedFor(urlEqualTo("/x/AbCd")));
    }

    // --- Gate review I7: the tiny-link request bypasses RestClient, so it must log itself --------

    @Test
    void aTinyLinkResolutionWritesItsOwnHttpRequestLineToDiagnostics() throws Exception {
        // Task 3 review Fix 3's own rationale ("a hung /x/AbCd... would block sdd plan
        // indefinitely") is exactly the failure this line exists to make debuggable remotely: this
        // request is the one HTTP call in this class that does not go through RestClient, and was
        // therefore invisible to diagnostics before this fix — zero lines in the one file built to
        // debug exactly this kind of hang or proxy rewrite.
        wm.stubFor(get(urlEqualTo("/x/AbCd")).willReturn(aResponse().withStatus(302)
                .withHeader("Location", "/pages/viewpage.action?pageId=65601")));
        Path diagFile = tmp.resolve("diag.log");
        DiagnosticWriter diagnostics =
                new DiagnosticWriter(diagFile, Set.of(), InstantSource.system(), new PrintWriter(new StringWriter()));
        RestClient rc = new RestClient("Confluence", wm.baseUrl(), "sk-token", "CONFLUENCE_PAT",
                Duration.ofSeconds(5), HttpClient.newHttpClient());
        ConfluenceClient client = new ConfluenceClient(rc, HttpClient.newHttpClient(), "sk-token", wm.baseUrl(),
                Duration.ofSeconds(5), diagnostics);

        String id = client.resolvePageId(wm.baseUrl() + "/x/AbCd");
        diagnostics.close();

        assertThat(id).isEqualTo("65601");
        String logged = Files.readString(diagFile);
        assertThat(logged).contains("http site=Confluence method=GET path=/x/AbCd status=302");
    }
}
