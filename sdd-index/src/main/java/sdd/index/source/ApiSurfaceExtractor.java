package sdd.index.source;

import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.javadoc.description.JavadocDescriptionElement;
import com.github.javaparser.javadoc.description.JavadocInlineTag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.Set;

public final class ApiSurfaceExtractor {
    /**
     * Hard cap on a stored javadoc summary. Enough to carry a type's intent — the estate's longest
     * useful opening sentences run well under it — small enough that prose cannot bloat the index
     * or dominate bm25's length normalisation.
     */
    private static final int JAVADOC_MAX_CHARS = 400;

    private ApiSurfaceExtractor() {}

    public static List<SourceModel.TypeInfo> extract(SourceParser.Session session, boolean libraryModule) {
        List<SourceModel.TypeInfo> out = new ArrayList<>();
        for (SourceParser.ParsedUnit unit : session.units()) {
            String pkg = unit.cu().getPackageDeclaration()
                    .map(p -> p.getNameAsString()).orElse("");
            unit.cu().findAll(TypeDeclaration.class).stream()
                    .filter(t -> t.isPublic() || t.hasModifier(com.github.javaparser.ast.Modifier.Keyword.PROTECTED)
                            || (!t.isPrivate() && (t.getParentNode().filter(p -> p instanceof ClassOrInterfaceDeclaration c && c.isInterface()).isPresent()
                                    || t.getParentNode().filter(p -> p instanceof AnnotationDeclaration).isPresent())))
                    .forEach(t -> out.add(toTypeInfo(t, pkg, unit.relPath(), libraryModule)));
        }
        return List.copyOf(out);
    }

    private static SourceModel.TypeInfo toTypeInfo(TypeDeclaration<?> t, String pkg,
                                                   String relPath, boolean libraryModule) {
        String fqcn = t.getFullyQualifiedName().orElse(pkg.isEmpty()
                ? t.getNameAsString() : pkg + "." + t.getNameAsString());
        List<String> annotations = t.getAnnotations().stream()
                .map(a -> a.getName().getIdentifier()).toList();
        List<SourceModel.MemberInfo> real = extractMembers(t);
        LombokShim.Result lombok = LombokShim.apply(t);
        Set<String> realSignatures = real.stream()
                .map(SourceModel.MemberInfo::signature).collect(Collectors.toSet());
        List<SourceModel.MemberInfo> members = new ArrayList<>(real);
        lombok.synthesized().stream()
                .filter(m -> !realSignatures.contains(m.signature()))
                .forEach(members::add);
        boolean isApi = libraryModule && !fqcn.contains(".internal.");
        return new SourceModel.TypeInfo(fqcn, kindOf(t), isApi, relPath,
                annotations, lombok.unknownLombok() ? "PARTIAL" : "OK",
                hash(fqcn, members), List.copyOf(members), javadocSummary(t));
    }

    /**
     * The type's javadoc summary, or null when it has no doc comment (or one whose description is
     * empty). Deliberately only the <em>first sentence</em>, whitespace-collapsed, inline tags
     * flattened to their content, and truncated to {@link #JAVADOC_MAX_CHARS}.
     *
     * <p>Taking the summary and discarding the body is not just a size limit. Javadoc rots, and
     * nothing in this pipeline verifies it; the opening sentence states intent and contract, which
     * ages far better than the implementation detail further down ("uses a HashMap internally",
     * "called from the watcher thread"). Keeping the most stable part of the least reliable source
     * is the point. Block tags ({@code @param}, {@code @return}, …) are excluded by construction —
     * JavaParser keeps them out of the description.
     *
     * <p>Limitations, both accepted: the sentence boundary is javadoc's own rule — the first
     * {@code '.'} followed by whitespace or end of text — so a summary opening with an abbreviation
     * ("Wraps the API, e.g. the pricing one.") is cut at the abbreviation, exactly as the javadoc
     * tool would cut it. And the truncation is a hard character cut, so an over-long single
     * sentence ends mid-word; the text is only ever fed to a tokenizer, never displayed as prose.
     */
    static String javadocSummary(TypeDeclaration<?> t) {
        Optional<Javadoc> javadoc = t.getJavadoc();
        if (javadoc.isEmpty()) {
            return null;
        }
        StringBuilder flattened = new StringBuilder();
        for (JavadocDescriptionElement element : javadoc.get().getDescription().getElements()) {
            // toText() on an inline tag returns it verbatim, braces and tag name included
            // ("{@code HashMap}"); getContent() is the text a reader would actually see.
            flattened.append(element instanceof JavadocInlineTag tag ? tag.getContent() : element.toText());
        }
        String collapsed = flattened.toString().replaceAll("\\s+", " ").trim();
        if (collapsed.isEmpty()) {
            return null;
        }
        String sentence = firstSentence(collapsed);
        return sentence.length() > JAVADOC_MAX_CHARS ? sentence.substring(0, JAVADOC_MAX_CHARS) : sentence;
    }

    /**
     * Text up to and including the first sentence-ending period, or all of it if there is none.
     * Whitespace is already collapsed to single spaces by the caller, so "followed by whitespace"
     * is exactly "followed by a space".
     */
    private static String firstSentence(String collapsed) {
        for (int dot = collapsed.indexOf('.'); dot >= 0; dot = collapsed.indexOf('.', dot + 1)) {
            if (dot == collapsed.length() - 1 || collapsed.charAt(dot + 1) == ' ') {
                return collapsed.substring(0, dot + 1);
            }
        }
        return collapsed;
    }

    static List<SourceModel.MemberInfo> extractMembers(TypeDeclaration<?> t) {
        List<SourceModel.MemberInfo> members = new ArrayList<>();
        for (MethodDeclaration m : t.getMethods()) {
            if (m.isPublic() || m.isProtected()) {
                String params = m.getParameters().stream()
                        .map(p -> p.getType().asString())
                        .collect(Collectors.joining(","));
                members.add(new SourceModel.MemberInfo(m.getNameAsString(),
                        m.getNameAsString() + "(" + params + ")",
                        m.getType().asString(), null));
            }
        }
        for (FieldDeclaration f : t.getFields()) {
            if (f.isPublic() || f.isProtected()) {
                f.getVariables().forEach(v -> members.add(new SourceModel.MemberInfo(
                        v.getNameAsString(), v.getNameAsString(),
                        v.getType().asString(), null)));
            }
        }
        // Add record components as synthesized members
        if (t instanceof RecordDeclaration r) {
            var existingMethods = members.stream()
                    .filter(m -> m.synthesizedBy() == null)
                    .map(SourceModel.MemberInfo::signature)
                    .collect(Collectors.toSet());
            for (var component : r.getParameters()) {
                String componentSig = component.getNameAsString() + "()";
                // Skip if an explicit method already has this signature
                if (!existingMethods.contains(componentSig)) {
                    members.add(new SourceModel.MemberInfo(
                            component.getNameAsString(),
                            componentSig,
                            component.getType().asString(),
                            "record-component"));
                }
            }
        }
        return members;
    }

    private static String kindOf(TypeDeclaration<?> t) {
        if (t instanceof AnnotationDeclaration) {
            return "ANNOTATION";
        }
        if (t instanceof EnumDeclaration) {
            return "ENUM";
        }
        if (t instanceof RecordDeclaration) {
            return "RECORD";
        }
        if (t instanceof ClassOrInterfaceDeclaration c && c.isInterface()) {
            return "INTERFACE";
        }
        return "CLASS";
    }

    /**
     * Digest of the type's whole API shape. The member signature alone is not enough: it carries
     * the name and parameter types but not the return type, so {@code String quote(String)} and
     * {@code int quote(String)} would hash identically — and for fields the signature is just the
     * name, making every field retype invisible. Both are breaking changes, so the return type
     * (which for a field is its type) is part of the canonical string.
     */
    static String hash(String fqcn, List<SourceModel.MemberInfo> members) {
        String canonical = fqcn + "\n" + members.stream()
                .map(m -> m.signature() + ":" + Objects.toString(m.returnType(), ""))
                .sorted()
                .collect(Collectors.joining("\n"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
