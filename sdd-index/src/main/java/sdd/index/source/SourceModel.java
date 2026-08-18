package sdd.index.source;

import java.util.List;

public final class SourceModel {
    private SourceModel() {}

    /**
     * One extracted type. {@code javadoc} is the summary sentence of the type's doc comment (see
     * {@code ApiSurfaceExtractor.javadocSummary}) and is <strong>null when the type carries no
     * javadoc</strong>, or carries one with an empty description — the common case for generated
     * and internal types. It is a retrieval aid, never a structural fact: it is unverified prose
     * that may describe behaviour the code no longer has, so it may make a type findable but may
     * never be the basis of a claim about the estate.
     */
    public record TypeInfo(String fqcn, String kind, boolean isApi, String relPath,
                           List<String> annotations, String apiConfidence,
                           String signatureHash, List<MemberInfo> members, String javadoc,
                           List<SupertypeRef> supertypes) {
        /** Pre-hierarchy shape, so non-Java extractors and tests construct unchanged. */
        public TypeInfo(String fqcn, String kind, boolean isApi, String relPath,
                        List<String> annotations, String apiConfidence, String signatureHash,
                        List<MemberInfo> members, String javadoc) {
            this(fqcn, kind, isApi, relPath, annotations, apiConfidence, signatureHash, members,
                    javadoc, List.of());
        }
    }

    /**
     * One {@code extends}/{@code implements} edge, carrying the SUBTYPE's own identity by virtue of
     * living on its {@link TypeInfo} — which is exactly what {@code api_usage} discards.
     *
     * @param resolution how {@code supertypeFqcn} was arrived at; see {@code V6__type_hierarchy.sql}
     */
    public record SupertypeRef(String supertypeFqcn, String relation, String resolution) {}

    public record MemberInfo(String name, String signature, String returnType, String synthesizedBy) {}

    public record UsageRef(String targetFqcn, String refKind) {}

    public record FileRef(String srcRel, String dstRel, int count) {}
}
