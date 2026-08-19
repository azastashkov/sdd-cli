package sdd.plan.spec;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpecRendererTest {

    @Test
    void canonicalFileReRendersByteIdentically() throws Exception {
        String canonical = new String(SpecRendererTest.class
                .getResourceAsStream("/spec/canonical.md").readAllBytes(), StandardCharsets.UTF_8);
        assertThat(SpecRenderer.render(SpecParser.parse(canonical))).isEqualTo(canonical);
    }

    @Test
    void parseOfRenderIsIdentityForMinimalSpec() {
        NormalizedSpec spec = new NormalizedSpec("S-1", "T", "o", "draft", "G.", "",
                List.of(new SpecItem("R1", "req")), List.of(new SpecItem("A1", "acc")),
                List.of(), List.of(), List.of(), List.of(), List.of());
        assertThat(SpecParser.parse(SpecRenderer.render(spec))).isEqualTo(spec);
    }

    @Test
    void parseOfRenderIsIdentityForTrickyScalars() {
        NormalizedSpec spec = new NormalizedSpec("S-2", "Bob's launch: v2 #final", "o", "draft",
                "Line one.\n\nLine two.", "Some\ncontext.",
                List.of(new SpecItem("R1", "req")), List.of(new SpecItem("A1", "acc")),
                List.of(new SpecItem("C1", "constraint")),
                List.of(new Touchpoint(Touchpoint.Kind.TOPIC, "orders.events")),
                List.of("out"), List.of(new SpecItem("Q1", "q?")), List.of("a.png"));
        assertThat(SpecParser.parse(SpecRenderer.render(spec))).isEqualTo(spec);
    }

    @Test
    void parseOfRenderIsIdentityWithSources() {
        // the 14-argument shape — round trip must hold with a populated Sources section too,
        // not just via the delegating 13-argument overload the other tests exercise
        NormalizedSpec spec = new NormalizedSpec("S-4", "T", "o", "draft", "G.", "",
                List.of(new SpecItem("R1", "req")), List.of(new SpecItem("A1", "acc")),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of("jira PROJ-123 updated 2026-08-16T09:12:00Z https://jira.corp.local/browse/PROJ-123",
                        "confluence 65601 v7 \"Order API spec\" "
                                + "https://confluence.corp.local/pages/viewpage.action?pageId=65601"));
        String rendered = SpecRenderer.render(spec);
        assertThat(rendered).contains("## Sources\n"
                + "- jira PROJ-123 updated 2026-08-16T09:12:00Z https://jira.corp.local/browse/PROJ-123\n"
                + "- confluence 65601 v7 \"Order API spec\" "
                + "https://confluence.corp.local/pages/viewpage.action?pageId=65601\n");
        assertThat(SpecParser.parse(rendered)).isEqualTo(spec);
    }

    @Test
    void parseOfRenderIsIdentityWithEvidence() {
        // Evidence is where a concept the touchpoint grammar cannot express lands — a Redis
        // channel, a table, a dashboard — carried as prose plus the file:line it was read from.
        NormalizedSpec spec = new NormalizedSpec("S-5", "T", "o", "draft", "G.", "",
                List.of(new SpecItem("R1", "req")), List.of(new SpecItem("A1", "acc")),
                List.of(), List.of(), List.of("redis channel `quotes.v1.spread` published here "
                        + "— trading-pricing/src/main/java/com/acme/QuotePublisher.java:88"),
                List.of(), List.of(), List.of(), List.of());

        String rendered = SpecRenderer.render(spec);

        assertThat(rendered).contains("## Evidence\n- redis channel `quotes.v1.spread` published "
                + "here — trading-pricing/src/main/java/com/acme/QuotePublisher.java:88\n");
        assertThat(SpecParser.parse(rendered)).isEqualTo(spec);
    }

    @Test
    void aSpecWithoutEvidenceRendersNoEvidenceSection() {
        // The section must be invisible when unused, or every spec written before it existed
        // would re-render differently and plan.md's hash would move for no reason.
        NormalizedSpec spec = new NormalizedSpec("S-6", "T", "o", "draft", "G.", "",
                List.of(new SpecItem("R1", "req")), List.of(new SpecItem("A1", "acc")),
                List.of(), List.of(), List.of(), List.of(), List.of());

        assertThat(SpecRenderer.render(spec)).doesNotContain("## Evidence");
    }

    @Test
    void yamlTrapScalarsAreQuotedAndRoundTrip() {
        // bare 'no'/'123'/'2026-08-11' would be resolved by SnakeYAML to Boolean/Integer/Date —
        // the renderer must quote anything that does not read back as the identical string
        for (String trap : List.of("no", "yes", "on", "true", "null", "123", "1.10", "0x1A", "2026-08-11")) {
            NormalizedSpec spec = new NormalizedSpec(trap, trap, trap, trap, "G.", "",
                    List.of(new SpecItem("R1", "r")), List.of(new SpecItem("A1", "a")),
                    List.of(), List.of(), List.of(), List.of(), List.of());
            assertThat(SpecParser.parse(SpecRenderer.render(spec))).as(trap).isEqualTo(spec);
        }
    }

    @Test
    void requiredSectionsRenderEvenWhenEmptySoIncompleteSpecsRoundTrip() {
        NormalizedSpec incomplete = new NormalizedSpec("S-3", "T", "unknown", "draft", "", "",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        String rendered = SpecRenderer.render(incomplete);
        assertThat(rendered).contains("## Goal").contains("## Requirements")
                .contains("## Acceptance Criteria")
                .doesNotContain("## Background").doesNotContain("## Touchpoints");
        assertThat(SpecParser.parse(rendered)).isEqualTo(incomplete);
    }
}
