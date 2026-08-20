package sdd.plan.spec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpecSourcesTest {

    @Test
    void confluenceExportExtensionsAreCaseInsensitive() {
        assertThat(SpecSources.classify("page.html")).isEqualTo(SpecRefKind.CONFLUENCE_EXPORT);
        assertThat(SpecSources.classify("page.HTML")).isEqualTo(SpecRefKind.CONFLUENCE_EXPORT);
        assertThat(SpecSources.classify("page.htm")).isEqualTo(SpecRefKind.CONFLUENCE_EXPORT);
        assertThat(SpecSources.classify("page.xhtml")).isEqualTo(SpecRefKind.CONFLUENCE_EXPORT);
    }

    @Test
    void bareJiraKeyIsJira() {
        assertThat(SpecSources.classify("PROJ-123")).isEqualTo(SpecRefKind.JIRA);
        assertThat(SpecSources.classify("A1B_C-1")).isEqualTo(SpecRefKind.JIRA);
    }

    @Test
    void lowercaseOrMalformedKeyIsNotJira() {
        assertThat(SpecSources.classify("proj-123")).isEqualTo(SpecRefKind.MARKDOWN);
        assertThat(SpecSources.classify("PROJ-0")).isEqualTo(SpecRefKind.MARKDOWN);
        assertThat(SpecSources.classify("PROJ")).isEqualTo(SpecRefKind.MARKDOWN);
        assertThat(SpecSources.classify("-123")).isEqualTo(SpecRefKind.MARKDOWN);
    }

    @Test
    void jiraBrowseUrlIsJira() {
        assertThat(SpecSources.classify("https://jira.corp.local/browse/PROJ-123"))
                .isEqualTo(SpecRefKind.JIRA);
        assertThat(SpecSources.classify("http://jira.corp.local/browse/PROJ-123?foo=bar"))
                .isEqualTo(SpecRefKind.JIRA);
    }

    @Test
    void confluencePageUrlShapesAreConfluencePage() {
        assertThat(SpecSources.classify("https://confluence.corp.local/pages/viewpage.action?pageId=1"))
                .isEqualTo(SpecRefKind.CONFLUENCE_PAGE);
        assertThat(SpecSources.classify("https://confluence.corp.local/display/PROJ/Some+Page"))
                .isEqualTo(SpecRefKind.CONFLUENCE_PAGE);
        assertThat(SpecSources.classify("https://confluence.corp.local/x/AbCdEf"))
                .isEqualTo(SpecRefKind.CONFLUENCE_PAGE);
    }

    @Test
    void theLegacyViewpageShapeIsAConfluencePage() {
        // The bare form -- no /pages/ segment, the page id only in the query. docs/commands.md
        // lists it as a supported shape and ConfluenceClient.resolvePageId reads pageId from the
        // query before it looks at the path at all, so the resolver always handled it; only the
        // classifier did not, and the symptom is the least helpful possible: sdd reports the URL
        // as a missing markdown FILE.
        assertThat(SpecSources.classify("https://confluence.corp.local/viewpage.action?pageId=65601"))
                .isEqualTo(SpecRefKind.CONFLUENCE_PAGE);
        // Under a context path too.
        assertThat(SpecSources.classify(
                "https://wiki.corp.local/confluence/viewpage.action?pageId=65601"))
                .isEqualTo(SpecRefKind.CONFLUENCE_PAGE);
    }

    @Test
    void aPageIdQueryParameterAloneIsEnough() {
        assertThat(SpecSources.classify("https://confluence.corp.local/spaces/x?pageId=42"))
                .isEqualTo(SpecRefKind.CONFLUENCE_PAGE);
        // ...but a parameter that merely ends in the same letters is not it.
        assertThat(SpecSources.classify("https://example.com/thing?notapageId=42"))
                .isEqualTo(SpecRefKind.MARKDOWN);
    }

    @Test
    void unrecognisedRefsAreMarkdown() {
        assertThat(SpecSources.classify("spec.md")).isEqualTo(SpecRefKind.MARKDOWN);
        assertThat(SpecSources.classify("loyalty.spec.md")).isEqualTo(SpecRefKind.MARKDOWN);
        assertThat(SpecSources.classify("https://example.com/some/other/path"))
                .isEqualTo(SpecRefKind.MARKDOWN);
        assertThat(SpecSources.classify("not a url at all")).isEqualTo(SpecRefKind.MARKDOWN);
    }

    @Test
    void isConfluenceExportDelegatesToClassify() {
        assertThat(SpecSources.isConfluenceExport("page.html")).isTrue();
        assertThat(SpecSources.isConfluenceExport("spec.md")).isFalse();
        assertThat(SpecSources.isConfluenceExport("PROJ-123")).isFalse();
    }
}
