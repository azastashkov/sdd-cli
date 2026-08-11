package sdd.index.source;

import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Set;

public final class ApiSurfaceExtractor {
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
                hash(fqcn, members), List.copyOf(members));
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

    static String hash(String fqcn, List<SourceModel.MemberInfo> members) {
        String canonical = fqcn + "\n" + members.stream()
                .map(SourceModel.MemberInfo::signature).sorted()
                .collect(Collectors.joining("\n"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
