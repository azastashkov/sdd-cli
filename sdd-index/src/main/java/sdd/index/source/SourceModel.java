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
                           String signatureHash, List<MemberInfo> members, String javadoc) {}

    public record MemberInfo(String name, String signature, String returnType, String synthesizedBy) {}

    public record UsageRef(String targetFqcn, String refKind) {}

    public record FileRef(String srcRel, String dstRel, int count) {}
}
