package sdd.core.contract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A plan-approved interface contract, parsed into the exact vocabulary
 * {@code sdd.cli.implement.ContractActualizer} emits, so a Gate-2 re-check can compare the two by
 * plain string equality instead of prose diffing. Grammar is deliberately narrow: a contract may
 * declare only what the actualizer can re-derive from source (design line 62 / M4's successor).
 * Pure value type — no I/O, no logging.
 */
public record DeclaredContract(String kind, List<String> members, List<String> problems) {

    public DeclaredContract {
        Objects.requireNonNull(kind, "kind");
        members = List.copyOf(members);
        problems = List.copyOf(problems);
    }

    private static final Set<String> REST_METHODS = new LinkedHashSet<>(
            List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"));

    /** Every maximal run of package/class characters, reduced to the text after its last '.'. */
    private static final Pattern TYPE_TOKEN = Pattern.compile("[A-Za-z0-9_.$]+");

    /** Blank text is {@code NOT_DECLARED} — empty members, no problems, never an error. */
    public static DeclaredContract parse(String kind, String declaredText) {
        List<String> members = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (String raw : declaredText.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (!ContractKinds.declarable(kind)) {
                problems.add("unknown contract kind '" + kind + "' (line: '" + line
                        + "'); declared contracts must be one of " + ContractKinds.JAVA_API + ", "
                        + ContractKinds.REST + ", " + ContractKinds.KAFKA);
                continue;
            }
            switch (kind) {
                case ContractKinds.JAVA_API -> parseJavaApiLine(line, members, problems);
                case ContractKinds.REST -> parseRestLine(line, members, problems);
                case ContractKinds.KAFKA -> parseKafkaLine(line, members, problems);
                default -> throw new IllegalStateException("unreachable: " + kind);
            }
        }
        return new DeclaredContract(kind, members, problems);
    }

    /** Canonicalizes {@code ContractActualizer}'s own output into the same space as {@link #parse}. */
    public static List<String> canonicalizeActual(String kind, String actualBody) {
        if (actualBody == null || actualBody.isBlank() || !ContractKinds.declarable(kind)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        switch (kind) {
            case ContractKinds.JAVA_API -> canonicalizeJavaApiActual(actualBody, out);
            case ContractKinds.REST -> canonicalizeRestActual(actualBody, out);
            case ContractKinds.KAFKA -> canonicalizeKafkaActual(actualBody, out);
            default -> throw new IllegalStateException("unreachable: " + kind);
        }
        return out;
    }

    /** Declared members with no match in {@code actualBody}, in declaration order. Extras in the
     *  actual body are never divergence — containment is one-directional, declared-into-actual. */
    public List<String> missingFrom(String actualBody) {
        Set<String> actual = new HashSet<>(canonicalizeActual(kind, actualBody));
        List<String> missing = new ArrayList<>();
        for (String member : members) {
            if (!actual.contains(member)) {
                missing.add(member);
            }
        }
        return missing;
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    // -- java-api ------------------------------------------------------------------------------

    private static void parseJavaApiLine(String line, List<String> members, List<String> problems) {
        int openParen = line.indexOf('(');
        int hash = line.indexOf('#');
        int closeParen = openParen >= 0 ? line.indexOf(')', openParen) : -1;
        int colon = closeParen >= 0 ? line.indexOf(':', closeParen) : -1;
        if (openParen < 0 || hash < 0 || hash > openParen || closeParen < 0 || colon < 0) {
            problems.add(malformedJavaApi(line));
            return;
        }
        String fqcn = line.substring(0, hash).strip();
        String signature = line.substring(hash + 1, colon).strip();
        String returnType = line.substring(colon + 1).strip();
        if (fqcn.isEmpty() || signature.isEmpty() || returnType.isEmpty()) {
            problems.add(malformedJavaApi(line));
            return;
        }
        // fqcn is the type's identity and stays fully qualified; only the signature's argument
        // types and the return type are reduced to simple names, matching ApiSurfaceExtractor.
        members.add(fqcn + "#" + normalizeTypes(signature) + ":" + normalizeTypes(returnType));
    }

    private static String malformedJavaApi(String line) {
        return "malformed java-api declaration '" + line + "'; expected <fqcn>#<signature>: <returnType>";
    }

    private static void canonicalizeJavaApiActual(String body, List<String> out) {
        String currentType = null;
        for (String raw : body.split("\n", -1)) {
            if (raw.isBlank()) {
                continue;
            }
            String trimmed = raw.strip();
            if (trimmed.startsWith("#")) {
                continue; // the "# actualized (java-api)" header
            }
            if (Character.isWhitespace(raw.charAt(0))) {
                if (currentType != null) {
                    out.add(currentType + "#" + normalizeTypes(trimmed));
                }
            } else {
                currentType = trimmed;
            }
        }
    }

    /** Reduces every dotted type token to its simple name (including inside generics) and strips
     *  all whitespace, so {@code java.util.Optional<com.trading.model.Tier>} and
     *  {@code Optional<Tier>} canonicalize identically. A trailing Java varargs ellipsis is
     *  stripped before the dot-reduction: {@code ApiSurfaceExtractor} (and so
     *  {@code ContractActualizer}) emits a varargs parameter as its bare component type with no
     *  {@code ...}, so a declared {@code String...} must canonicalize to the same {@code String}
     *  the actual side produces — otherwise a correctly implemented varargs method is reported as
     *  a false MISSING purely because a human wrote the parameter the conventional Java way. */
    private static String normalizeTypes(String s) {
        Matcher matcher = TYPE_TOKEN.matcher(s);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            sb.append(s, last, matcher.start());
            String token = matcher.group();
            if (token.endsWith("...")) {
                token = token.substring(0, token.length() - 3);
            }
            int dot = token.lastIndexOf('.');
            sb.append(dot >= 0 ? token.substring(dot + 1) : token);
            last = matcher.end();
        }
        sb.append(s, last, s.length());
        return sb.toString().replaceAll("\\s+", "");
    }

    // -- rest ------------------------------------------------------------------------------------

    private static void parseRestLine(String line, List<String> members, List<String> problems) {
        int space = line.indexOf(' ');
        String method = space >= 0 ? line.substring(0, space) : line;
        String path = space >= 0 ? line.substring(space + 1).strip() : "";
        if (space < 0 || !REST_METHODS.contains(method) || path.isEmpty()) {
            problems.add("malformed rest declaration '" + line
                    + "'; expected <METHOD> <path> with METHOD one of " + REST_METHODS);
            return;
        }
        members.add(method + " " + path);
    }

    private static void canonicalizeRestActual(String body, List<String> out) {
        for (String raw : body.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            int arrow = line.indexOf(" -> ");
            out.add(arrow >= 0 ? line.substring(0, arrow) : line);
        }
    }

    // -- kafka -----------------------------------------------------------------------------------

    /** The declared roles, and the {@code SpringModel.KafkaUse.role()} literals {@code KafkaExtractor}
     *  actually emits for each — the only two values that field ever takes. Both spellings are
     *  accepted on the declared side and canonicalize to the human one; the actual side is mapped
     *  onto the same space in {@link #canonicalizeKafkaActual}. Without this mapping every declared
     *  kafka contract compares {@code produces <topic>} against {@code PRODUCER <topic>} and reports
     *  DIVERGED_FROM_PLAN no matter how correct the implementation is. */
    private static final Map<String, String> KAFKA_ROLES = Map.of(
            "produces", "produces", "producer", "produces",
            "consumes", "consumes", "consumer", "consumes");

    private static void parseKafkaLine(String line, List<String> members, List<String> problems) {
        String[] parts = line.split("\\s+");
        String role = parts.length == 2 ? KAFKA_ROLES.get(parts[0].toLowerCase(Locale.ROOT)) : null;
        if (role == null) {
            problems.add("malformed kafka declaration '" + line
                    + "'; expected <role> <topic> with role one of [produces, consumes]");
            return;
        }
        members.add(role + " " + parts[1]);
    }

    private static void canonicalizeKafkaActual(String body, List<String> out) {
        for (String raw : body.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue; // the "# actualized (kafka)" header
            }
            String[] parts = line.split("\\s+");
            String role = parts.length == 2 ? KAFKA_ROLES.get(parts[0].toLowerCase(Locale.ROOT)) : null;
            out.add(role == null ? line : role + " " + parts[1]);
        }
    }
}
