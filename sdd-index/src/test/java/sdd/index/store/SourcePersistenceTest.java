package sdd.index.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.retrieve.FtsRetriever;
import sdd.core.retrieve.FtsSymbolWriter;
import sdd.index.source.SourceModel;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourcePersistenceTest {
    @TempDir Path ws;
    private Database db;
    private long repoId;
    private long moduleId;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core', '/w/lib-core', 'LIBRARY')");
            repoId = h.createQuery("SELECT id FROM repo").mapTo(Long.class).one();
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (" + repoId + ", ':', 'LIBRARY')");
            moduleId = h.createQuery("SELECT id FROM module").mapTo(Long.class).one();
        });
    }

    private static final String TIER_JAVADOC = "The customer's loyalty standing at checkout.";

    private static SourceModel.TypeInfo type() {
        return new SourceModel.TypeInfo("com.acme.pricing.LoyaltyTier", "ENUM", true,
                "src/main/java/com/acme/pricing/LoyaltyTier.java",
                List.of("Generated"), "OK", "f".repeat(64),
                List.of(new SourceModel.MemberInfo("values", "values()", "LoyaltyTier[]", null)),
                TIER_JAVADOC);
    }

    private List<Map<String, Object>> typeRefRows() {
        return db.jdbi().withHandle(h -> h.createQuery(
                "SELECT to_fqcn, ref_kind, ref_count FROM type_ref ORDER BY to_fqcn, ref_kind")
                .mapToMap().list());
    }

    private void persistWithRefs(List<SourceModel.TypeRef> refs) {
        db.jdbi().useHandle(h -> SourcePersistence.persistModuleSource(h, repoId, moduleId, "JAVA",
                List.of(type()), List.of(), List.of(), refs));
    }

    @Test
    void typeRefsArePersistedAndRePersistingAModuleIsIdempotent() {
        List<SourceModel.TypeRef> refs = List.of(
                new SourceModel.TypeRef("com.acme.pricing.LoyaltyTier", "com.acme.pricing.Money",
                        "TYPE", 3),
                new SourceModel.TypeRef("com.acme.pricing.LoyaltyTier", "com.acme.pricing.Money",
                        "CALL", 1));

        persistWithRefs(refs);
        assertThat(typeRefRows()).hasSize(2);
        assertThat(typeRefRows().get(1)).containsEntry("ref_count", 3);

        // Re-indexing the same module must not double the counts. This is the property that makes
        // the ON CONFLICT clause safe: the DELETE of java_type cascades the old rows away first,
        // so the upsert only ever merges duplicates WITHIN one extraction pass.
        persistWithRefs(refs);
        assertThat(typeRefRows()).hasSize(2);
        assertThat(typeRefRows().get(1)).containsEntry("ref_count", 3);
    }

    @Test
    void deletingAModulesTypesCascadesItsTypeRefsAway() {
        persistWithRefs(List.of(new SourceModel.TypeRef("com.acme.pricing.LoyaltyTier",
                "com.acme.pricing.Money", "TYPE", 1)));
        assertThat(typeRefRows()).hasSize(1);

        // V7 adds no DELETE of its own and relies entirely on ON DELETE CASCADE plus
        // Database.dataSource's enforceForeignKeys(true). If foreign keys were ever silently off,
        // type_ref would accumulate orphans across every re-index while every other table stayed
        // correct -- a drift nothing else in the suite would notice.
        db.jdbi().useHandle(h ->
                h.createUpdate("DELETE FROM java_type WHERE module_id=:m")
                        .bind("m", moduleId).execute());

        assertThat(typeRefRows()).isEmpty();
    }

    @Test
    void aTypeRefNamingATypeWithNoRowFailsLoudlyRatherThanBeingDropped() {
        // Means ApiSurfaceExtractor.isExtractedType and ReferenceExtractor.typeRefs have drifted
        // apart. A silently dropped edge is invisible for as long as it takes someone to wonder
        // why the graph is thin, so this must not be absorbed.
        assertThatThrownBy(() -> persistWithRefs(List.of(
                new SourceModel.TypeRef("com.acme.pricing.NotExtracted", "com.acme.pricing.Money",
                        "TYPE", 1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no java_type row")
                .hasMessageContaining("com.acme.pricing.NotExtracted");
    }

    @Test
    void persistsTypesMembersUsagesFileRefsAndFts() {
        SourcePersistence.clearRepoFileRefs(db.jdbi(), repoId);
        SourcePersistence.persistModuleSource(db.jdbi(), repoId, moduleId,
                List.of(type()),
                List.of(new SourceModel.UsageRef("com.other.Thing", "IMPORT")),
                List.of(new SourceModel.FileRef("a/A.java", "b/B.java", 3)));

        Map<String, Object> jt = db.jdbi().withHandle(h ->
                h.createQuery("SELECT fqcn, kind, is_api, annotations, javadoc FROM java_type")
                        .mapToMap().one());
        assertThat(jt).containsEntry("fqcn", "com.acme.pricing.LoyaltyTier").containsEntry("kind", "ENUM")
                .containsEntry("javadoc", TIER_JAVADOC);
        assertThat((String) jt.get("annotations")).contains("Generated");
        Integer memberCount = db.jdbi().withHandle(h -> h.createQuery("SELECT count(*) FROM api_member")
                .mapTo(Integer.class).one());
        assertThat(memberCount).isEqualTo(1);
        String usageTarget = db.jdbi().withHandle(h -> h.createQuery(
                "SELECT target_fqcn FROM api_usage WHERE from_module_id=" + moduleId)
                .mapTo(String.class).one());
        assertThat(usageTarget).isEqualTo("com.other.Thing");
        Integer refCount = db.jdbi().withHandle(h -> h.createQuery(
                "SELECT ref_count FROM file_ref WHERE src_file='a/A.java'")
                .mapTo(Integer.class).one());
        assertThat(refCount).isEqualTo(3);
        // FTS finds by camel-split word AND by member name
        assertThat(new FtsRetriever(db.jdbi()).search("loyalty", 10)).isNotEmpty();
        assertThat(new FtsRetriever(db.jdbi()).search("values", 10)).isNotEmpty();
        // ...and by a word that appears only in the type's javadoc, which is the whole point of
        // carrying it: "checkout" is in no identifier, package or member name anywhere here.
        assertThat(new FtsRetriever(db.jdbi()).search("checkout", 10))
                .singleElement()
                .satisfies(hit -> {
                    assertThat(hit.fqcn()).isEqualTo("com.acme.pricing.LoyaltyTier");
                    assertThat(hit.docOnly()).isTrue();
                });
        // The doc lands on the type's row only. Repeating it on each member row would let one doc
        // comment match a query once per member and bury the type it actually describes.
        List<String> docsByIdentifier = db.jdbi().withHandle(h -> h.createQuery(
                        "SELECT identifier || '=' || doc AS row FROM fts_symbol ORDER BY identifier")
                .mapTo(String.class).list());
        assertThat(docsByIdentifier).containsExactly("LoyaltyTier=" + TIER_JAVADOC, "values=");
    }

    @Test
    void reperistReplacesInsteadOfDuplicating() {
        SourcePersistence.persistModuleSource(db.jdbi(), repoId, moduleId,
                List.of(type()), List.of(), List.of());
        SourcePersistence.persistModuleSource(db.jdbi(), repoId, moduleId,
                List.of(type()), List.of(), List.of());
        Integer typeCount = db.jdbi().withHandle(h -> h.createQuery("SELECT count(*) FROM java_type")
                .mapTo(Integer.class).one());
        assertThat(typeCount).isEqualTo(1);
        Integer ftsCount = db.jdbi().withHandle(h -> h.createQuery(
                "SELECT count(*) FROM fts_symbol").mapTo(Integer.class).one());
        assertThat(ftsCount).isEqualTo(2);
    }

    @Test
    void parseStatusAppendsErrorInsteadOfOverwriting() {
        db.jdbi().useHandle(h -> h.execute("UPDATE repo SET error='old note; ' WHERE id=" + repoId));
        SourcePersistence.updateParseStatus(db.jdbi(), "lib-core", "DEGRADED", "3 files failed");
        Map<String, Object> repo = db.jdbi().withHandle(h ->
                h.createQuery("SELECT parse_status, error FROM repo").mapToMap().one());
        assertThat(repo.get("parse_status")).isEqualTo("DEGRADED");
        // an already-separated note is not double-separated
        assertThat(repo.get("error")).isEqualTo("old note; 3 files failed; ");
    }

    @Test
    void parseStatusAppendSeparatesFromAnErrorThatDoesNotEndInASeparator() {
        // what a gradle failure leaves behind: persistRepo writes the raw message, no trailing "; "
        db.jdbi().useHandle(h -> h.execute("UPDATE repo SET error='gradle kaput' WHERE id=" + repoId));
        SourcePersistence.updateParseStatus(db.jdbi(), "lib-core", "DEGRADED",
                "3 source files failed to parse");
        String error = db.jdbi().withHandle(h ->
                h.createQuery("SELECT error FROM repo").mapTo(String.class).one());
        assertThat(error).isEqualTo("gradle kaput; 3 source files failed to parse; ");
    }

    @Test
    void parseStatusAppendOnAnEmptyErrorDoesNotLeadWithASeparator() {
        SourcePersistence.updateParseStatus(db.jdbi(), "lib-core", "DEGRADED", "1 file failed");
        String error = db.jdbi().withHandle(h ->
                h.createQuery("SELECT error FROM repo").mapTo(String.class).one());
        assertThat(error).isEqualTo("1 file failed; ");
    }

    @Test
    void parseStatusAppendDoesNotDuplicateIdenticalMessages() {
        SourcePersistence.updateParseStatus(db.jdbi(), "lib-core", "DEGRADED", "3 files failed");
        SourcePersistence.updateParseStatus(db.jdbi(), "lib-core", "DEGRADED", "3 files failed");
        String error = db.jdbi().withHandle(h ->
                h.createQuery("SELECT error FROM repo").mapTo(String.class).one());
        assertThat(error.split("3 files failed", -1).length - 1).isEqualTo(1);
    }

    @Test
    void parseStatusAppendIsNotSwallowedByARawSubstringOfAnExistingLargerCount() {
        // "3 source files failed to parse" is a raw substring of "13 source files failed to
        // parse" — a naive instr() dedup check treats the shorter note as already present and
        // silently drops it. Both distinct counts must survive as separate delimited notes.
        db.jdbi().useHandle(h -> h.execute(
                "UPDATE repo SET error='13 source files failed to parse; ' WHERE id=" + repoId));
        SourcePersistence.updateParseStatus(db.jdbi(), "lib-core", "DEGRADED",
                "3 source files failed to parse");
        String error = db.jdbi().withHandle(h ->
                h.createQuery("SELECT error FROM repo").mapTo(String.class).one());
        assertThat(error).isEqualTo("13 source files failed to parse; 3 source files failed to parse; ");
    }

    @Test
    void repoAtomicWriteRollsBackEveryModuleOnMidRepoFailure() {
        // A second module in the same repo. When SourceExtraction persists a whole repo, both
        // modules' writes (and the shared clearRepoFileRefs) share one caller-owned transaction.
        long module2Id = db.jdbi().withHandle(h -> {
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (" + repoId + ", ':sub', 'LIBRARY')");
            return h.createQuery("SELECT id FROM module WHERE gradle_path=':sub'").mapTo(Long.class).one();
        });
        // A null target_fqcn violates api_usage's NOT NULL constraint, forcing a mid-transaction
        // failure on the second module — after the first module already wrote fresh java_type rows.
        SourceModel.UsageRef invalidUsage = new SourceModel.UsageRef(null, "IMPORT");

        assertThatThrownBy(() -> db.jdbi().useTransaction(h -> {
            SourcePersistence.clearRepoFileRefs(h, repoId);
            SourcePersistence.persistModuleSource(h, repoId, moduleId, List.of(type()), List.of(), List.of());
            SourcePersistence.persistModuleSource(h, repoId, module2Id, List.of(), List.of(invalidUsage), List.of());
        })).isInstanceOf(RuntimeException.class);

        // Full rollback: the first module's otherwise-successful java_type write must not survive
        // a failure elsewhere in the same repo-wide transaction.
        Integer typeCount = db.jdbi().withHandle(h -> h.createQuery("SELECT count(*) FROM java_type")
                .mapTo(Integer.class).one());
        assertThat(typeCount).isEqualTo(0);
        // fts_symbol is a virtual table — prove its writes ride the same transaction rather than
        // leaving searchable ghosts of rolled-back types.
        Integer ftsCount = db.jdbi().withHandle(h -> h.createQuery("SELECT count(*) FROM fts_symbol")
                .mapTo(Integer.class).one());
        assertThat(ftsCount).isEqualTo(0);
    }

    @Test
    void overloadedMembersProduceOneFtsRow() {
        SourceModel.TypeInfo overloaded = new SourceModel.TypeInfo(
                "com.acme.pricing.PriceCalculator", "CLASS", true,
                "src/main/java/com/acme/pricing/PriceCalculator.java",
                List.of(), "OK", "e".repeat(64),
                List.of(new SourceModel.MemberInfo("quote", "quote(String)", "String", null),
                        new SourceModel.MemberInfo("quote", "quote(String,int)", "String", null)),
                null);
        SourcePersistence.persistModuleSource(db.jdbi(), repoId, moduleId,
                List.of(overloaded), List.of(), List.of());
        Integer quoteRows = db.jdbi().withHandle(h -> h.createQuery(
                "SELECT count(*) FROM fts_symbol WHERE identifier='quote'").mapTo(Integer.class).one());
        assertThat(quoteRows).isEqualTo(1);
    }

    @Test
    void rebuildFromReproducesTheRowsThisClassWrote() {
        // A schema migration that recreates fts_symbol repopulates it with
        // FtsSymbolWriter.rebuildFrom, which reconstructs from java_type/api_member what this
        // class wrote at index time. rebuildFrom lives in sdd-core and cannot call this class, so
        // the two are separate statements of one rule; if they ever drift, upgraded workspaces get
        // a quietly wrong search index and no test in sdd-core would notice. Pin them together
        // here, the one place both are visible, against real indexer output.
        SourceModel.TypeInfo calculator = new SourceModel.TypeInfo(
                "com.acme.pricing.PriceCalculator", "CLASS", true,
                "src/main/java/com/acme/pricing/PriceCalculator.java",
                List.of(), "OK", "a".repeat(64),
                List.of(new SourceModel.MemberInfo("<init>", "PriceCalculator()", "void", null),
                        new SourceModel.MemberInfo("quote", "quote(String)", "String", null),
                        new SourceModel.MemberInfo("quote", "quote(String,int)", "String", null)),
                null);
        SourceModel.TypeInfo tier = new SourceModel.TypeInfo(
                "com.acme.pricing.LoyaltyTier", "ENUM", true,
                "src/main/java/com/acme/pricing/LoyaltyTier.java",
                List.of(), "OK", "b".repeat(64),
                List.of(new SourceModel.MemberInfo("quote", "quote()", "String", null),
                        new SourceModel.MemberInfo("values", "values()", "LoyaltyTier[]", null)),
                TIER_JAVADOC);
        // A member whose name equals its type's simple name: both paths deliberately emit it twice,
        // once as the type row and once as the member row, and the two rows are indistinguishable
        // in every sort key. A rebuild that "tidied" that duplicate away — or that emitted it in
        // one path only — would diverge here and nowhere else.
        SourceModel.TypeInfo currency = new SourceModel.TypeInfo(
                "com.acme.pricing.Currency", "CLASS", true,
                "src/main/java/com/acme/pricing/Currency.java",
                List.of(), "OK", "c".repeat(64),
                List.of(new SourceModel.MemberInfo("Currency", "Currency", "String", null)),
                null);
        SourcePersistence.persistModuleSource(db.jdbi(), repoId, moduleId,
                List.of(calculator, tier, currency), List.of(), List.of());
        List<String> written = symbolRows();
        // constructors dropped, the overload collapsed, 'quote' kept once under each of its two
        // types, and 'Currency' present twice
        assertThat(written).hasSize(7);

        // Called on the populated table on purpose: rebuildFrom clears before it rebuilds, so a
        // caller that forgets to (it is public, and its production caller only happens to hand it
        // an empty table) gets the reconstruction rather than every row silently doubled.
        db.jdbi().useHandle(FtsSymbolWriter::rebuildFrom);

        assertThat(symbolRows()).isEqualTo(written);
    }

    /**
     * Identity of every fts_symbol row, order-independent.
     *
     * <p>Excludes the {@code doc} column, and the exclusion is the point: {@code rebuildFrom} runs
     * only inside the V2 migration, where {@code java_type.javadoc} does not exist yet, so it
     * writes {@code ""} where the indexer writes a type's javadoc. That one column is a known,
     * documented divergence (see {@code FtsSymbolWriter.rebuildFrom}); every other column must
     * match exactly.
     */
    private List<String> symbolRows() {
        return db.jdbi().withHandle(h -> h.createQuery("""
                        SELECT identifier || '|' || fqcn || '|' || words || '|' || module_id AS row
                        FROM fts_symbol ORDER BY identifier, fqcn, module_id""")
                .mapTo(String.class).list());
    }
}
