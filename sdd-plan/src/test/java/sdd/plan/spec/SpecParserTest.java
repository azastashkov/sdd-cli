package sdd.plan.spec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpecParserTest {

    static final String FULL = """
            ---
            id: SPEC-9
            title: 'Loyalty tiers: phase one'
            owner: ana
            status: draft
            ---

            ## Goal
            Add loyalty tiers to pricing.

            Gold customers get the discounted rate.

            ## Background
            Pricing today is flat per SKU.

            ## Requirements
            - R1: Price response includes the customer tier.
            - R2: Tier rules load from configuration.

            ## Acceptance Criteria
            - A1: GET /price returns tier for gold customers.

            ## Constraints
            - C1: No schema change to the pricing database.

            ## Touchpoints
            - repo: svc-pricing
            - endpoint: GET /price

            ## Out of Scope
            - Loyalty point accrual

            ## Open Questions
            - Q1: Which service owns tier configuration?

            ## Attachments
            - tier-diagram.png

            ## Sources
            - jira PROJ-123 updated 2026-08-16T09:12:00Z https://jira.corp.local/browse/PROJ-123
            - confluence 65601 v7 "Order API spec" https://confluence.corp.local/pages/viewpage.action?pageId=65601
            """;

    @Test
    void parsesEverySectionOfTheCanonicalFormat() {
        NormalizedSpec spec = SpecParser.parse(FULL);

        assertThat(spec.id()).isEqualTo("SPEC-9");
        assertThat(spec.title()).isEqualTo("Loyalty tiers: phase one");
        assertThat(spec.owner()).isEqualTo("ana");
        assertThat(spec.status()).isEqualTo("draft");
        assertThat(spec.goal()).isEqualTo(
                "Add loyalty tiers to pricing.\n\nGold customers get the discounted rate.");
        assertThat(spec.background()).isEqualTo("Pricing today is flat per SKU.");
        assertThat(spec.requirements()).containsExactly(
                new SpecItem("R1", "Price response includes the customer tier."),
                new SpecItem("R2", "Tier rules load from configuration."));
        assertThat(spec.acceptance()).containsExactly(
                new SpecItem("A1", "GET /price returns tier for gold customers."));
        assertThat(spec.constraints()).containsExactly(
                new SpecItem("C1", "No schema change to the pricing database."));
        assertThat(spec.touchpoints()).containsExactly(
                new Touchpoint(Touchpoint.Kind.REPO, "svc-pricing"),
                new Touchpoint(Touchpoint.Kind.ENDPOINT, "GET /price"));
        assertThat(spec.outOfScope()).containsExactly("Loyalty point accrual");
        assertThat(spec.openQuestions()).containsExactly(
                new SpecItem("Q1", "Which service owns tier configuration?"));
        assertThat(spec.attachments()).containsExactly("tier-diagram.png");
        assertThat(spec.sources()).containsExactly(
                "jira PROJ-123 updated 2026-08-16T09:12:00Z https://jira.corp.local/browse/PROJ-123",
                "confluence 65601 v7 \"Order API spec\" "
                        + "https://confluence.corp.local/pages/viewpage.action?pageId=65601");
    }

    @Test
    void minimalSpecParsesWithEmptyOptionals() {
        NormalizedSpec spec = SpecParser.parse("""
                ---
                id: S-1
                title: T
                owner: o
                status: draft
                ---

                ## Goal
                G.

                ## Requirements
                - R1: req

                ## Acceptance Criteria
                - A1: acc
                """);
        assertThat(spec.background()).isEmpty();
        assertThat(spec.constraints()).isEmpty();
        assertThat(spec.touchpoints()).isEmpty();
        assertThat(spec.attachments()).isEmpty();
        assertThat(spec.sources()).isEmpty();
    }

    @Test
    void missingFrontMatterFailsAtLineOne() {
        assertThatThrownBy(() -> SpecParser.parse("## Goal\nG\n"))
                .isInstanceOf(SpecParseException.class)
                .hasMessageStartingWith("line 1: spec must start with '---'");
    }

    @Test
    void unknownFrontMatterKeyAndMissingKeyFail() {
        assertThatThrownBy(() -> SpecParser.parse("""
                ---
                id: S-1
                title: T
                owner: o
                status: draft
                priority: high
                ---
                """))
                .isInstanceOf(SpecParseException.class)
                .hasMessageContaining("unknown front matter key 'priority'");
        assertThatThrownBy(() -> SpecParser.parse("""
                ---
                id: S-1
                title: T
                owner: o
                ---
                """))
                .isInstanceOf(SpecParseException.class)
                .hasMessageContaining("front matter is missing 'status'");
    }

    @Test
    void unknownAndOutOfOrderSectionsFailWithLineNumbers() {
        String unknown = """
                ---
                id: S-1
                title: T
                owner: o
                status: draft
                ---

                ## Storyline
                x
                """;
        assertThatThrownBy(() -> SpecParser.parse(unknown))
                .isInstanceOf(SpecParseException.class)
                .hasMessageStartingWith("line 8: unknown section '## Storyline'");

        String outOfOrder = """
                ---
                id: S-1
                title: T
                owner: o
                status: draft
                ---

                ## Requirements
                - R1: req

                ## Goal
                G.
                """;
        assertThatThrownBy(() -> SpecParser.parse(outOfOrder))
                .isInstanceOf(SpecParseException.class)
                .hasMessageStartingWith("line 11: section 'Goal' is duplicated or out of canonical order");
    }

    @Test
    void malformedBulletsFailWithTheExpectedShape() {
        String badItem = """
                ---
                id: S-1
                title: T
                owner: o
                status: draft
                ---

                ## Goal
                G.

                ## Requirements
                - X1: wrong prefix
                """;
        assertThatThrownBy(() -> SpecParser.parse(badItem))
                .isInstanceOf(SpecParseException.class)
                .hasMessageStartingWith("line 12: Requirements items must look like '- R1: <text>'");

        String badTouchpoint = """
                ---
                id: S-1
                title: T
                owner: o
                status: draft
                ---

                ## Goal
                G.

                ## Requirements
                - R1: req

                ## Acceptance Criteria
                - A1: acc

                ## Touchpoints
                - service: svc-pricing
                """;
        assertThatThrownBy(() -> SpecParser.parse(badTouchpoint))
                .isInstanceOf(SpecParseException.class)
                .hasMessageStartingWith("line 18: Touchpoints items must look like '- repo: <value>'")
                .hasMessageContaining("repo, endpoint, topic, class, artifact");
    }

    @Test
    void crlfInputParsesIdenticallyToLf() {
        // regression pin, not a TDD RED step — String.lines() already handles \r\n; this
        // keeps a future refactor to split("\n") from breaking Windows-edited gate files
        assertThat(SpecParser.parse(FULL.replace("\n", "\r\n"))).isEqualTo(SpecParser.parse(FULL));
    }

    @Test
    void contentBeforeFirstSectionAndMissingRequiredSectionFail() {
        assertThatThrownBy(() -> SpecParser.parse("""
                ---
                id: S-1
                title: T
                owner: o
                status: draft
                ---
                stray prose
                """))
                .isInstanceOf(SpecParseException.class)
                .hasMessageStartingWith("line 7: content before the first '## ' section heading");

        assertThatThrownBy(() -> SpecParser.parse("""
                ---
                id: S-1
                title: T
                owner: o
                status: draft
                ---

                ## Goal
                G.

                ## Requirements
                - R1: req
                """))
                .isInstanceOf(SpecParseException.class)
                .hasMessageContaining("missing required section '## Acceptance Criteria'");
    }
}
