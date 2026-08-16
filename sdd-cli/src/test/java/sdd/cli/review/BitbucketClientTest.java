package sdd.cli.review;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import sdd.core.http.AtlassianException;
import sdd.core.http.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Bitbucket Data Center REST 1.0 shapes this class assumes — hand-written fixtures per the
 * Task 5 brief, since there is no live instance to record against yet (Phase 6 replaces these with
 * real recordings). See the Task 5 report's "invented / least certain" section for exactly which
 * fields here are guesses versus documented behaviour: the PR {@code version} field's presence on
 * every PR-shaped response, the {@code fromRef}/{@code toRef} create-PR body shape, and the
 * merge/decline endpoints taking {@code version} as a query parameter rather than a body field.
 */
class BitbucketClientTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    private BitbucketClient client() {
        RestClient rc = new RestClient("Bitbucket", wm.baseUrl(), "sk-token", "BITBUCKET_PAT",
                Duration.ofSeconds(5), HttpClient.newHttpClient());
        return new BitbucketClient(rc, "TRADING");
    }

    @Test
    void defaultBranchReadsTheDisplayIdFromTheDefaultBranchResource() {
        wm.stubFor(get(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/default-branch"))
                .willReturn(okJson("""
                        {"id":"refs/heads/main","displayId":"main","type":"BRANCH","isDefault":true}
                        """)));

        assertThat(client().defaultBranch("lib")).isEqualTo("main");
    }

    @Test
    void createPostsFromRefToRefAndReviewersAndReturnsTheParsedPullRequest() {
        wm.stubFor(post(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests"))
                .willReturn(okJson("""
                        {"id":42,"version":0,"title":"SPEC-1: Tiers","description":"body",
                         "links":{"self":[{"href":"https://bb.corp.local/projects/TRADING/repos/lib/pull-requests/42"}]}}
                        """)));

        BitbucketClient.PullRequest pr = client().create("lib", "SPEC-1: Tiers", "body",
                "sdd/SPEC-1-v1/lib", "main", List.of("jsmith", "adoe"));

        assertThat(pr.id()).isEqualTo(42);
        assertThat(pr.version()).isEqualTo(0);
        assertThat(pr.link()).isEqualTo("https://bb.corp.local/projects/TRADING/repos/lib/pull-requests/42");
        wm.verify(postRequestedFor(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests"))
                .withHeader("Authorization", equalTo("Bearer sk-token"))
                .withRequestBody(equalToJson("""
                        {"title":"SPEC-1: Tiers","description":"body",
                         "fromRef":{"id":"refs/heads/sdd/SPEC-1-v1/lib",
                                    "repository":{"slug":"lib","project":{"key":"TRADING"}}},
                         "toRef":{"id":"refs/heads/main",
                                  "repository":{"slug":"lib","project":{"key":"TRADING"}}},
                         "reviewers":[{"user":{"name":"jsmith"}},{"user":{"name":"adoe"}}]}
                        """, true, true)));
    }

    @Test
    void createRepoSlugIsLowercasedButTheProjectKeyIsNot() {
        wm.stubFor(post(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/order-service/pull-requests"))
                .willReturn(okJson("{\"id\":1,\"version\":0,\"title\":\"t\",\"description\":\"d\","
                        + "\"links\":{\"self\":[{\"href\":\"https://bb/x\"}]}}")));

        client().create("Order-Service", "t", "d", "branch", "main", List.of());

        wm.verify(postRequestedFor(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/order-service/pull-requests")));
    }

    @Test
    void findOpenBySourceBranchFiltersByAtAndOutgoingDirection() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests"))
                .willReturn(okJson("""
                        {"size":1,"isLastPage":true,"values":[
                          {"id":7,"version":2,"title":"old title","description":"old body",
                           "links":{"self":[{"href":"https://bb/7"}]}}
                        ]}
                        """)));

        Optional<BitbucketClient.PullRequest> found = client().findOpenBySourceBranch("lib", "sdd/SPEC-1-v1/lib");

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(7);
        assertThat(found.get().version()).isEqualTo(2);
        wm.verify(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests"
                        + "?at=refs%2Fheads%2Fsdd%2FSPEC-1-v1%2Flib&direction=OUTGOING&state=OPEN")));
    }

    @Test
    void findOpenBySourceBranchIsEmptyWhenNoPullRequestExists() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests"))
                .willReturn(okJson("{\"size\":0,\"isLastPage\":true,\"values\":[]}")));

        assertThat(client().findOpenBySourceBranch("lib", "sdd/SPEC-1-v1/lib")).isEmpty();
    }

    @Test
    void updateDescriptionPutsTheCurrentVersionAlongsideTheNewTitleAndDescription() {
        wm.stubFor(put(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests/7"))
                .willReturn(okJson("""
                        {"id":7,"version":3,"title":"new title","description":"new body",
                         "links":{"self":[{"href":"https://bb/7"}]}}
                        """)));
        BitbucketClient.PullRequest existing = new BitbucketClient.PullRequest(7, 2, "old", "old", "https://bb/7");

        BitbucketClient.PullRequest updated = client().updateDescription("lib", existing, "new title", "new body");

        assertThat(updated.version()).isEqualTo(3);
        wm.verify(putRequestedFor(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests/7"))
                .withRequestBody(equalToJson(
                        "{\"version\":2,\"title\":\"new title\",\"description\":\"new body\"}", true, true)));
    }

    @Test
    void getReadsOnePullRequestById() {
        wm.stubFor(get(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests/7"))
                .willReturn(okJson("""
                        {"id":7,"version":5,"title":"t","description":"d",
                         "links":{"self":[{"href":"https://bb/7"}]}}
                        """)));

        assertThat(client().get("lib", 7).version()).isEqualTo(5);
    }

    @Test
    void mergePostsToTheMergeEndpointWithVersionAsAQueryParameter() {
        wm.stubFor(post(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests/7/merge?version=2"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.ok()));

        client().merge("lib", 7, 2);

        wm.verify(postRequestedFor(
                urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests/7/merge?version=2")));
    }

    @Test
    void declinePostsToTheDeclineEndpointWithVersionAsAQueryParameter() {
        wm.stubFor(post(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests/7/decline?version=2"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.ok()));

        client().decline("lib", 7, 2);

        wm.verify(postRequestedFor(
                urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests/7/decline?version=2")));
    }

    @Test
    void mergeOnAVersionConflictSurfacesAClearMessageRatherThanRetryingBlindly() {
        wm.stubFor(post(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests/7/merge?version=1"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.status(409)
                        .withBody("{\"errors\":[{\"message\":\"PR has been updated\"}]}")));

        assertThatThrownBy(() -> client().merge("lib", 7, 1))
                .isInstanceOf(AtlassianException.class)
                .hasMessageContaining("version conflict");
    }

    @Test
    void declineOnAVersionConflictSurfacesAClearMessageRatherThanRetryingBlindly() {
        wm.stubFor(post(urlEqualTo("/rest/api/1.0/projects/TRADING/repos/lib/pull-requests/7/decline?version=1"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.status(409)
                        .withBody("{\"errors\":[{\"message\":\"PR has been updated\"}]}")));

        assertThatThrownBy(() -> client().decline("lib", 7, 1))
                .isInstanceOf(AtlassianException.class)
                .hasMessageContaining("version conflict");
    }
}
