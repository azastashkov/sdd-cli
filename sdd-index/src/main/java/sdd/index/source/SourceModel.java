package sdd.index.source;

import java.util.List;

public final class SourceModel {
    private SourceModel() {}

    public record TypeInfo(String fqcn, String kind, boolean isApi, String relPath,
                           List<String> annotations, String apiConfidence,
                           String signatureHash, List<MemberInfo> members) {}

    public record MemberInfo(String name, String signature, String returnType, String synthesizedBy) {}

    public record UsageRef(String targetFqcn, String refKind) {}

    public record FileRef(String srcRel, String dstRel, int count) {}
}
