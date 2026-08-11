package sdd.index.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.retrieve.FtsRetriever;
import sdd.index.source.SourceModel;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static SourceModel.TypeInfo type() {
        return new SourceModel.TypeInfo("com.acme.pricing.LoyaltyTier", "ENUM", true,
                "src/main/java/com/acme/pricing/LoyaltyTier.java",
                List.of("Generated"), "OK", "f".repeat(64),
                List.of(new SourceModel.MemberInfo("values", "values()", "LoyaltyTier[]", null)));
    }

    @Test
    void persistsTypesMembersUsagesFileRefsAndFts() {
        SourcePersistence.clearRepoFileRefs(db.jdbi(), repoId);
        SourcePersistence.persistModuleSource(db.jdbi(), repoId, moduleId,
                List.of(type()),
                List.of(new SourceModel.UsageRef("com.other.Thing", "IMPORT")),
                List.of(new SourceModel.FileRef("a/A.java", "b/B.java", 3)));

        Map<String, Object> jt = db.jdbi().withHandle(h ->
                h.createQuery("SELECT fqcn, kind, is_api, annotations FROM java_type").mapToMap().one());
        assertThat(jt).containsEntry("fqcn", "com.acme.pricing.LoyaltyTier").containsEntry("kind", "ENUM");
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
        assertThat((String) repo.get("error")).contains("old note").contains("3 files failed");
    }
}
