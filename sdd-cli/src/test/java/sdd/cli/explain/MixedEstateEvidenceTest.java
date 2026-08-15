package sdd.cli.explain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.kb.EntityKind;
import sdd.core.retrieve.FtsRetriever;
import sdd.core.retrieve.Retriever;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code sdd explain} answers once an estate contains both ecosystems. The point of every test
 * here is that a question about the estate gets an estate-wide answer, rather than one scoped to
 * whichever language the fact happens to be written in.
 */
class MixedEstateEvidenceTest {
    @TempDir Path ws;
    private Database db;
    private Retriever retriever;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            ExplainFixture.seed(h);
            ExplainFixture.seedNpm(h);
        });
        retriever = new FtsRetriever(db.jdbi());
    }

    private Evidence collect(RetrievalRequest request) {
        return EvidenceCollector.collect(db.jdbi(), retriever, request);
    }

    private static List<String> allTexts(Evidence evidence) {
        return evidence.sections().stream().flatMap(s -> s.facts().stream()).map(Fact::text).toList();
    }

    @Test
    void whoCallsAnEndpointIsAnsweredAcrossBothStacks() {
        // The question this whole capability exists to answer. Before TypeScript call sites were
        // indexed, the honest answer to "who calls GET /orders/{id}" named only the Java caller and
        // gave no hint that a browser SDK calls it too — which is exactly the caller you most need
        // to know about, because it ships separately and cannot be redeployed with the service.
        Evidence evidence = collect(new RetrievalRequest(Intent.CONSUMERS,
                List.of(new EntityRef(EntityKind.ENDPOINT, ExplainFixture.ORDERS_ENDPOINT, false)),
                List.of(), "who calls the orders endpoint", List.of(), false));

        assertThat(allTexts(evidence))
                .anySatisfy(t -> assertThat(t)
                        .contains("svc-billing").contains("GET /orders/{}").contains("svc-orders"))
                .anySatisfy(t -> assertThat(t)
                        .contains("web-sdk").contains("GET /orders/{}").contains("svc-orders"));
    }

    @Test
    void theCrossLanguageCallerIsNeverHighConfidence() {
        // A browser talks to one origin and an ingress fans it out, so "which service serves this
        // path" is genuinely absent from TypeScript source. MEDIUM — one endpoint in the estate
        // matches this verb and path — is the strongest claim the evidence supports; HIGH would be
        // asserting knowledge nothing in the repository contains.
        Evidence evidence = collect(new RetrievalRequest(Intent.CONSUMERS,
                List.of(new EntityRef(EntityKind.ENDPOINT, ExplainFixture.ORDERS_ENDPOINT, false)),
                List.of(), "who calls the orders endpoint", List.of(), false));

        assertThat(allTexts(evidence))
                .filteredOn(t -> t.contains("web-sdk"))
                .isNotEmpty()
                .allSatisfy(t -> assertThat(t).contains("confidence=MEDIUM").doesNotContain("HIGH"));
    }

    @Test
    void anNpmDependencyEdgeIsReportedLikeAnyOtherConsumer() {
        Evidence evidence = collect(new RetrievalRequest(Intent.CONSUMERS,
                List.of(new EntityRef(EntityKind.REPO, ExplainFixture.WEB_SDK, false)),
                List.of(), "what depends on the web sdk", List.of(), false));

        assertThat(allTexts(evidence)).anySatisfy(t -> assertThat(t).contains("web-app"));
    }

    @Test
    void anNpmRepoIsDescribableLikeAnyOther() {
        Evidence evidence = collect(new RetrievalRequest(Intent.DESCRIBE,
                List.of(new EntityRef(EntityKind.REPO, ExplainFixture.WEB_SDK, false)),
                List.of(), "what is web-sdk", List.of(), false));

        assertThat(evidence.sections()).isNotEmpty();
        assertThat(allTexts(evidence)).anySatisfy(t -> assertThat(t).contains("web-sdk"));
    }

    @Test
    void anUnresolvableTypeScriptCallIsCountedInTheAbsenceCaveatNotSilentlyDropped() {
        // web-app's client builds its path from a value the extractor cannot resolve, so it is
        // recorded with no norm_path and matched to nothing. That is the honest outcome — but it
        // has to be VISIBLE, or "nothing else calls this" reads as proof when it is only silence.
        // Asked as an impact question, because the caveat is scoped to the repos the answer covers
        // and only impact's covered set reaches web-app, where the unresolvable client lives.
        Evidence evidence = collect(new RetrievalRequest(Intent.IMPACT,
                List.of(new EntityRef(EntityKind.REPO, ExplainFixture.WEB_SDK, false)),
                List.of(), "what breaks if the web sdk changes", List.of(), false));

        assertThat(evidence.caveats())
                .filteredOn(c -> c.contains("Absence is never proof here"))
                .singleElement()
                .satisfies(c -> assertThat(c).contains("1 rest_client row(s) with no resolvable path"));
    }

    @Test
    void aResolvedPathTemplateIsNotCountedAsInvisible() {
        // The caveat counts what the query genuinely cannot see. web-sdk's call resolves to a path
        // template and is matched by the same rule Spring's own templates are matched by, so
        // counting it would make the number grow as extraction gets BETTER — the opposite of what
        // a reader would conclude. Of the two TypeScript clients in scope here, only web-app's
        // genuinely unresolvable one should be counted.
        Evidence evidence = collect(new RetrievalRequest(Intent.IMPACT,
                List.of(new EntityRef(EntityKind.REPO, ExplainFixture.WEB_SDK, false)),
                List.of(), "what breaks if the web sdk changes", List.of(), false));

        // Both TypeScript clients are in scope; only web-app's is genuinely unresolvable, so the
        // count must be 1 and not 2. Under the old "resolution is not LITERAL" predicate it was 2.
        assertThat(evidence.caveats())
                .filteredOn(c -> c.contains("Absence is never proof here"))
                .singleElement()
                .satisfies(c -> assertThat(c)
                        .contains("1 rest_client row(s) with no resolvable path")
                        .contains("web-app"));
    }
}
