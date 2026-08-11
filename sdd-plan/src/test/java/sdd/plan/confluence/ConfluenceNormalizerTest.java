package sdd.plan.confluence;

import org.junit.jupiter.api.Test;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.Usage;
import sdd.core.testing.ScriptedChatModel;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;
import sdd.plan.spec.Touchpoint;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfluenceNormalizerTest {

    private static final ConfluenceExtract.Extracted EXTRACTED =
            new ConfluenceExtract.Extracted("# Loyalty\nWe want tiers.", List.of("tiers.png"));

    private static ChatResponse response(String content, String finishReason) {
        return new ChatResponse(ChatMessage.assistant(content), finishReason, new Usage(10, 10));
    }

    private static final String GOOD_JSON = """
            {"title": "Loyalty tiers", "owner": "", "status": "", "goal": "Add tiers.",
             "background": "Flat pricing today.",
             "requirements": ["Price response includes tier", "Tier rules configurable"],
             "acceptance": ["GET /price returns tier"],
             "constraints": ["No pricing schema change"],
             "touchpoints": [{"kind": "repo", "value": "svc-pricing"},
                             {"kind": "service", "value": "bogus"}],
             "out_of_scope": ["Point accrual"],
             "open_questions": ["Who owns tier config?"],
             "unmapped": ["Rollout percentage table"]}""";

    @Test
    void assignsIdsCarriesAttachmentsAndDemotesUnmappedToOpenQuestions() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response(GOOD_JSON, "stop")));

        NormalizedSpec spec = ConfluenceNormalizer.normalize(EXTRACTED, planner, "deepseek-v4-flash",
                16384, "spec-loyalty-page");

        assertThat(spec.id()).isEqualTo("spec-loyalty-page");
        assertThat(spec.owner()).isEqualTo("unknown");
        assertThat(spec.status()).isEqualTo("draft");
        assertThat(spec.requirements()).containsExactly(
                new SpecItem("R1", "Price response includes tier"),
                new SpecItem("R2", "Tier rules configurable"));
        assertThat(spec.acceptance()).containsExactly(new SpecItem("A1", "GET /price returns tier"));
        assertThat(spec.constraints()).containsExactly(new SpecItem("C1", "No pricing schema change"));
        assertThat(spec.touchpoints()).containsExactly(
                new Touchpoint(Touchpoint.Kind.REPO, "svc-pricing"));
        assertThat(spec.openQuestions()).containsExactly(
                new SpecItem("Q1", "Who owns tier config?"),
                new SpecItem("Q2", "[unmapped] Rollout percentage table"),
                new SpecItem("Q3", "[unmapped touchpoint] service: bogus"));
        assertThat(spec.attachments()).containsExactly("tiers.png");

        // prompt shape: system prompt + the extracted text; planner maxTokens passed through
        assertThat(planner.requests()).hasSize(1);
        assertThat(planner.requests().get(0).messages().get(0).content())
                .isEqualTo(ConfluenceNormalizer.SYSTEM_PROMPT);
        assertThat(planner.requests().get(0).messages().get(1).content())
                .contains("We want tiers.");
        assertThat(planner.requests().get(0).maxTokens()).isEqualTo(16384);
    }

    @Test
    void fencedJsonIsAccepted() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(
                response("```json\n" + GOOD_JSON + "\n```", "stop")));
        NormalizedSpec spec = ConfluenceNormalizer.normalize(EXTRACTED, planner, "m", 100, "id");
        assertThat(spec.requirements()).hasSize(2);
    }

    @Test
    void truncatedResponseFailsExplicitly() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response("{", "length")));
        assertThatThrownBy(() -> ConfluenceNormalizer.normalize(EXTRACTED, planner, "m", 100, "id"))
                .isInstanceOf(SpecNormalizationException.class)
                .hasMessageContaining("finish_reason=length");
    }

    @Test
    void malformedJsonFailsWithSnippet() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response("not json at all", "stop")));
        assertThatThrownBy(() -> ConfluenceNormalizer.normalize(EXTRACTED, planner, "m", 100, "id"))
                .isInstanceOf(SpecNormalizationException.class)
                .hasMessageContaining("not valid JSON")
                .hasMessageContaining("not json at all");
    }

    @Test
    void modelWhitespaceAndHeadingMarkersAreSanitizedSoTheSpecReparses() {
        // a multi-line requirement or a goal quoting a markdown heading must not produce a
        // gate file that SpecParser later rejects
        String json = """
                {"title": "T", "owner": "", "status": "", "goal": "# Big plan\\nAdd tiers.",
                 "background": "", "requirements": ["line one\\nline two"],
                 "acceptance": ["ok"], "constraints": [], "touchpoints": [],
                 "out_of_scope": [], "open_questions": [], "unmapped": []}""";
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response(json, "stop")));

        NormalizedSpec spec = ConfluenceNormalizer.normalize(EXTRACTED, planner, "m", 100, "id");

        assertThat(spec.goal()).isEqualTo("Big plan\nAdd tiers.");
        assertThat(spec.requirements()).containsExactly(new SpecItem("R1", "line one line two"));
        assertThat(sdd.plan.spec.SpecParser.parse(sdd.plan.spec.SpecRenderer.render(spec)))
                .isEqualTo(spec);
    }

    @Test
    void unmappedTouchpointKindIsSanitizedSoTheSpecReparses() {
        // an embedded newline in the model-supplied kind must not survive into the rendered
        // gate file as a raw newline inside a bullet — SpecRenderer would emit a multi-line
        // bullet that SpecParser then rejects
        String json = """
                {"title": "T", "owner": "", "status": "", "goal": "G", "background": "",
                 "requirements": ["r"], "acceptance": ["a"], "constraints": [],
                 "touchpoints": [{"kind": "weird\\nkind", "value": "x"}],
                 "out_of_scope": [], "open_questions": [], "unmapped": []}""";
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response(json, "stop")));

        NormalizedSpec spec = ConfluenceNormalizer.normalize(EXTRACTED, planner, "m", 100, "id");

        assertThat(spec.openQuestions()).containsExactly(
                new SpecItem("Q1", "[unmapped touchpoint] weird kind: x"));
        assertThat(sdd.plan.spec.SpecParser.parse(sdd.plan.spec.SpecRenderer.render(spec)))
                .isEqualTo(spec);
    }
}
