package sdd.index.source;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class LombokShim {
    public record Result(List<SourceModel.MemberInfo> synthesized, boolean unknownLombok) {}

    private static final Set<String> GENERATING = Set.of(
            "Getter", "Setter", "Data", "Value", "Builder",
            "NoArgsConstructor", "AllArgsConstructor", "RequiredArgsConstructor");
    private static final Set<String> IGNORED = Set.of(
            "Slf4j", "Log4j2", "CustomLog", "UtilityClass", "FieldDefaults",
            "EqualsAndHashCode", "ToString", "NonNull", "SneakyThrows",
            "Synchronized", "Cleanup", "With");
    private static final Set<String> KNOWN_LOMBOK_EXTRAS = Set.of(
            "SuperBuilder", "Accessors", "Wither", "Delegate", "Tolerate",
            "Jacksonized", "StandardException", "ExtensionMethod");

    private LombokShim() {}

    public static Result apply(TypeDeclaration<?> type) {
        Set<String> annotationNames = type.getAnnotations().stream()
                .map(a -> a.getName().getIdentifier()).collect(Collectors.toSet());
        boolean unknownLombok = annotationNames.stream()
                .anyMatch(n -> !GENERATING.contains(n) && !IGNORED.contains(n)
                        && importedFromLombok(type, n));
        List<SourceModel.MemberInfo> synthesized = new ArrayList<>();
        boolean data = annotationNames.contains("Data");
        boolean value = annotationNames.contains("Value");
        String typeName = type.getNameAsString();

        List<VariableDeclarator> fields = new ArrayList<>();
        for (FieldDeclaration f : type.getFields()) {
            if (!f.isStatic()) {
                fields.addAll(f.getVariables());
            }
        }
        if (annotationNames.contains("Getter") || data || value) {
            String by = "lombok:@" + (data ? "Data" : value ? "Value" : "Getter");
            for (VariableDeclarator v : fields) {
                String prefix = v.getTypeAsString().equals("boolean") ? "is" : "get";
                String name = prefix + capitalize(v.getNameAsString());
                synthesized.add(new SourceModel.MemberInfo(name, name + "()", v.getTypeAsString(), by));
            }
        }
        if (annotationNames.contains("Setter") || data) {
            String by = "lombok:@" + (data ? "Data" : "Setter");
            for (VariableDeclarator v : fields) {
                if (!isFinal(v)) {
                    String name = "set" + capitalize(v.getNameAsString());
                    synthesized.add(new SourceModel.MemberInfo(name,
                            name + "(" + v.getTypeAsString() + ")", "void", by));
                }
            }
        }
        if (annotationNames.contains("Builder")) {
            synthesized.add(new SourceModel.MemberInfo("builder", "builder()",
                    typeName + ".Builder", "lombok:@Builder"));
        }
        if (annotationNames.contains("NoArgsConstructor")) {
            synthesized.add(ctor(typeName, List.of(), "NoArgsConstructor"));
        }
        if (annotationNames.contains("AllArgsConstructor") || value) {
            synthesized.add(ctor(typeName, fields.stream().map(VariableDeclarator::getTypeAsString).toList(),
                    value ? "Value" : "AllArgsConstructor"));
        }
        if (annotationNames.contains("RequiredArgsConstructor") || data) {
            synthesized.add(ctor(typeName, fields.stream().filter(LombokShim::isFinal)
                            .filter(v -> v.getInitializer().isEmpty())
                            .map(VariableDeclarator::getTypeAsString).toList(),
                    data ? "Data" : "RequiredArgsConstructor"));
        }
        // Dedup by signature: first occurrence wins
        Map<String, SourceModel.MemberInfo> deduped = new LinkedHashMap<>();
        for (SourceModel.MemberInfo member : synthesized) {
            deduped.putIfAbsent(member.signature(), member);
        }
        return new Result(List.copyOf(deduped.values()), unknownLombok);
    }

    private static SourceModel.MemberInfo ctor(String typeName, List<String> paramTypes, String by) {
        return new SourceModel.MemberInfo("<init>",
                "<init>(" + String.join(",", paramTypes) + ")", typeName, "lombok:@" + by);
    }

    private static boolean isFinal(VariableDeclarator v) {
        return v.getParentNode()
                .filter(p -> p instanceof FieldDeclaration fd && fd.isFinal()).isPresent();
    }

    private static boolean importedFromLombok(TypeDeclaration<?> type, String simpleName) {
        return type.findCompilationUnit().map(CompilationUnit::getImports).stream()
                .flatMap(List::stream)
                .anyMatch(imp -> {
                    String name = imp.getNameAsString();
                    boolean lombokRoot = name.equals("lombok") || name.startsWith("lombok.");
                    if (!lombokRoot) {
                        return false;
                    }
                    if (!imp.isAsterisk()) {
                        // Single-type import: exact match
                        return name.endsWith("." + simpleName);
                    }
                    // Wildcard import: only count if in known Lombok annotations
                    return GENERATING.contains(simpleName) || IGNORED.contains(simpleName)
                            || KNOWN_LOMBOK_EXTRAS.contains(simpleName);
                });
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
