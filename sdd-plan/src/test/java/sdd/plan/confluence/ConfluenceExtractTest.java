package sdd.plan.confluence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfluenceExtractTest {

    @Test
    void storageFormatHeadingsListsTablesImagesAndCodeBecomeMarkdownishText() {
        String storage = """
                <h1>Loyalty tiers</h1>
                <p>We want tiered pricing. <ac:image><ri:attachment ri:filename="tiers.png"/></ac:image></p>
                <ul><li>gold</li><li>silver</li></ul>
                <table>
                  <tr><th>Tier</th><th>Discount</th></tr>
                  <tr><td>gold</td><td>10%</td></tr>
                  <tr><td>si|lver</td><td>5%</td></tr>
                </table>
                <ac:structured-macro ac:name="code"><ac:plain-text-body>GET /price</ac:plain-text-body></ac:structured-macro>
                """;

        ConfluenceExtract.Extracted extracted = ConfluenceExtract.extract(storage);

        assertThat(extracted.text()).contains("# Loyalty tiers");
        assertThat(extracted.text()).contains("We want tiered pricing.").contains("[attachment: tiers.png]");
        assertThat(extracted.text()).contains("- gold").contains("- silver");
        assertThat(extracted.text()).contains("| Tier | Discount |")
                .contains("| --- | --- |")
                .contains("| gold | 10% |")
                .contains("| si\\|lver | 5% |");
        assertThat(extracted.text()).contains("```\nGET /price\n```");
        assertThat(extracted.attachments()).containsExactly("tiers.png");
    }

    @Test
    void exportedHtmlImagesAreCollectedByFileNameOnceEach() {
        String html = """
                <html><body>
                <h2>Design</h2>
                <p><img src="attachments/123/diagram.png"></p>
                <p><img src="attachments/123/diagram.png"></p>
                <div><p>Nested prose survives.</p></div>
                </body></html>
                """;

        ConfluenceExtract.Extracted extracted = ConfluenceExtract.extract(html);

        assertThat(extracted.text()).contains("## Design").contains("[attachment: diagram.png]")
                .contains("Nested prose survives.");
        assertThat(extracted.attachments()).containsExactly("diagram.png");
    }

    @Test
    void cdataCodeBodiesSurvive() {
        // real storage-format exports wrap code in CDATA; jsoup's HTML parser turns the CDATA
        // section into a bogus Comment node — the extractor must recover it
        String storage = "<ac:structured-macro ac:name=\"code\">"
                + "<ac:plain-text-body><![CDATA[GET /price?tier=gold]]></ac:plain-text-body>"
                + "</ac:structured-macro>";

        ConfluenceExtract.Extracted extracted = ConfluenceExtract.extract(storage);

        assertThat(extracted.text()).contains("```\nGET /price?tier=gold\n```");
    }

    @Test
    void bareTextPreBlocksAndInlineWrappersAreNotLost() {
        String html = """
                <div>Intro prose outside any paragraph.<p>Para.</p><span>Trailing <b>note</b>.</span></div>
                <pre>curl -s /price</pre>
                """;

        ConfluenceExtract.Extracted extracted = ConfluenceExtract.extract(html);

        assertThat(extracted.text()).contains("Intro prose outside any paragraph.")
                .contains("Para.")
                .contains("Trailing note.")
                .contains("```\ncurl -s /price\n```");
    }

    @Test
    void oversizeExtractionFailsLoudly() {
        String huge = "<p>" + "x".repeat(ConfluenceExtract.MAX_TEXT_CHARS + 100) + "</p>";
        assertThatThrownBy(() -> ConfluenceExtract.extract(huge))
                .isInstanceOf(SpecNormalizationException.class)
                .hasMessageContaining("too large");
    }
}
