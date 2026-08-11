package sdd.index.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import sdd.core.retrieve.FtsSymbolWriter;
import sdd.index.source.SourceModel;

public final class SourcePersistence {
    private static final ObjectMapper JSON = new ObjectMapper();

    private SourcePersistence() {}

    public static void clearRepoFileRefs(Jdbi jdbi, long repoId) {
        jdbi.useHandle(h -> clearRepoFileRefs(h, repoId));
    }

    /**
     * Handle-based overload so callers that must write a whole repo's source atomically (see
     * {@link sdd.index.source.SourceExtraction#extractRepo}) can compose this with
     * {@link #persistModuleSource(Handle, long, long, java.util.List, java.util.List, java.util.List)}
     * inside a single caller-owned transaction, instead of each getting its own.
     */
    public static void clearRepoFileRefs(Handle h, long repoId) {
        h.createUpdate("DELETE FROM file_ref WHERE repo_id=:r").bind("r", repoId).execute();
    }

    public static void persistModuleSource(Jdbi jdbi, long repoId, long moduleId,
                                           java.util.List<SourceModel.TypeInfo> types,
                                           java.util.List<SourceModel.UsageRef> usages,
                                           java.util.List<SourceModel.FileRef> fileRefs) {
        jdbi.useTransaction(h -> persistModuleSource(h, repoId, moduleId, types, usages, fileRefs));
    }

    /** Handle-based overload — see {@link #clearRepoFileRefs(Handle, long)}. */
    public static void persistModuleSource(Handle h, long repoId, long moduleId,
                                           java.util.List<SourceModel.TypeInfo> types,
                                           java.util.List<SourceModel.UsageRef> usages,
                                           java.util.List<SourceModel.FileRef> fileRefs) {
        h.createUpdate("DELETE FROM java_type WHERE module_id=:m").bind("m", moduleId).execute();
        h.createUpdate("DELETE FROM api_usage WHERE from_module_id=:m").bind("m", moduleId).execute();
        FtsSymbolWriter.deleteForModule(h, moduleId);
        for (SourceModel.TypeInfo t : types) {
            insertType(h, moduleId, t);
        }
        for (SourceModel.UsageRef u : usages) {
            h.createUpdate("INSERT INTO api_usage(from_module_id, target_fqcn, ref_kind) "
                            + "VALUES (:m, :fqcn, :kind)")
                    .bind("m", moduleId).bind("fqcn", u.targetFqcn())
                    .bind("kind", u.refKind()).execute();
        }
        for (SourceModel.FileRef fr : fileRefs) {
            h.createUpdate("INSERT INTO file_ref(repo_id, src_file, dst_file, ref_count) "
                            + "VALUES (:r, :src, :dst, :count)")
                    .bind("r", repoId).bind("src", fr.srcRel())
                    .bind("dst", fr.dstRel()).bind("count", fr.count()).execute();
        }
    }

    private static void insertType(Handle h, long moduleId, SourceModel.TypeInfo t) {
        String annotationsJson;
        try {
            annotationsJson = JSON.writeValueAsString(t.annotations());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        h.createUpdate("""
                        INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path,
                                              signature_hash, api_confidence, annotations)
                        VALUES (:m, :fqcn, :kind, :api, :file, :hash, :conf, :ann)""")
                .bind("m", moduleId).bind("fqcn", t.fqcn()).bind("kind", t.kind())
                .bind("api", t.isApi() ? 1 : 0).bind("file", t.relPath())
                .bind("hash", t.signatureHash()).bind("conf", t.apiConfidence())
                .bind("ann", annotationsJson).execute();
        long typeId = h.createQuery("SELECT last_insert_rowid()").mapTo(Long.class).one();
        String simpleName = t.fqcn().substring(t.fqcn().lastIndexOf('.') + 1);
        FtsSymbolWriter.insert(h, moduleId, simpleName, t.fqcn());
        java.util.Set<String> ftsEmitted = new java.util.HashSet<>();
        for (SourceModel.MemberInfo m : t.members()) {
            h.createUpdate("INSERT INTO api_member(type_id, name, signature, return_type, synthesized_by) "
                            + "VALUES (:t, :name, :sig, :ret, :by)")
                    .bind("t", typeId).bind("name", m.name()).bind("sig", m.signature())
                    .bind("ret", m.returnType()).bind("by", m.synthesizedBy()).execute();
            if (!m.name().equals("<init>") && ftsEmitted.add(m.name())) {
                FtsSymbolWriter.insert(h, moduleId, m.name(), t.fqcn());
            }
        }
    }

    /**
     * Sets parse_status and, when given, appends a note to the repo's error column. Notes are
     * "; "-terminated, but the existing error may not be (a gradle failure message is written
     * raw), so the separator is re-established rather than assumed: rtrim any trailing "; " off
     * what is there and put exactly one back. An empty/NULL error gains no leading separator.
     * If the exact append text is already present as a "; "-delimited segment, the append is
     * skipped — a repeated identical failure (e.g. retried indexing hitting the same error) must
     * not grow the column unbounded. The match is delimiter-anchored rather than a raw substring
     * check: notes are "; "-terminated by construction, so both the existing error and the
     * needle are wrapped in "; " before comparing, which also means "3 ... failed" is not
     * mistaken for already-present inside "13 ... failed".
     */
    public static void updateParseStatus(Jdbi jdbi, String repoName, String parseStatus, String errorAppend) {
        jdbi.useHandle(h -> h.createUpdate("""
                        UPDATE repo SET parse_status=:status,
                          error = CASE WHEN :append IS NULL THEN error
                                       WHEN instr('; ' || COALESCE(error, ''), '; ' || :append || '; ') > 0 THEN error
                                       ELSE COALESCE(NULLIF(rtrim(COALESCE(error, ''), '; '), '') || '; ', '')
                                            || :append || '; ' END
                        WHERE name=:name""")
                .bind("status", parseStatus).bind("append", errorAppend)
                .bind("name", repoName).execute());
    }
}
