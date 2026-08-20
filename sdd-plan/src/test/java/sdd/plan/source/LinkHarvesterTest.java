package sdd.plan.source;

import org.junit.jupiter.api.Test;
import sdd.core.http.AtlassianException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LinkHarvester} against a scripted {@link ConfluencePages} fake — no Mockito in this
 * repo, so the seam is a small interface ({@link ConfluencePages}) that {@code ConfluenceClient}
 * implements in production and this test implements by hand, mirroring {@code ChatModel} and
 * {@code RestClient.Sleeper} elsewhere in the codebase.
 */
class LinkHarvesterTest {
    private static SourceDoc page(String id, String url, String title, String text) {
        return new SourceDoc(SourceDoc.Kind.CONFLUENCE_PAGE, id, url, title, "1", text, List.of());
    }

    private static final class FakeConfluencePages implements ConfluencePages {
        private final Map<String, String> idsByUrl = new LinkedHashMap<>();
        private final Map<String, SourceDoc> pagesById = new LinkedHashMap<>();
        final List<String> resolveCalls = new ArrayList<>();
        final List<String> fetchCalls = new ArrayList<>();

        FakeConfluencePages map(String url, String pageId) {
            idsByUrl.put(url, pageId);
            return this;
        }

        FakeConfluencePages serve(SourceDoc doc) {
            pagesById.put(doc.id(), doc);
            return this;
        }

        @Override
        public String resolvePageId(String url) {
            resolveCalls.add(url);
            return idsByUrl.get(url);
        }

        @Override
        public SourceDoc fetchPage(String pageId) {
            fetchCalls.add(pageId);
            SourceDoc doc = pagesById.get(pageId);
            if (doc == null) {
                throw new AtlassianException("Confluence HTTP 404: no such page " + pageId);
            }
            return doc;
        }
    }

    @Test
    void harvestsABareUrlFromJiraTextAndFetchesIt() {
        FakeConfluencePages fake = new FakeConfluencePages()
                .map("https://confluence.corp.local/pages/viewpage.action?pageId=65601", "65601")
                .serve(page("65601", "https://confluence.corp.local/pages/viewpage.action?pageId=65601",
                        "Order API spec", "content"));
        LinkHarvester harvester = new LinkHarvester(fake, "confluence.corp.local", 1, 20);
        SourceDoc issue = new SourceDoc(SourceDoc.Kind.JIRA_ISSUE, "PROJ-1", null, null, null,
                "See https://confluence.corp.local/pages/viewpage.action?pageId=65601 for details.", List.of());

        LinkHarvester.Result result = harvester.harvest(List.of(issue), List.of());

        assertThat(result.pages()).extracting(SourceDoc::id).containsExactly("65601");
        assertThat(result.notes()).isEmpty();
    }

    @Test
    void harvestsARemoteLinkUrlEvenWhenNotMentionedInAnyText() {
        FakeConfluencePages fake = new FakeConfluencePages()
                .map("https://confluence.corp.local/pages/viewpage.action?pageId=70000", "70000")
                .serve(page("70000", "https://confluence.corp.local/pages/viewpage.action?pageId=70000",
                        "Design notes", "content"));
        LinkHarvester harvester = new LinkHarvester(fake, "confluence.corp.local", 1, 20);
        SourceDoc issue = new SourceDoc(SourceDoc.Kind.JIRA_ISSUE, "PROJ-1", null, null, null,
                "No links in the text at all.", List.of());

        LinkHarvester.Result result = harvester.harvest(List.of(issue),
                List.of("https://confluence.corp.local/pages/viewpage.action?pageId=70000"));

        assertThat(result.pages()).extracting(SourceDoc::id).containsExactly("70000");
    }

    @Test
    void wrongHostLinkBecomesANoteNamingTheUrlAndIsNeverResolved() {
        FakeConfluencePages fake = new FakeConfluencePages();
        LinkHarvester harvester = new LinkHarvester(fake, "confluence.corp.local", 1, 20);
        SourceDoc issue = new SourceDoc(SourceDoc.Kind.JIRA_ISSUE, "PROJ-1", null, null, null,
                "See https://example.com/not-confluence for context.", List.of());

        LinkHarvester.Result result = harvester.harvest(List.of(issue), List.of());

        assertThat(result.pages()).isEmpty();
        assertThat(result.notes()).singleElement().asString()
                .contains("wrong host").contains("https://example.com/not-confluence");
        assertThat(fake.resolveCalls).isEmpty();   // wrong host is rejected before any resolve call
    }

    @Test
    void unresolvableUrlShapeBecomesANote() {
        FakeConfluencePages fake = new FakeConfluencePages();   // no mapping => resolvePageId returns null
        LinkHarvester harvester = new LinkHarvester(fake, "confluence.corp.local", 1, 20);
        SourceDoc issue = new SourceDoc(SourceDoc.Kind.JIRA_ISSUE, "PROJ-1", null, null, null,
                "See https://confluence.corp.local/some/weird/shape for context.", List.of());

        LinkHarvester.Result result = harvester.harvest(List.of(issue), List.of());

        assertThat(result.notes()).singleElement().asString().contains("unresolvable")
                .contains("https://confluence.corp.local/some/weird/shape");
    }

    @Test
    void anOversizedPageBecomesANoteRatherThanEndingTheRun() {
        // ConfluenceExtract.extract throws SpecNormalizationException, which extends
        // RuntimeException -- NOT AtlassianException -- and fetchPage calls it. So one 300k-char
        // page anywhere in the link graph took down the whole `sdd plan` run, and did it looking
        // like a budgeting bug rather than an exception-typing one. Every other declined link in
        // this class earns a note; this one has to as well.
        ConfluencePages fake = new ConfluencePages() {
            @Override
            public String resolvePageId(String url) {
                return "1";
            }

            @Override
            public SourceDoc fetchPage(String pageId) {
                throw new sdd.plan.confluence.SpecNormalizationException(
                        "Confluence export too large: extracted 421337 chars (limit 300000)");
            }
        };
        LinkHarvester harvester = new LinkHarvester(fake, "confluence.corp.local", 1, 20);
        SourceDoc issue = new SourceDoc(SourceDoc.Kind.JIRA_ISSUE, "PROJ-1", null, null, null,
                "See https://confluence.corp.local/pages/viewpage.action?pageId=1 for context.",
                List.of());

        LinkHarvester.Result result = harvester.harvest(List.of(issue), List.of());

        assertThat(result.pages()).isEmpty();
        assertThat(result.notes()).singleElement().asString()
                .contains("too large to read")
                .contains("https://confluence.corp.local/pages/viewpage.action?pageId=1")
                .contains("421337");
    }

    @Test
    void anUnresolvablePageThatThrowsIsStillJustANote() {
        ConfluencePages fake = new ConfluencePages() {
            @Override
            public String resolvePageId(String url) {
                throw new sdd.plan.confluence.SpecNormalizationException("bad escape in title");
            }

            @Override
            public SourceDoc fetchPage(String pageId) {
                throw new AssertionError("must not be reached");
            }
        };
        LinkHarvester harvester = new LinkHarvester(fake, "confluence.corp.local", 1, 20);
        SourceDoc issue = new SourceDoc(SourceDoc.Kind.JIRA_ISSUE, "PROJ-1", null, null, null,
                "See https://confluence.corp.local/display/ENG/x for context.", List.of());

        assertThat(harvester.harvest(List.of(issue), List.of()).notes())
                .singleElement().asString().contains("unresolvable");
    }

    @Test
    void fetchFailureBecomesANoteRatherThanAbortingIngestion() {
        FakeConfluencePages fake = new FakeConfluencePages()
                .map("https://confluence.corp.local/pages/viewpage.action?pageId=1", "1");
        // no .serve("1") => fetchPage throws
        LinkHarvester harvester = new LinkHarvester(fake, "confluence.corp.local", 1, 20);
        SourceDoc issue = new SourceDoc(SourceDoc.Kind.JIRA_ISSUE, "PROJ-1", null, null, null,
                "See https://confluence.corp.local/pages/viewpage.action?pageId=1 for context.", List.of());

        LinkHarvester.Result result = harvester.harvest(List.of(issue), List.of());

        assertThat(result.pages()).isEmpty();
        assertThat(result.notes()).singleElement().asString().contains("fetch failed")
                .contains("https://confluence.corp.local/pages/viewpage.action?pageId=1");
    }

    @Test
    void twoDifferentUrlsResolvingToTheSamePageIdAreFetchedOnlyOnce() {
        FakeConfluencePages fake = new FakeConfluencePages()
                .map("https://confluence.corp.local/pages/viewpage.action?pageId=1", "1")
                .map("https://confluence.corp.local/x/AbCd", "1")
                .serve(page("1", "https://confluence.corp.local/pages/viewpage.action?pageId=1", "T", "content"));
        LinkHarvester harvester = new LinkHarvester(fake, "confluence.corp.local", 1, 20);
        SourceDoc issue = new SourceDoc(SourceDoc.Kind.JIRA_ISSUE, "PROJ-1", null, null, null,
                "See https://confluence.corp.local/pages/viewpage.action?pageId=1 and also "
                        + "https://confluence.corp.local/x/AbCd which is the same page.", List.of());

        LinkHarvester.Result result = harvester.harvest(List.of(issue), List.of());

        assertThat(result.pages()).hasSize(1);
        assertThat(fake.fetchCalls).containsExactly("1");
        assertThat(result.notes()).isEmpty();   // a cycle is not a drop — nothing was left out
    }

    @Test
    void aDepth2LinkInsideAFetchedPageIsNotFollowedWhenFollowDepthIsOne() {
        FakeConfluencePages fake = new FakeConfluencePages()
                .map("https://confluence.corp.local/pages/viewpage.action?pageId=1", "1")
                .map("https://confluence.corp.local/pages/viewpage.action?pageId=2", "2")
                .serve(page("1", "https://confluence.corp.local/pages/viewpage.action?pageId=1", "T1",
                        "Nested: https://confluence.corp.local/pages/viewpage.action?pageId=2"))
                .serve(page("2", "https://confluence.corp.local/pages/viewpage.action?pageId=2", "T2", "leaf"));
        LinkHarvester harvester = new LinkHarvester(fake, "confluence.corp.local", 1, 20);
        SourceDoc issue = new SourceDoc(SourceDoc.Kind.JIRA_ISSUE, "PROJ-1", null, null, null,
                "See https://confluence.corp.local/pages/viewpage.action?pageId=1", List.of());

        LinkHarvester.Result result = harvester.harvest(List.of(issue), List.of());

        assertThat(result.pages()).extracting(SourceDoc::id).containsExactly("1");
        assertThat(result.notes()).singleElement().asString().contains("over the depth limit")
                .contains("pageId=2");
    }

    @Test
    void aDepth2LinkIsFollowedWhenFollowDepthIsTwo() {
        FakeConfluencePages fake = new FakeConfluencePages()
                .map("https://confluence.corp.local/pages/viewpage.action?pageId=1", "1")
                .map("https://confluence.corp.local/pages/viewpage.action?pageId=2", "2")
                .serve(page("1", "https://confluence.corp.local/pages/viewpage.action?pageId=1", "T1",
                        "Nested: https://confluence.corp.local/pages/viewpage.action?pageId=2"))
                .serve(page("2", "https://confluence.corp.local/pages/viewpage.action?pageId=2", "T2", "leaf"));
        LinkHarvester harvester = new LinkHarvester(fake, "confluence.corp.local", 2, 20);
        SourceDoc issue = new SourceDoc(SourceDoc.Kind.JIRA_ISSUE, "PROJ-1", null, null, null,
                "See https://confluence.corp.local/pages/viewpage.action?pageId=1", List.of());

        LinkHarvester.Result result = harvester.harvest(List.of(issue), List.of());

        assertThat(result.pages()).extracting(SourceDoc::id).containsExactly("1", "2");
        // Gate review minor: follow_depth > 1 always earns the honest "bare URLs only" note — see
        // the dedicated test below for exactly why. Nothing else was dropped or unresolvable here,
        // so that note is the only one.
        assertThat(result.notes()).singleElement().asString()
                .contains("bare URLs").contains("follow_depth=2");
    }

    @Test
    void followDepthGreaterThanOneEmitsAnHonestNoteAboutNamedHyperlinksNotBeingFollowed() {
        // ConfluenceExtract drops every <a> element's href by the time this class scans a fetched
        // page's own text for nested candidates (urlsIn's own javadoc) — so a NAMED hyperlink
        // inside a fetched Confluence page silently yields no candidate at depth 2+. Without this
        // note, follow_depth: 2 promises more than it delivers with no warning at all.
        FakeConfluencePages fake = new FakeConfluencePages();
        LinkHarvester harvester = new LinkHarvester(fake, "confluence.corp.local", 2, 20);

        LinkHarvester.Result result = harvester.harvest(List.of(), List.of());

        assertThat(result.notes()).singleElement().asString()
                .contains("bare URLs").contains("named hyperlinks").contains("follow_depth=2");
    }

    @Test
    void followDepthOfOneEmitsNoHonestNoteSinceNoDepth2FollowingEverHappens() {
        FakeConfluencePages fake = new FakeConfluencePages();
        LinkHarvester harvester = new LinkHarvester(fake, "confluence.corp.local", 1, 20);

        LinkHarvester.Result result = harvester.harvest(List.of(), List.of());

        assertThat(result.notes()).isEmpty();
    }

    @Test
    void maxPagesCapsTotalFetchesAndNotesTheRest() {
        FakeConfluencePages fake = new FakeConfluencePages()
                .map("https://confluence.corp.local/pages/viewpage.action?pageId=1", "1")
                .map("https://confluence.corp.local/pages/viewpage.action?pageId=2", "2")
                .serve(page("1", "https://confluence.corp.local/pages/viewpage.action?pageId=1", "T1", "content"))
                .serve(page("2", "https://confluence.corp.local/pages/viewpage.action?pageId=2", "T2", "content"));
        LinkHarvester harvester = new LinkHarvester(fake, "confluence.corp.local", 1, 1);
        SourceDoc issue = new SourceDoc(SourceDoc.Kind.JIRA_ISSUE, "PROJ-1", null, null, null,
                "https://confluence.corp.local/pages/viewpage.action?pageId=1 and "
                        + "https://confluence.corp.local/pages/viewpage.action?pageId=2", List.of());

        LinkHarvester.Result result = harvester.harvest(List.of(issue), List.of());

        assertThat(result.pages()).hasSize(1);
        assertThat(result.notes()).singleElement().asString().contains("over the page cap")
                .contains("pageId=2");
    }

    @Test
    void twoDifferentAliasesOfAnOverCapPageEachEarnTheirOwnNoteRatherThanOneSilentlyVanishing() {
        // Task 3 review Fix 4: marking a page id "visited" before the cap check meant a second,
        // different URL resolving to the same over-cap page id took the "cycle, nothing left out"
        // branch and produced no note at all — even though that page was never fetched either
        // time. Both aliases must independently earn "over the page cap".
        FakeConfluencePages fake = new FakeConfluencePages()
                .map("https://confluence.corp.local/pages/viewpage.action?pageId=1", "1")
                .map("https://confluence.corp.local/x/AbCd", "1");
        // no .serve("1") at all — if either alias were ever actually fetched, fetchCalls would
        // record it; maxPages=0 means neither should be.
        LinkHarvester harvester = new LinkHarvester(fake, "confluence.corp.local", 1, 0);
        SourceDoc issue = new SourceDoc(SourceDoc.Kind.JIRA_ISSUE, "PROJ-1", null, null, null,
                "https://confluence.corp.local/pages/viewpage.action?pageId=1 and also "
                        + "https://confluence.corp.local/x/AbCd which resolves to the same page.", List.of());

        LinkHarvester.Result result = harvester.harvest(List.of(issue), List.of());

        assertThat(result.pages()).isEmpty();
        assertThat(fake.fetchCalls).isEmpty();
        assertThat(result.notes()).hasSize(2)
                .allSatisfy(note -> assertThat(note).contains("over the page cap"));
        assertThat(result.notes().get(0)).contains("pageId=1");
        assertThat(result.notes().get(1)).contains("/x/AbCd");
    }

    @Test
    void confluencePagesAreReturnedInFetchOrder() {
        FakeConfluencePages fake = new FakeConfluencePages()
                .map("https://confluence.corp.local/pages/viewpage.action?pageId=2", "2")
                .map("https://confluence.corp.local/pages/viewpage.action?pageId=1", "1")
                .serve(page("2", "https://confluence.corp.local/pages/viewpage.action?pageId=2", "Second", "c"))
                .serve(page("1", "https://confluence.corp.local/pages/viewpage.action?pageId=1", "First", "c"));
        LinkHarvester harvester = new LinkHarvester(fake, "confluence.corp.local", 1, 20);
        SourceDoc issue = new SourceDoc(SourceDoc.Kind.JIRA_ISSUE, "PROJ-1", null, null, null,
                "https://confluence.corp.local/pages/viewpage.action?pageId=2 then "
                        + "https://confluence.corp.local/pages/viewpage.action?pageId=1", List.of());

        LinkHarvester.Result result = harvester.harvest(List.of(issue), List.of());

        assertThat(result.pages()).extracting(SourceDoc::id).containsExactly("2", "1");
    }

    @Test
    void hostMatchingIsCaseInsensitive() {
        FakeConfluencePages fake = new FakeConfluencePages()
                .map("https://CONFLUENCE.corp.local/pages/viewpage.action?pageId=1", "1")
                .serve(page("1", "https://CONFLUENCE.corp.local/pages/viewpage.action?pageId=1", "T", "c"));
        LinkHarvester harvester = new LinkHarvester(fake, "confluence.corp.local", 1, 20);
        SourceDoc issue = new SourceDoc(SourceDoc.Kind.JIRA_ISSUE, "PROJ-1", null, null, null,
                "https://CONFLUENCE.corp.local/pages/viewpage.action?pageId=1", List.of());

        LinkHarvester.Result result = harvester.harvest(List.of(issue), List.of());

        assertThat(result.pages()).hasSize(1);
    }
}
