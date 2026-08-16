package sdd.index.ts;

import com.fasterxml.jackson.databind.JsonNode;
import sdd.index.source.SourceModel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the sidecar's reading of a package into the rows the knowledge base already understands.
 *
 * <p>The whole job is choosing each symbol's NAME. A TypeScript declaration has several: the file
 * it lives in, the entry point that re-exports it, and the specifier a consumer writes. Only the
 * last one joins two repos, because {@code UsageLinker} resolves a cross-repo reference by string
 * equality on {@code java_type.fqcn} — so recording {@code Tick} under its file path leaves every
 * consumer of {@code @acme/web-sdk} pointing at nothing.
 *
 * <p>Hence: {@code <specifier>.<exportedName>} for anything a consumer can import, and
 * {@code <package>/<path>.<name>} for everything else. The separator is a dot so
 * {@code KbEntities.resolveClass}'s bare-name lookup keeps working.
 */
public final class TsApiSurface {

    /** Matches {@code ApiSurfaceExtractor}'s cap so both languages summarise docs the same way. */
    private static final int DOC_MAX_CHARS = 400;

    private TsApiSurface() {
    }

    /**
     * @param packageName the npm package these files belong to
     * @param isPrivate   a private package publishes nothing, so nothing in it is API however it is
     *                    exported — the npm equivalent of an internal package
     */
    public static List<SourceModel.TypeInfo> typesOf(JsonNode packageNode, String packageName,
                                                     boolean isPrivate, boolean partialEntries) {
        Map<String, String> publicNames = publicNames(packageNode);
        List<SourceModel.TypeInfo> types = new ArrayList<>();
        for (JsonNode file : packageNode.path("files")) {
            String relPath = file.path("relPath").asText();
            for (JsonNode symbol : file.path("symbols")) {
                String name = symbol.path("name").asText();
                String publicSpecifier = publicNames.get(relPath + "#" + name);
                String fqcn = publicSpecifier != null
                        ? publicSpecifier + "." + name
                        : packageName + "/" + stripExtension(relPath) + "." + name;
                List<SourceModel.MemberInfo> members = membersOf(symbol);
                types.add(new SourceModel.TypeInfo(
                        fqcn,
                        symbol.path("kind").asText("CLASS"),
                        publicSpecifier != null && !isPrivate,
                        relPath,
                        annotationsOf(symbol),
                        // PARTIAL says the package's surface is known to be incomplete rather than
                        // known to be small — the same distinction the Java side draws when Lombok
                        // synthesis cannot be resolved.
                        partialEntries ? "PARTIAL" : "OK",
                        hash(fqcn, members),
                        members,
                        docOf(symbol)));
            }
        }
        return types;
    }

    /**
     * Cross-package imports, as {@code api_usage} references.
     *
     * <p>The target is {@code <specifier>.<name>} — exactly the string a provider records its
     * exports under — so {@code UsageLinker} resolves them by the same equality it already uses for
     * Java FQCNs, with no TypeScript-aware query anywhere. An import naming a package nobody in the
     * estate publishes simply stays unresolved, as an import of a third-party library should.
     */
    public static List<SourceModel.UsageRef> usagesOf(JsonNode packageNode) {
        List<SourceModel.UsageRef> usages = new ArrayList<>();
        for (JsonNode file : packageNode.path("files")) {
            for (JsonNode ref : file.path("refs")) {
                String specifier = ref.path("specifier").asText(null);
                if (specifier == null) {
                    continue;
                }
                JsonNode name = ref.path("name");
                // A side-effect or namespace import names the module and nothing in it; recording
                // the bare specifier keeps the dependency visible without inventing a symbol.
                String target = name.isNull() || name.isMissingNode()
                        ? specifier : specifier + "." + name.asText();
                usages.add(new SourceModel.UsageRef(target, ref.path("kind").asText("IMPORT")));
            }
        }
        return usages;
    }

    /**
     * {@code file#name} to the specifier a consumer would write.
     *
     * <p>When a declaration is reachable under several specifiers the shortest wins, so a symbol
     * exported from both {@code @acme/lib} and {@code @acme/lib/contract} is recorded under the
     * root. Deterministic, and it picks the name a consumer is likelier to have written.
     */
    private static Map<String, String> publicNames(JsonNode packageNode) {
        Map<String, String> names = new LinkedHashMap<>();
        for (JsonNode export : packageNode.path("publicExports")) {
            String declFile = export.path("declFile").asText(null);
            String declName = export.path("declName").asText(null);
            String exportName = export.path("exportName").asText(null);
            String specifier = export.path("specifier").asText(null);
            if (declFile == null || specifier == null || exportName == null) {
                continue;
            }
            // Keyed on the DECLARED name, since that is what the file's symbol list carries; the
            // exported name is what a consumer writes and becomes the fqcn's tail.
            String key = declFile + "#" + (declName == null ? exportName : declName);
            names.merge(key, specifier, (existing, candidate) ->
                    candidate.length() < existing.length() ? candidate : existing);
        }
        return names;
    }

    private static List<SourceModel.MemberInfo> membersOf(JsonNode symbol) {
        List<SourceModel.MemberInfo> members = new ArrayList<>();
        for (JsonNode member : symbol.path("members")) {
            members.add(new SourceModel.MemberInfo(
                    member.path("name").asText(),
                    member.path("signature").asText(),
                    member.path("returnType").isNull() ? null : member.path("returnType").asText(),
                    null));
        }
        return members;
    }

    private static List<String> annotationsOf(JsonNode symbol) {
        List<String> decorators = new ArrayList<>();
        symbol.path("decorators").forEach(d -> decorators.add(d.asText()));
        return decorators;
    }

    /**
     * First sentence, capped — the same treatment {@code ApiSurfaceExtractor} gives javadoc, and
     * for the same reason: a doc comment is a retrieval aid, never a structural fact, so it is
     * stored at the length that helps a search and no further.
     */
    private static String docOf(JsonNode symbol) {
        JsonNode doc = symbol.path("doc");
        if (doc.isNull() || doc.isMissingNode()) {
            return null;
        }
        String text = doc.asText().strip();
        if (text.isEmpty()) {
            return null;
        }
        int stop = firstSentenceEnd(text);
        String summary = stop < 0 ? text : text.substring(0, stop + 1);
        if (summary.length() > DOC_MAX_CHARS) {
            summary = summary.substring(0, DOC_MAX_CHARS);
            if (Character.isHighSurrogate(summary.charAt(summary.length() - 1))) {
                summary = summary.substring(0, summary.length() - 1);
            }
        }
        return summary;
    }

    private static int firstSentenceEnd(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '.' && (i + 1 == text.length() || text.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    /** Byte-for-byte the Java side's rule, so a signature change moves the hash identically. */
    static String hash(String fqcn, List<SourceModel.MemberInfo> members) {
        List<String> lines = new ArrayList<>();
        for (SourceModel.MemberInfo m : members) {
            lines.add(m.signature() + ":" + m.returnType());
        }
        lines.sort(String::compareTo);
        StringBuilder sb = new StringBuilder(fqcn);
        for (String line : lines) {
            sb.append('\n').append(line);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String stripExtension(String relPath) {
        int dot = relPath.lastIndexOf('.');
        return dot > relPath.lastIndexOf('/') ? relPath.substring(0, dot) : relPath;
    }
}
