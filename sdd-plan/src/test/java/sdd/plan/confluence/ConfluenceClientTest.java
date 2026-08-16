package sdd.plan.confluence;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import sdd.core.http.AtlassianException;
import sdd.core.http.RestClient;
import sdd.plan.source.SourceDoc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

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

    private ConfluenceClient client() {
        RestClient rc = new RestClient("Confluence", wm.baseUrl(), "sk-token", "CONFLUENCE_PAT",
                Duration.ofSeconds(5), HttpClient.newHttpClient());
        return new ConfluenceClient(rc, HttpClient.newHttpClient(), "sk-token", wm.baseUrl());
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
    void anUnrecognizedUrlShapeIsUnresolvableWithoutAnyNetworkCall() {
        String id = client().resolvePageId("https://confluence.corp.local/some/other/thing");

        assertThat(id).isNull();
        wm.verify(0, getRequestedFor(urlEqualTo("/some/other/thing")));
    }
}
