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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.Set;

public final class ApiSurfaceExtractor {
    /**
     * Hard cap on a stored javadoc summary. Enough to carry a type's intent — the estate's longest
     * useful opening sentences run well under it — small enough that prose cannot bloat the index
     * or dominate bm25's length normalisation.
     */
    private static final int JAVADOC_MAX_CHARS = 400;

    /**
     * An HTML tag in javadoc prose — {@code <p>}, {@code <b>}, {@code <a href="…">} and their
     * closing forms. Replaced with a space, not with nothing, so {@code one<br>two} stays two words.
     */
    private static final Pattern HTML_TAG = Pattern.compile("<[^<>]*>");

    private ApiSurfaceExtractor() {}

    public static List<SourceModel.TypeInfo> extract(SourceParser.Session session, boolean libraryModule) {
        List<SourceModel.TypeInfo> out = new ArrayList<>();
        for (SourceParser.ParsedUnit unit : session.units()) {
            String pkg = unit.cu().getPackageDeclaration()
                    .map(p -> p.getNameAsString()).orElse("");
            unit.cu().findAll(TypeDeclaration.class).stream()
                    .filter(ApiSurfaceExtractor::isExtractedType)
                    .forEach(t -> out.add(toTypeInfo(t, pkg, unit.relPath(), libraryModule, unit.cu())));
        }
        return List.copyOf(out);
    }

    /**
     * Whether a declaration becomes a {@code java_type} row. The one definition of what this
     * knowledge base can see; {@link ReferenceExtractor#typeRefs} must select over exactly the same
     * set, or a reference would be attributed to a type that has no row to hang it on.
     *
     * <p>Three admitting cases:
     * <ul>
     *   <li>{@code public} or {@code protected} — declared surface, in any position;
     *   <li><b>any top-level type</b>, including a package-private one. A top-level declaration
     *       cannot be {@code private} in Java, so this admits the whole file-level set;
     *   <li>a non-private type nested in an interface or annotation, which Java makes implicitly
     *       public.
     * </ul>
     *
     * <p>The top-level clause was added on measured evidence (2026-08-20). Restricting the index to
     * public/protected types put <b>19% of this estate's main-source files out of reach — 37% of
     * trading-core's</b>, and among them every class the 2026-08-19 explore measurement was about:
     * both {@code TierInvalidationListener}s and {@code OrdersConfig} are package-private, so the
     * repeated finding that "perfect seeding still never names the classes" was never a ranking
     * failure. The rows did not exist. A Spring {@code @Component}, a listener, a config class —
     * the idiomatic way to write one is package-private, and those are exactly the types a
     * change-impact question is about.
     *
     * <p>Deliberately still excluded: a package-private type <em>nested inside a class</em>. It is
     * addressable only through its outer type, which does have a row, and admitting it would fill
     * the corpus with helper classes for no measured gain. Revisit only with evidence, the same way
     * this clause arrived.
     */
    static boolean isExtractedType(TypeDeclaration<?> t) {
        if (t.isPublic() || t.hasModifier(com.github.javaparser.ast.Modifier.Keyword.PROTECTED)) {
            return true;
        }
        if (t.isPrivate()) {
            return false;
        }
        if (t.getParentNode()
                .filter(p -> p instanceof com.github.javaparser.ast.CompilationUnit).isPresent()) {
            return true;
        }
        return t.getParentNode().filter(p -> p instanceof ClassOrInterfaceDeclaration c
                        && c.isInterface()).isPresent()
                || t.getParentNode().filter(p -> p instanceof AnnotationDeclaration).isPresent();
    }

    private static SourceModel.TypeInfo toTypeInfo(TypeDeclaration<?> t, String pkg,
                                                   String relPath, boolean libraryModule,
                                                   com.github.javaparser.ast.CompilationUnit cu) {
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
        // A package-private type is not API surface however library-ish its module is: nothing
        // outside its package can name it. Without this clause, widening the extractor to top-level
        // package-private types would silently mark them is_api=1 in every library module, and
        // is_api is the primary sort key of the drafter's and the work order's evidence.
        boolean declaredSurface = t.isPublic()
                || t.hasModifier(com.github.javaparser.ast.Modifier.Keyword.PROTECTED)
                || t.getParentNode().filter(p -> p instanceof ClassOrInterfaceDeclaration c
                                && c.isInterface()).isPresent()
                || t.getParentNode().filter(p -> p instanceof AnnotationDeclaration).isPresent();
        boolean isApi = libraryModule && declaredSurface && !fqcn.contains(".internal.");
        List<SourceModel.SupertypeRef> supertypes =
                t instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration decl
                        ? SupertypeResolver.resolve(cu, decl)
                        : List.of();
        return new SourceModel.TypeInfo(fqcn, kindOf(t), isApi, relPath,
                annotations, lombok.unknownLombok() ? "PARTIAL" : "OK",
                hash(fqcn, members), List.copyOf(members), javadocSummary(t), supertypes);
    }

    /**
     * The type's javadoc summary, or null when it has no doc comment (or one whose description is
     * empty). Deliberately only the <em>first sentence</em>, whitespace-collapsed, inline tags
     * flattened to their content, HTML markup dropped, and truncated to
     * {@link #JAVADOC_MAX_CHARS}.
     *
     * <p>Taking the summary and discarding the body is not just a size limit. Javadoc rots, and
     * nothing in this pipeline verifies it; the opening sentence states intent and contract, which
     * ages far better than the implementation detail further down ("uses a HashMap internally",
     * "called from the watcher thread"). Keeping the most stable part of the least reliable source
     * is the point. Block tags ({@code @param}, {@code @return}, …) are excluded by construction —
     * JavaParser keeps them out of the description.
     *
     * <p>Markup is dropped rather than indexed. This column exists to be searched, and a corpus
     * that holds {@code b} from {@code <b>write-through</b>} or {@code href} and {@code http} from
     * an anchor answers queries no reader would type while diluting the words they would. Tags are
     * stripped from the description's own text only; an inline tag's content is taken verbatim, so
     * a {@code Map<String,Long>} written inside an inline code tag keeps its type arguments.
     * Character entities are unescaped afterwards, not before, so text an author escaped
     * deliberately ({@code &lt;p&gt;}) survives as the literal it was written to be rather than
     * being mistaken for the markup it names.
     *
     * <p>Limitations, all accepted: the sentence boundary is javadoc's own rule — the first
     * {@code '.'} followed by whitespace or end of text — so a summary opening with an abbreviation
     * ("Wraps the API, e.g. the pricing one.") is cut at the abbreviation, exactly as the javadoc
     * tool would cut it. Angle brackets in the description are treated as markup, which is what
     * javadoc's own spec requires them to be ({@code &lt;} is mandatory for a literal), so a bare
     * {@code Map<K,V>} written outside {@code {@code …}} — malformed javadoc, but common — loses
     * its type arguments here. And the truncation is a hard character cut, so an over-long single
     * sentence ends mid-word; it steps back one char rather than splitting a surrogate pair, since
     * half a pair is not a character at all and would corrupt the stored text rather than merely
     * shorten it. The result is only ever fed to a tokenizer, never displayed as prose.
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
            flattened.append(element instanceof JavadocInlineTag tag
                    ? tag.getContent()
                    : unescape(HTML_TAG.matcher(element.toText()).replaceAll(" ")));
        }
        String collapsed = flattened.toString().replaceAll("\\s+", " ").trim();
        if (collapsed.isEmpty()) {
            return null;
        }
        return cap(firstSentence(collapsed));
    }

    /**
     * The predefined character entities, unescaped after markup has already been dropped, and
     * applied to the description's own text only — never to an inline tag's content, which is
     * literal by definition. {@code &amp;} is unescaped last so that {@code &amp;amp;lt;} — an
     * author writing about the entity itself — does not decay to {@code <} in two passes.
     */
    private static String unescape(String text) {
        return text.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    /**
     * Truncates to {@link #JAVADOC_MAX_CHARS}, stepping back one character rather than cutting a
     * surrogate pair in half — an unpaired surrogate is not a character and would corrupt the
     * stored text, where a word cut short only shortens it.
     */
    private static String cap(String sentence) {
        if (sentence.length() <= JAVADOC_MAX_CHARS) {
            return sentence;
        }
        int end = Character.isHighSurrogate(sentence.charAt(JAVADOC_MAX_CHARS - 1))
                ? JAVADOC_MAX_CHARS - 1 : JAVADOC_MAX_CHARS;
        return sentence.substring(0, end);
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
