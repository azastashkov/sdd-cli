package sdd.cli.explain;

import org.junit.jupiter.api.Test;
import sdd.core.kb.EntityKind;
import sdd.core.kb.Provenance;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EvidenceRenderer} is a pure function over Task 4's records — no {@code Jdbi}, no
 * database, so every case here builds {@link Evidence} directly rather than through a fixture.
 */
class EvidenceRendererTest {

    private static final Provenance PROVENANCE =
            new Provenance(6, "2026-08-01T00:00:00Z", "2026-08-14T00:00:00Z");

    private static RetrievalRequest request(List<EntityRef> entities, List<String> notes) {
        return new RetrievalRequest(Intent.DESCRIBE, entities, List.of(), "What is svc-orders?",
                notes, false);
    }

    private static Evidence evidence(RetrievalRequest request, List<Section> sections, List<String> caveats) {
        return new Evidence(PROVENANCE, request, sections, caveats);
    }

    @Test
    void zeroFactSectionsAreOmittedEntirely() {
        Section empty = Section.of("Top API types: svc-orders", "java_type", List.of());
        Section populated = Section.of("Modules: svc-orders", "module", List.of(new Fact(":app (SERVICE)")));

        String out = EvidenceRenderer.render(evidence(request(List.of(), List.of()),
                List.of(empty, populated), List.of()));

        assertThat(out).doesNotContain("Top API types").doesNotContain("[java_type]");
        assertThat(out).contains("[module] Modules: svc-orders").contains(":app (SERVICE)");
    }

    @Test
    void provenanceIsRenderedAsATag() {
        String out = EvidenceRenderer.render(evidence(request(List.of(), List.of()), List.of(), List.of()));

        assertThat(out).contains("6 repos indexed")
                .contains("2026-08-01T00:00:00Z")
                .contains("2026-08-14T00:00:00Z");
    }

    @Test
    void interpretationAlwaysRendersRestatementIntentAndDroppedEntityReasons() {
        List<String> notes = List.of(
                "model referenced topic 'nope' — no topic named 'nope' in the estate — dropped");
        Evidence ev = evidence(request(List.of(), notes), List.of(), List.of());

        String out = EvidenceRenderer.render(ev);

        assertThat(out).contains("Interpreted as: What is svc-orders?");
        assertThat(out).contains("Intent: describe");
        assertThat(out).contains(
                "model referenced topic 'nope' — no topic named 'nope' in the estate — dropped");
    }

    @Test
    void interpretationSurvivesWhenEverythingElseIsEmpty() {
        Evidence ev = new Evidence(new Provenance(0, null, null),
                request(List.of(), List.of()), List.of(), List.of());

        String out = EvidenceRenderer.render(ev);

        assertThat(out).contains("Interpreted as: What is svc-orders?").contains("Intent: describe");
    }

    @Test
    void searchTermsLineIsOmittedOnANonSearchIntentSinceItNeverActuallySearched() {
        // QuestionInterpreter.fallback populates searchTerms regardless of intent (e.g. DESCRIBE,
        // once at least one entity is named), but EvidenceCollector.collect dispatches purely on
        // intent -- only Intent.SEARCH ever reads them. Printing "Search terms:" on a DESCRIBE
        // report tells the reader a search ran when it did not.
        RetrievalRequest req = new RetrievalRequest(Intent.DESCRIBE,
                List.of(new EntityRef(EntityKind.REPO, "svc-orders", false)),
                List.of("orders", "billing"), "What is svc-orders?", List.of(), true);
        Evidence ev = evidence(req, List.of(), List.of());

        String out = EvidenceRenderer.render(ev);

        assertThat(out).doesNotContain("Search terms:");
    }

    @Test
    void searchTermsLineStillRendersOnASearchIntent() {
        RetrievalRequest req = new RetrievalRequest(Intent.SEARCH, List.of(),
                List.of("orders", "billing"), "tell me about orders and billing", List.of(), false);
        Evidence ev = evidence(req, List.of(), List.of());

        String out = EvidenceRenderer.render(ev);

        assertThat(out).contains("Search terms:").contains("orders").contains("billing");
    }

    @Test
    void entitiesAreListedWithKindAndValue() {
        List<EntityRef> entities = List.of(new EntityRef(EntityKind.REPO, "svc-orders", false));
        Evidence ev = evidence(request(entities, List.of()), List.of(), List.of());

        String out = EvidenceRenderer.render(ev);

        assertThat(out).contains("repo 'svc-orders'");
    }

    @Test
    void dependencyPathEntitiesCarrySubjectObjectRoles() {
        RetrievalRequest req = new RetrievalRequest(Intent.DEPENDENCY_PATH,
                List.of(new EntityRef(EntityKind.REPO, "svc-orders", false),
                        new EntityRef(EntityKind.REPO, "lib-core", true)),
                List.of(), "Why does svc-orders depend on lib-core?", List.of(), false);
        Evidence ev = evidence(req, List.of(), List.of());

        String out = EvidenceRenderer.render(ev);

        assertThat(out).contains("repo 'svc-orders' (subject)").contains("repo 'lib-core' (object)");
    }

    @Test
    void describeEntitiesDoNotCarryARoleSuffix() {
        List<EntityRef> entities = List.of(new EntityRef(EntityKind.REPO, "svc-orders", false));
        Evidence ev = evidence(request(entities, List.of()), List.of(), List.of());

        String out = EvidenceRenderer.render(ev);

        assertThat(out).doesNotContain("(subject)").doesNotContain("(object)");
    }

    @Test
    void caveatsRenderedWhenNonEmptyAndOmittedWhenEmpty() {
        Evidence withCaveat = evidence(request(List.of(), List.of()), List.of(),
                List.of("nothing in the knowledge base asserts that no repo consumes svc-orders"));
        Evidence withoutCaveat = evidence(request(List.of(), List.of()), List.of(), List.of());

        assertThat(EvidenceRenderer.render(withCaveat))
                .contains("Caveats")
                .contains("nothing in the knowledge base asserts that no repo consumes svc-orders");
        assertThat(EvidenceRenderer.render(withoutCaveat)).doesNotContain("Caveats");
    }

    @Test
    void cardMdContainingALiteralFenceIsNeutralized() {
        Section card = Section.of("Summary: svc-orders", "repo_card", List.of(
                new Fact("card_md (model-generated summary, repo_card.card_md): "
                        + "```yaml\nweird: fence\n``` and more")));
        Evidence ev = evidence(request(List.of(), List.of()), List.of(card), List.of());

        String out = EvidenceRenderer.render(ev);

        assertThat(out).doesNotContain("```").contains("'''yaml").contains("''' and more");
    }

    @Test
    void sectionSourceContainingAFenceIsNeutralized() {
        // section.title() is sanitized at the render site but section.source() was interpolated
        // raw -- currently safe because every real caller passes a code literal, but the class
        // Javadoc states the neutralization guarantee with no carve-out, so this must hold too.
        Section section = new Section("Modules: svc-orders", "module```evil```",
                List.of(new Fact(":app (SERVICE)")), 1);
        Evidence ev = evidence(request(List.of(), List.of()), List.of(section), List.of());

        String out = EvidenceRenderer.render(ev);

        assertThat(out).doesNotContain("```").contains("module'''evil'''");
    }

    @Test
    void modelAuthoredRestatementAndNotesAreAlsoNeutralized() {
        RetrievalRequest req = new RetrievalRequest(Intent.SEARCH, List.of(), List.of(),
                "what about ```rm -rf /``` here", List.of("dropped ```evil``` entity"), false);
        Evidence ev = evidence(req, List.of(), List.of());

        String out = EvidenceRenderer.render(ev);

        assertThat(out).doesNotContain("```");
        assertThat(out).contains("'''rm -rf /'''").contains("'''evil'''");
    }

    @Test
    void sectionCappedMarkerSurvivesVerbatimIntoOutput() {
        List<Fact> facts = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            facts.add(new Fact("module-" + i));
        }
        Section capped = Section.capped("Modules: svc-orders", "module", facts, Section.DEFAULT_LIMIT);
        Evidence ev = evidence(request(List.of(), List.of()), List.of(capped), List.of());

        String out = EvidenceRenderer.render(ev);

        assertThat(out).contains("+5 more (showing 25 of 30)");
    }

    @Test
    void everySectionIsTitledWithItsKbSourceInBrackets() {
        Section section = Section.of("Endpoints: svc-orders", "rest_endpoint",
                List.of(new Fact("GET /orders/{id}")));
        Evidence ev = evidence(request(List.of(), List.of()), List.of(section), List.of());

        String out = EvidenceRenderer.render(ev);

        assertThat(out).contains("[rest_endpoint] Endpoints: svc-orders");
    }

    @Test
    void capTruncatesWholeSectionsNeverMidSectionAndStatesIt() {
        // Each section body is ~1000 chars; enough sections to blow well past EVIDENCE_CAP so the
        // whole-section-boundary cap logic has to kick in.
        List<Section> sections = new ArrayList<>();
        for (int s = 0; s < 20; s++) {
            List<Fact> facts = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                facts.add(new Fact("x".repeat(45) + "-" + s + "-" + i));
            }
            sections.add(Section.of("Section " + s, "module", facts));
        }
        Evidence ev = evidence(request(List.of(), List.of()), sections, List.of());

        String out = EvidenceRenderer.render(ev);

        assertThat(out).contains("more section").contains("omitted").contains("evidence capped at "
                + EvidenceRenderer.EVIDENCE_CAP);
        // The last mentioned "Section N" header found in the body must be immediately followed by
        // its own facts, not cut short — i.e. every "[module] Section N" that appears also has at
        // least one fact line for that same N before the next "### " heading.
        assertThat(out).contains("Section 0");
        // No section header appears without its content: for each included section index, its
        // marker text ("-N-") must also appear (proving the facts, not just the header, made it in).
        for (int s = 0; s < 20; s++) {
            boolean headerPresent = out.contains("[module] Section " + s + "\n");
            boolean factPresent = out.contains("-" + s + "-0");
            assertThat(headerPresent).as("section %d header/fact consistency", s).isEqualTo(factPresent);
        }
    }

    @Test
    void outputIsByteIdenticalForIdenticalInput() {
        // Two SEPARATELY-CONSTRUCTED, structurally-equal Evidence instances, not the same object
        // twice — the latter would pass even if render() took an identity-based shortcut (e.g.
        // memoizing on object identity) rather than actually being a pure function of content.
        String first = EvidenceRenderer.render(buildEquivalentEvidence());
        String second = EvidenceRenderer.render(buildEquivalentEvidence());

        assertThat(first).isEqualTo(second);
    }

    private static Evidence buildEquivalentEvidence() {
        Section section = Section.of("Modules: svc-orders", "module", List.of(new Fact(":app (SERVICE)")));
        return evidence(request(List.of(new EntityRef(EntityKind.REPO, "svc-orders", false)),
                List.of("a note")), List.of(section), List.of("a caveat"));
    }

    @Test
    void sectionTitleContainingAFenceIsNeutralized() {
        Section section = Section.of("Endpoint match is ambiguous: '```evil```'", "rest_endpoint",
                List.of(new Fact("GET /orders/{id}")));
        Evidence ev = evidence(request(List.of(), List.of()), List.of(section), List.of());

        String out = EvidenceRenderer.render(ev);

        assertThat(out).doesNotContain("```").contains("'''evil'''");
    }

    @Test
    void restatementLongerThanItsCapIsTruncatedWithAStatedMarker() {
        String huge = "x".repeat(1000);
        RetrievalRequest req = new RetrievalRequest(Intent.SEARCH, List.of(), List.of(), huge, List.of(), false);
        Evidence ev = evidence(req, List.of(), List.of());

        String out = EvidenceRenderer.render(ev);

        assertThat(out).doesNotContain(huge);
        assertThat(out).contains("[truncated to 500 of 1000 chars]");
        // The line is genuinely shorter, not just annotated as if it were.
        assertThat(out).doesNotContain("x".repeat(600));
    }

    @Test
    void aNoteLongerThanItsCapIsTruncatedWithAStatedMarker() {
        String hugeNote = "model referenced repo '" + "y".repeat(1000) + "' — dropped";
        RetrievalRequest req = new RetrievalRequest(Intent.SEARCH, List.of(), List.of(),
                "irrelevant", List.of(hugeNote), false);
        Evidence ev = evidence(req, List.of(), List.of());

        String out = EvidenceRenderer.render(ev);

        assertThat(out).doesNotContain(hugeNote);
        assertThat(out).contains("[truncated to 300 of " + hugeNote.length() + " chars]");
    }

    @Test
    void aSearchTermLongerThanItsCapIsTruncatedWithAStatedMarker() {
        // QuestionInterpreter.MAX_TERMS = 8 caps the COUNT of search terms; nothing upstream caps
        // the LENGTH of any one term (search_terms[i] is unvalidated model text, never checked
        // against KbEntities.resolve) -- this is the fourth path to the same defect already fixed
        // for restatement and notes.
        String hugeTerm = "z".repeat(1000);
        RetrievalRequest req = new RetrievalRequest(Intent.SEARCH, List.of(), List.of(hugeTerm, "short"),
                "irrelevant", List.of(), false);
        Evidence ev = evidence(req, List.of(), List.of());

        String out = EvidenceRenderer.render(ev);

        assertThat(out).doesNotContain(hugeTerm);
        assertThat(out).contains("[truncated to 100 of 1000 chars]");
        // The short term rode along unmodified, and both still ended up on the "Search terms:" line.
        assertThat(out).contains("Search terms:").contains("short");
    }

    @Test
    void aSurvivingEntityValueLongerThanItsCapIsTruncatedWithAStatedMarker() {
        // ENDPOINT, deliberately: Routes.normalize collapses any {...} segment (any length) to the
        // 2-char token "{}" before templatesMatch runs, so this value can legitimately pass
        // KbEntities.resolve against a real "GET /api/{id}" endpoint despite being enormous -- the
        // fifth path, and the one where resolving successfully is a red herring, not evidence of a
        // bounded value. QuestionInterpreter stores the RAW value on EntityRef, never the normalized
        // one, and EvidenceRenderer renders that raw value.
        String hugeSegment = "x".repeat(1000);
        String hugeEndpointValue = "GET /api/{" + hugeSegment + "}";
        RetrievalRequest req = new RetrievalRequest(Intent.DESCRIBE,
                List.of(new EntityRef(EntityKind.ENDPOINT, hugeEndpointValue, false)),
                List.of(), "What calls this endpoint?", List.of(), false);
        Evidence ev = evidence(req, List.of(), List.of());

        String out = EvidenceRenderer.render(ev);

        assertThat(out).doesNotContain(hugeSegment);
        assertThat(out).contains("[truncated to 200 of " + hugeEndpointValue.length() + " chars]");
        assertThat(out).contains("endpoint 'GET /api/{");
    }

    @Test
    void farMoreNotesThanTheLimitAreCappedByCountWithAStatedOmission() {
        // Mirrors QuestionInterpreter's actual failure mode: one drop-note per entity the model
        // named, appended BEFORE MAX_ENTITIES truncation runs -- so a response naming far more
        // entities than survive validation produces far more notes than EVIDENCE_CAP should have
        // to absorb. 500 short, well-under-NOTE_CAP notes: only the count cap should be at work.
        List<String> notes = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            notes.add("model referenced repo 'nope-" + i + "' — no repo named 'nope-" + i
                    + "' in the estate — dropped");
        }
        RetrievalRequest req = new RetrievalRequest(Intent.SEARCH, List.of(), List.of(),
                "irrelevant", notes, false);
        Evidence ev = evidence(req, List.of(), List.of());

        String out = EvidenceRenderer.render(ev);

        // The cap held: only the first 20 individual note lines made it in.
        assertThat(out).contains("nope-0'").contains("nope-19'");
        assertThat(out).doesNotContain("nope-20'").doesNotContain("nope-499'");
        // The omission is stated, not silent, with an accurate count.
        assertThat(out).contains("+480 more notes omitted (showing 20 of 500)");
    }

    @Test
    void shortRestatementAndNotesAreNeverTruncated() {
        RetrievalRequest req = new RetrievalRequest(Intent.SEARCH, List.of(), List.of(),
                "What is svc-orders?", List.of("a short note"), false);
        Evidence ev = evidence(req, List.of(), List.of());

        String out = EvidenceRenderer.render(ev);

        assertThat(out).doesNotContain("[truncated to");
        assertThat(out).contains("Interpreted as: What is svc-orders?").contains("- a short note");
    }
}
