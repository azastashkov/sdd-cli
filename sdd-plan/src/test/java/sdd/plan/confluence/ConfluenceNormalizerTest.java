package sdd.plan.confluence;

import org.junit.jupiter.api.Test;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.Usage;
import sdd.core.testing.ScriptedChatModel;
import sdd.plan.source.SourceBundle;
import sdd.plan.source.SourceDoc;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;
import sdd.plan.spec.Touchpoint;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfluenceNormalizerTest {

    private static SourceBundle bundleOf(String text, List<String> attachments) {
        return new SourceBundle(List.of(new SourceDoc(SourceDoc.Kind.CONFLUENCE_PAGE, "doc-1",
                null, null, null, text, attachments)), List.of());
    }

    private static final SourceBundle BUNDLE = bundleOf("# Loyalty\nWe want tiers.", List.of("tiers.png"));

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

        NormalizedSpec spec = ConfluenceNormalizer.normalize(BUNDLE, planner, "deepseek-v4-flash",
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

        // prompt shape: single-doc system prompt (no multi-source addendum — see the dedicated
        // byte-identical test below) + the extracted text; planner maxTokens passed through
        assertThat(planner.requests()).hasSize(1);
        assertThat(planner.requests().get(0).messages().get(0).content())
                .isEqualTo(ConfluenceNormalizer.SYSTEM_PROMPT_BASE);
        assertThat(planner.requests().get(0).messages().get(1).content())
                .contains("We want tiers.");
        assertThat(planner.requests().get(0).maxTokens()).isEqualTo(16384);
    }

    @Test
    void aOneDocumentBundleYieldsExactlyTheBasePrePreTask3Prompt() {
        // Gate review I2: with no atlassian: block at all, sdd plan <export>.html is a pre-existing
        // path whose behaviour must not change — the planner must see byte-identical input to what
        // it saw before Task 3 introduced SourceBundle. This test pins that against a hardcoded
        // copy of the ORIGINAL (pre-Task-3) system prompt, not against ConfluenceNormalizer's own
        // constant — comparing against the class's own field would not catch a regression that
        // changed both together.
        String originalSystemPrompt = """
                You convert one raw feature-specification document into strict JSON for a \
                spec-driven development pipeline. Return exactly ONE JSON object - no markdown \
                fences, no commentary - with exactly these fields:
                {"title": string, "owner": string, "status": string, "goal": string,
                 "background": string, "requirements": [string, ...], "acceptance": [string, ...],
                 "constraints": [string, ...],
                 "touchpoints": [{"kind": "repo"|"endpoint"|"topic"|"class"|"artifact", "value": string}, ...],
                 "out_of_scope": [string, ...], "open_questions": [string, ...], "unmapped": [string, ...]}
                Rules:
                - Use only information present in the document. Never invent requirements.
                - "requirements" are behaviours to build; "acceptance" are checks that prove them.
                - "goal" is 1-3 sentences; longer context belongs in "background".
                - Use "" for owner/status when the document does not state them.
                - Anything you cannot confidently place goes into "unmapped" verbatim.
                """;
        SourceBundle bundle = bundleOf("# Loyalty\nWe want tiers.", List.of("tiers.png", "tiers.png"));
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response(GOOD_JSON, "stop")));

        NormalizedSpec spec = ConfluenceNormalizer.normalize(bundle, planner, "m", 100, "id");

        assertThat(planner.requests().get(0).messages().get(0).content()).isEqualTo(originalSystemPrompt);
        // Bare text, no "## Source 1: ..." header — exactly extracted.text() as the base path sent.
        assertThat(planner.requests().get(0).messages().get(1).content()).isEqualTo("# Loyalty\nWe want tiers.");
        // Mapped-through, not de-duplicated — the base path never merged attachments across
        // documents because there was never more than one to merge.
        assertThat(spec.attachments()).containsExactly("tiers.png", "tiers.png");
    }

    @Test
    void fencedJsonIsAccepted() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(
                response("```json\n" + GOOD_JSON + "\n```", "stop")));
        NormalizedSpec spec = ConfluenceNormalizer.normalize(BUNDLE, planner, "m", 100, "id");
        assertThat(spec.requirements()).hasSize(2);
    }

    @Test
    void truncatedResponseFailsExplicitly() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response("{", "length")));
        assertThatThrownBy(() -> ConfluenceNormalizer.normalize(BUNDLE, planner, "m", 100, "id"))
                .isInstanceOf(SpecNormalizationException.class)
                .hasMessageContaining("finish_reason=length");
    }

    @Test
    void malformedJsonFailsWithSnippet() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response("not json at all", "stop")));
        assertThatThrownBy(() -> ConfluenceNormalizer.normalize(BUNDLE, planner, "m", 100, "id"))
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

        NormalizedSpec spec = ConfluenceNormalizer.normalize(BUNDLE, planner, "m", 100, "id");

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

        NormalizedSpec spec = ConfluenceNormalizer.normalize(BUNDLE, planner, "m", 100, "id");

        assertThat(spec.openQuestions()).containsExactly(
                new SpecItem("Q1", "[unmapped touchpoint] weird kind: x"));
        assertThat(sdd.plan.spec.SpecParser.parse(sdd.plan.spec.SpecRenderer.render(spec)))
                .isEqualTo(spec);
    }


    @Test
    void unicodeLineSeparatorsAreCollapsedEverywhere() {
        // U+2028 LINE SEPARATOR, U+2029 PARAGRAPH SEPARATOR, U+0085 NEXT LINE — written in the
        // JSON below as Java unicode escapes (translated to the real code points at compile time)
        // so the test cannot be silently neutered by copy/paste normalization of invisible
        // characters. Spell them U+xxxx in comments — the compiler translates escape forms there too.
        String json = """
                {"title": "T\u2028sub", "owner": "", "status": "", "goal": "G.",
                 "background": "", "requirements": ["a\u2029b", "c\u0085d"],
                 "acceptance": ["ok"], "constraints": [], "touchpoints": [],
                 "out_of_scope": [], "open_questions": [], "unmapped": []}""";
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response(json, "stop")));

        NormalizedSpec spec = ConfluenceNormalizer.normalize(BUNDLE, planner, "m", 100, "id");

        assertThat(spec.title()).isEqualTo("T sub");
        assertThat(spec.requirements()).containsExactly(
                new SpecItem("R1", "a b"), new SpecItem("R2", "c d"));
        assertThat(sdd.plan.spec.SpecParser.parse(sdd.plan.spec.SpecRenderer.render(spec))).isEqualTo(spec);
    }

    @Test
    void attachmentNamesAreSanitizedBeforeEnteringTheSpec() {
        SourceBundle withBadName = bundleOf("text", List.of("dia\ngram.png", "ok.png"));
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response(GOOD_JSON, "stop")));

        NormalizedSpec spec = ConfluenceNormalizer.normalize(withBadName, planner, "m", 100, "id");

        assertThat(spec.attachments()).containsExactly("dia gram.png", "ok.png");
        assertThat(sdd.plan.spec.SpecParser.parse(sdd.plan.spec.SpecRenderer.render(spec)).attachments())
                .containsExactly("dia gram.png", "ok.png");
    }

    @Test
    void multiDocBundleRendersOneHeaderPerDocumentOmittingTheUrlWhenAbsent() {
        SourceBundle bundle = new SourceBundle(List.of(
                new SourceDoc(SourceDoc.Kind.JIRA_ISSUE, "PROJ-123",
                        "https://jira.corp.local/browse/PROJ-123", "Loyalty tiers", null,
                        "Jira says tiers.", List.of()),
                new SourceDoc(SourceDoc.Kind.FREE_TEXT, "text-1", null, null, null,
                        "Operator free text.", List.of())),
                List.of());
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response(GOOD_JSON, "stop")));

        ConfluenceNormalizer.normalize(bundle, planner, "m", 100, "id");

        String userMessage = planner.requests().get(0).messages().get(1).content();
        assertThat(userMessage).contains(
                "## Source 1: Loyalty tiers (https://jira.corp.local/browse/PROJ-123)\n"
                        + "Jira says tiers.");
        assertThat(userMessage).contains("## Source 2: text-1\nOperator free text.");
    }

    @Test
    void bundleNotesBecomeSourcePrefixedOpenQuestionsAfterUnmapped() {
        SourceBundle bundle = new SourceBundle(
                List.of(new SourceDoc(SourceDoc.Kind.FREE_TEXT, "text-1", null, null, null,
                        "text", List.of())),
                List.of("Confluence page conflicts with Jira description on rollout date"));
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response(GOOD_JSON, "stop")));

        NormalizedSpec spec = ConfluenceNormalizer.normalize(bundle, planner, "m", 100, "id");

        assertThat(spec.openQuestions()).containsExactly(
                new SpecItem("Q1", "Who owns tier config?"),
                new SpecItem("Q2", "[unmapped] Rollout percentage table"),
                new SpecItem("Q3", "[unmapped touchpoint] service: bogus"),
                new SpecItem("Q4", "[source] Confluence page conflicts with Jira description on rollout date"));
    }

    @Test
    void attachmentsAreTheOrderPreservingDeduplicatedUnionAcrossDocuments() {
        SourceBundle bundle = new SourceBundle(List.of(
                new SourceDoc(SourceDoc.Kind.CONFLUENCE_PAGE, "doc-1", null, "Page one", null,
                        "text one", List.of("a.png", "b.png")),
                new SourceDoc(SourceDoc.Kind.CONFLUENCE_PAGE, "doc-2", null, "Page two", null,
                        "text two", List.of("b.png", "c.png"))),
                List.of());
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response(GOOD_JSON, "stop")));

        NormalizedSpec spec = ConfluenceNormalizer.normalize(bundle, planner, "m", 100, "id");

        assertThat(spec.attachments()).containsExactly("a.png", "b.png", "c.png");
    }

    @Test
    void budgetDropNotesFlowIntoOpenQuestionsAsSourceNotes() {
        // two documents whose combined text exceeds the 300_000-char bundle budget: the
        // lower-priority JIRA_COMMENT must be dropped whole and its drop noted, and that note
        // must reach Open Questions exactly like any other bundle note
        SourceDoc kept = new SourceDoc(SourceDoc.Kind.FREE_TEXT, "text-1", null, "Kept doc", null,
                "x".repeat(200_000), List.of());
        SourceDoc dropped = new SourceDoc(SourceDoc.Kind.JIRA_COMMENT, "j1",
                "https://jira.corp.local/browse/PROJ-123", "Old comment", null,
                "x".repeat(150_000), List.of());
        SourceBundle bundle = new SourceBundle(List.of(kept, dropped), List.of());
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response(GOOD_JSON, "stop")));

        NormalizedSpec spec = ConfluenceNormalizer.normalize(bundle, planner, "m", 100, "id");

        assertThat(spec.openQuestions()).anySatisfy(q ->
                assertThat(q.text()).startsWith("[source] dropped for budget: Old comment"));
        String userMessage = planner.requests().get(0).messages().get(1).content();
        assertThat(userMessage).doesNotContain("Old comment");
    }

    @Test
    void anOversizedSingleDocumentFailsLoudlyInsteadOfNormalizingAnEmptyBundle() {
        // a FREE_TEXT document alone over the 300_000-char budget must never be silently
        // dropped down to an empty bundle and sent to the model as an empty prompt — normalize
        // must fail before the planner is ever called
        SourceDoc oversized = new SourceDoc(SourceDoc.Kind.FREE_TEXT, "text-1", null,
                "Huge requirement", null, "x".repeat(300_001), List.of());
        SourceBundle bundle = new SourceBundle(List.of(oversized), List.of());
        ScriptedChatModel planner = new ScriptedChatModel(List.of());

        assertThatThrownBy(() -> ConfluenceNormalizer.normalize(bundle, planner, "m", 100, "id"))
                .isInstanceOf(SpecNormalizationException.class)
                .hasMessageContaining("Huge requirement");
        assertThat(planner.requests()).isEmpty();
    }

    @Test
    void systemPromptStatesConfluenceWinsOverJiraOnConflict() {
        assertThat(ConfluenceNormalizer.SYSTEM_PROMPT).contains("Confluence").contains("Jira")
                .contains("unmapped");
    }
}
