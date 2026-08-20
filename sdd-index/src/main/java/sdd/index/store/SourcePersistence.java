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

    /** As above, for a module whose sources are not Java. */
    public static void persistModuleSource(Jdbi jdbi, long repoId, long moduleId, String language,
                                           java.util.List<SourceModel.TypeInfo> types,
                                           java.util.List<SourceModel.UsageRef> usages,
                                           java.util.List<SourceModel.FileRef> fileRefs) {
        jdbi.useTransaction(h ->
                persistModuleSource(h, repoId, moduleId, language, types, usages, fileRefs));
    }

    /** Handle-based overload — see {@link #clearRepoFileRefs(Handle, long)}. */
    public static void persistModuleSource(Handle h, long repoId, long moduleId,
                                           java.util.List<SourceModel.TypeInfo> types,
                                           java.util.List<SourceModel.UsageRef> usages,
                                           java.util.List<SourceModel.FileRef> fileRefs) {
        persistModuleSource(h, repoId, moduleId, "JAVA", types, usages, fileRefs);
    }

    public static void persistModuleSource(Handle h, long repoId, long moduleId, String language,
                                           java.util.List<SourceModel.TypeInfo> types,
                                           java.util.List<SourceModel.UsageRef> usages,
                                           java.util.List<SourceModel.FileRef> fileRefs) {
        persistModuleSource(h, repoId, moduleId, language, types, usages, fileRefs,
                java.util.List.of());
    }

    /**
     * As above, plus the type -> type edges of {@code V7__type_refs.sql}.
     *
     * <p>A separate overload rather than a widened signature so every existing caller — including
     * {@code TsExtraction}, where TypeScript stays module-granular — keeps compiling and states its
     * emptiness explicitly instead of by omission.
     *
     * <p>{@code type_ref} needs no DELETE of its own: {@code from_type_id} is a foreign key into
     * {@code java_type} with ON DELETE CASCADE, and the wipe above already removes this module's
     * types. If foreign keys were ever silently off, this table would accumulate orphans across
     * re-indexes; {@code SourcePersistenceTest} pins that.
     */
    public static void persistModuleSource(Handle h, long repoId, long moduleId, String language,
                                           java.util.List<SourceModel.TypeInfo> types,
                                           java.util.List<SourceModel.UsageRef> usages,
                                           java.util.List<SourceModel.FileRef> fileRefs,
                                           java.util.List<SourceModel.TypeRef> typeRefs) {
        h.createUpdate("DELETE FROM java_type WHERE module_id=:m").bind("m", moduleId).execute();
        h.createUpdate("DELETE FROM api_usage WHERE from_module_id=:m").bind("m", moduleId).execute();
        FtsSymbolWriter.deleteForModule(h, moduleId);
        java.util.Map<String, Long> typeIds = new java.util.HashMap<>();
        for (SourceModel.TypeInfo t : types) {
            typeIds.put(t.fqcn(), insertType(h, moduleId, language, t));
        }
        insertTypeRefs(h, typeIds, typeRefs);
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

    /**
     * One PreparedBatch, not a statement per row. {@code type_ref} is an order of magnitude larger
     * than {@code api_usage} — every import, call site and type mention of every extracted type in
     * the estate — and row-at-a-time inserts here are the one place this table's cost could stop
     * being negligible.
     *
     * <p>A {@code fromFqcn} with no id means the extractor and
     * {@code ApiSurfaceExtractor.isExtractedType} disagree about what becomes a row. That is a bug
     * in this module, not a data condition to absorb, so it fails loudly rather than dropping the
     * edge — a silently missing edge is invisible for as long as it takes someone to wonder why the
     * graph is thin.
     */
    private static void insertTypeRefs(Handle h, java.util.Map<String, Long> typeIds,
                                       java.util.List<SourceModel.TypeRef> typeRefs) {
        if (typeRefs.isEmpty()) {
            return;
        }
        org.jdbi.v3.core.statement.PreparedBatch batch = h.prepareBatch("""
                INSERT INTO type_ref(from_type_id, to_fqcn, ref_kind, ref_count)
                VALUES (:from, :to, :kind, :count)
                ON CONFLICT(from_type_id, to_fqcn, ref_kind)
                DO UPDATE SET ref_count = ref_count + excluded.ref_count""");
        for (SourceModel.TypeRef r : typeRefs) {
            Long fromId = typeIds.get(r.fromFqcn());
            if (fromId == null) {
                throw new IllegalStateException("type_ref names a type with no java_type row: "
                        + r.fromFqcn() + " -> " + r.toFqcn()
                        + " (ApiSurfaceExtractor.isExtractedType and ReferenceExtractor.typeRefs "
                        + "have drifted apart)");
            }
            batch.bind("from", fromId).bind("to", r.toFqcn())
                    .bind("kind", r.refKind()).bind("count", r.count()).add();
        }
        batch.execute();
    }

    private static long insertType(Handle h, long moduleId, String language, SourceModel.TypeInfo t) {
        String annotationsJson;
        try {
            annotationsJson = JSON.writeValueAsString(t.annotations());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        h.createUpdate("""
                        INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path,
                                              signature_hash, api_confidence, annotations, javadoc,
                                              language)
                        VALUES (:m, :fqcn, :kind, :api, :file, :hash, :conf, :ann, :javadoc, :lang)""")
                .bind("m", moduleId).bind("fqcn", t.fqcn()).bind("kind", t.kind())
                .bind("api", t.isApi() ? 1 : 0).bind("file", t.relPath())
                .bind("hash", t.signatureHash()).bind("conf", t.apiConfidence())
                .bind("ann", annotationsJson).bind("javadoc", t.javadoc())
                .bind("lang", language).execute();
        long typeId = h.createQuery("SELECT last_insert_rowid()").mapTo(Long.class).one();
        for (SourceModel.SupertypeRef sup : t.supertypes()) {
            // OR IGNORE rather than a dedupe pass: the primary key already states the rule, and a
            // declaration naming the same supertype twice is malformed source, not a case to model.
            h.createUpdate("INSERT OR IGNORE INTO type_supertype(type_id, supertype_fqcn, relation, resolution) VALUES (:t, :fqcn, :rel, :res)")
                    .bind("t", typeId).bind("fqcn", sup.supertypeFqcn())
                    .bind("rel", sup.relation()).bind("res", sup.resolution()).execute();
        }
        String simpleName = t.fqcn().substring(t.fqcn().lastIndexOf('.') + 1);
        FtsSymbolWriter.insert(h, moduleId, simpleName, t.fqcn(), t.javadoc());
        java.util.Set<String> ftsEmitted = new java.util.HashSet<>();
        for (SourceModel.MemberInfo m : t.members()) {
            h.createUpdate("INSERT INTO api_member(type_id, name, signature, return_type, synthesized_by) "
                            + "VALUES (:t, :name, :sig, :ret, :by)")
                    .bind("t", typeId).bind("name", m.name()).bind("sig", m.signature())
                    .bind("ret", m.returnType()).bind("by", m.synthesizedBy()).execute();
            // Members carry no doc text: member-level javadoc is out of scope, and repeating the
            // type's summary on each of its members would let one doc comment match a query once
            // per member and swamp the ranking of the type it actually describes.
            // Mirrors FtsSymbolWriter.rebuildFrom's filter: an angle-bracketed name is synthetic
            // (<init>, or <value> for a type alias whose right-hand side is a union) and is not
            // something anyone searches for.
            if (!m.name().startsWith("<") && ftsEmitted.add(m.name())) {
                FtsSymbolWriter.insert(h, moduleId, m.name(), t.fqcn(), "");
            }
        }
        return typeId;
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
