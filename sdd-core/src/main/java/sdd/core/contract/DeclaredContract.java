package sdd.core.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
                        + "'); declared contracts must be one of " + ContractKinds.describeDeclarable());
                continue;
            }
            switch (kind) {
                case ContractKinds.JAVA_API -> parseJavaApiLine(line, members, problems);
                case ContractKinds.REST -> parseRestLine(line, members, problems);
                case ContractKinds.KAFKA -> parseKafkaLine(line, members, problems);
                case ContractKinds.TS_API -> parseTsApiLine(line, members, problems);
                // Same grammar as `rest`: a verb and a path is a verb and a path whichever end of
                // the call declares it, and sharing the parser keeps the two from drifting.
                case ContractKinds.REST_CLIENT -> parseRestLine(line, members, problems);
                case ContractKinds.STREAM_DESCRIPTOR -> parseStreamLine(line, members, problems);
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
            case ContractKinds.TS_API -> canonicalizeTsApiActual(actualBody, out);
            case ContractKinds.REST_CLIENT -> canonicalizeRestActual(actualBody, out);
            case ContractKinds.STREAM_DESCRIPTOR -> canonicalizeStreamActual(actualBody, out);
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

    /** Marks an actual line whose value the actualizer could not resolve — see the 2026-08-14
     *  "unresolved extraction" amendment. Mirrors {@code
     *  sdd.cli.implement.ContractActualizer.UNRESOLVED_MARKER} exactly; sdd-core cannot depend on
     *  sdd-cli, so the literal is duplicated here the same way {@link #KAFKA_ROLES} already
     *  duplicates {@code KafkaExtractor}'s PRODUCER/CONSUMER vocabulary from sdd-index. */
    private static final String UNRESOLVED_MARKER = " [unresolved]";

    /** The canonical members of lines the actualizer marked {@link #UNRESOLVED_MARKER}, in the
     *  same space {@link #missingFrom} compares against. A declared member missing only because
     *  the matching actual entry is on this list is {@code NOT_RESOLVED} rather than
     *  {@code DIVERGED_FROM_PLAN} — extraction could not see it, which is not the same as it not
     *  existing. {@code java-api} has no unresolved shape (type extraction either sees a member
     *  or does not), so this is always empty for that kind. */
    public List<String> unresolvedMembers(String actualBody) {
        if (actualBody == null || actualBody.isBlank() || !ContractKinds.declarable(kind)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        switch (kind) {
            case ContractKinds.REST -> unresolvedRestMembers(actualBody, out);
            case ContractKinds.REST_CLIENT -> unresolvedRestMembers(actualBody, out);
            case ContractKinds.KAFKA -> unresolvedKafkaMembers(actualBody, out);
            case ContractKinds.TS_API -> unresolvedTsApiMembers(actualBody, out);
            case ContractKinds.STREAM_DESCRIPTOR -> unresolvedStreamMembers(actualBody, out);
            default -> { } // java-api: no unresolved shape exists
        }
        return out;
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
        if (fqcn.indexOf('.') < 0) {
            // ContractActualizer selects types by exact fqcn equality, so an unqualified name
            // selects nothing at all: the actualized body comes back empty and Gate 2 reports the
            // grossest divergence available for what is only a notation slip. Types elsewhere on
            // the line deliberately compare by simple name, which makes the fqcn the single place a
            // human must be exactly right — so say so here, at Gate 1, while it can still be fixed.
            problems.add("java-api declaration '" + line + "': the type '" + fqcn
                    + "' must be fully qualified (e.g. com.trading.pricing.core." + fqcn + ")"
                    + " — it is matched against the extracted type's full name, not its simple name");
            return;
        }
        // fqcn is the type's identity and stays fully qualified; only the signature's argument
        // types and the return type are reduced to simple names, matching ApiSurfaceExtractor.
        members.add(fqcn + "#" + normalizeTypes(signature) + ":" + normalizeTypes(returnType));
    }

    /**
     * {@code <moduleSpecifier>#<Export>[.<member>][(<params>)]: <type>}.
     *
     * <p>Shaped like the java-api line, with the module specifier where that carries a fully
     * qualified class name — the same role, played by whatever identifies a symbol across repos in
     * each ecosystem. It is checked for a slash or an @ for the same reason a Java fqcn is checked
     * for a dot: the actualizer selects by exact specifier, so a bare name selects nothing at all,
     * and Gate 2 would report the grossest divergence available for what is a notation slip.
     */
    private static void parseTsApiLine(String line, List<String> members, List<String> problems) {
        int hash = line.indexOf('#');
        int colon = line.lastIndexOf(':');
        if (hash < 0 || colon < hash) {
            problems.add(malformedTsApi(line));
            return;
        }
        String specifier = line.substring(0, hash).strip();
        String member = line.substring(hash + 1, colon).strip();
        String type = line.substring(colon + 1).strip();
        if (specifier.isEmpty() || member.isEmpty() || type.isEmpty()) {
            problems.add(malformedTsApi(line));
            return;
        }
        if (specifier.indexOf('/') < 0 && !specifier.startsWith("@")) {
            problems.add("ts-api declaration '" + line + "': '" + specifier
                    + "' must be the module specifier a consumer imports (e.g. @acme/web-sdk or"
                    + " @acme/web-sdk/contract) — it is matched against the package's published"
                    + " entry point, not a file path or a bare name");
            return;
        }
        members.add(specifier + "#" + normalizeTsTypes(member) + ":" + normalizeTsTypes(type));
    }

    private static String malformedTsApi(String line) {
        return "malformed ts-api declaration '" + line
                + "'; expected <moduleSpecifier>#<Export>[.<member>]: <type>";
    }

    private static void canonicalizeTsApiActual(String body, List<String> out) {
        String currentModule = null;
        for (String raw : body.split("\n", -1)) {
            if (raw.isBlank()) {
                continue;
            }
            String trimmed = raw.strip();
            if (trimmed.startsWith("#")) {
                continue;   // the "# actualized (ts-api)" header
            }
            if (Character.isWhitespace(raw.charAt(0))) {
                if (currentModule != null) {
                    out.add(currentModule + "#" + normalizeTsTypes(trimmed));
                }
            } else {
                currentModule = trimmed;
            }
        }
    }

    /**
     * Whitespace stripped, and {@code import("/abs/path").Foo} reduced to {@code Foo}.
     *
     * <p>The checker renders a cross-file type reference as an {@code import(...)} with an ABSOLUTE
     * path in it. Left alone, every member compares as divergent and the comparison's outcome
     * depends on where the repo happens to be checked out — so a contract that passed on one
     * machine would fail on another for no reason a reader could see.
     */
    static String normalizeTsTypes(String s) {
        return s.replaceAll("import\\([^)]*\\)\\.", "").replaceAll("\\s+", "");
    }

    /**
     * A ts-api member the actualizer could not resolve. Unlike java-api, TypeScript HAS an
     * unresolved shape: a type that degraded to {@code any} because the declaration it referred to
     * was not readable. Excusing a missing member needs the same module AND the same member —
     * sharing only the module excuses nothing, exactly as sharing only a verb or a role does on
     * the other kinds.
     */
    private static void unresolvedTsApiMembers(String body, List<String> out) {
        // The module header has to be tracked exactly as canonicalizeTsApiActual tracks it: an
        // unresolved entry is compared against the missing member, and a missing member always
        // carries its specifier. Emitting the bare member line here would produce entries that can
        // never match anything, silently disabling the excusal rule rather than tightening it.
        String currentModule = null;
        for (String raw : body.split("\n", -1)) {
            if (raw.isBlank()) {
                continue;
            }
            String trimmed = raw.strip();
            if (trimmed.startsWith("#")) {
                continue;
            }
            if (!Character.isWhitespace(raw.charAt(0))) {
                currentModule = trimmed;
                continue;
            }
            if (currentModule != null && trimmed.endsWith(UNRESOLVED_MARKER)) {
                out.add(currentModule + "#" + normalizeTsTypes(
                        trimmed.substring(0, trimmed.length() - UNRESOLVED_MARKER.length())));
            }
        }
    }

    // -- stream-descriptor ---------------------------------------------------------------------

    /** The two axes both sides of a stream registration can actually be read for. */
    private static final Set<String> STREAM_AXES = Set.of("key", "channels");

    /**
     * {@code <stream> <axis> <v1>,<v2>,…} — e.g. {@code md key clientId,securityType}.
     *
     * <p>Order is significant and is preserved: a key's field order decides how a subscription key
     * is encoded on the wire, and two ends that agree on the SET but not the ORDER produce keys
     * that never match. Comparing as an unordered set would call that agreement.
     */
    private static void parseStreamLine(String line, List<String> members, List<String> problems) {
        String[] parts = line.strip().split("\\s+", 3);
        if (parts.length < 3) {
            problems.add(malformedStream(line));
            return;
        }
        if (!STREAM_AXES.contains(parts[1])) {
            problems.add("stream-descriptor declaration '" + line + "': '" + parts[1]
                    + "' is not an axis — expected 'key' or 'channels'. Only those two are"
                    + " derivable from both the Java builders and the TypeScript built-ins, so"
                    + " only those two can be checked on both ends");
            return;
        }
        String values = normalizeStreamValues(parts[2]);
        if (values.isEmpty()) {
            problems.add(malformedStream(line));
            return;
        }
        members.add(parts[0] + " " + parts[1] + " " + values);
    }

    private static String malformedStream(String line) {
        return "malformed stream-descriptor declaration '" + line
                + "'; expected <stream> key|channels <value>,<value>,…";
    }

    /** Whitespace around the separators is a writing convenience, never a difference. */
    private static String normalizeStreamValues(String values) {
        List<String> out = new ArrayList<>();
        for (String value : values.split(",", -1)) {
            String trimmed = value.strip();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return String.join(",", out);
    }

    private static void canonicalizeStreamActual(String body, List<String> out) {
        for (String raw : body.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;   // the "# actualized (stream-descriptor)" header
            }
            if (line.endsWith(UNRESOLVED_MARKER)) {
                line = line.substring(0, line.length() - UNRESOLVED_MARKER.length()).strip();
            }
            String[] parts = line.split("\\s+", 3);
            if (parts.length == 3) {
                out.add(parts[0] + " " + parts[1] + " " + normalizeStreamValues(parts[2]));
            }
        }
    }

    /**
     * A stream axis one of whose values could not be read.
     *
     * <p>Keyed on stream AND axis, which together are the whole left-hand side — the only thing
     * extraction failed at is one entry IN the list, so a marked line means "this list could not
     * be confirmed" and nothing wider. It cannot excuse the other axis of the same stream, nor
     * the same axis of another stream.
     */
    private static void unresolvedStreamMembers(String body, List<String> out) {
        for (String raw : body.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || !line.endsWith(UNRESOLVED_MARKER)) {
                continue;
            }
            String[] parts = line.substring(0, line.length() - UNRESOLVED_MARKER.length())
                    .strip().split("\\s+", 3);
            if (parts.length >= 2) {
                out.add(parts[0] + " " + parts[1]);
            }
        }
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
            if (line.isEmpty() || line.startsWith("#")) {
                continue; // the "# actualized (rest)" header
            }
            int arrow = line.indexOf(" -> ");
            out.add(arrow >= 0 ? line.substring(0, arrow) : line);
        }
    }

    /** Only the verbless-{@code @RequestMapping} shape reaches here today: the verb {@code ANY},
     *  which {@link #REST_METHODS} can never legally declare, so it is unmatchable by
     *  {@link #missingFrom} on purpose. The marker sits after the {@code " -> "} handler suffix,
     *  so this still uses the same before-the-arrow canonical form {@link #canonicalizeRestActual}
     *  produces. */
    private static void unresolvedRestMembers(String body, List<String> out) {
        for (String raw : body.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || !line.endsWith(UNRESOLVED_MARKER)) {
                continue;
            }
            String stripped = line.substring(0, line.length() - UNRESOLVED_MARKER.length());
            int arrow = stripped.indexOf(" -> ");
            out.add(arrow >= 0 ? stripped.substring(0, arrow) : stripped);
        }
    }

    // -- kafka -----------------------------------------------------------------------------------

    /** The declared roles, and the {@code SpringModel.KafkaUse.role()} literals {@code KafkaExtractor}
     *  actually emits for each — the only two values that field ever takes. Both spellings are
     *  accepted on the declared side and canonicalize to the human one; the actual side is mapped
     *  onto the same space in {@link #canonicalizeKafkaActual}. Without this mapping every declared
     *  kafka contract compares {@code produces <topic>} against {@code PRODUCER <topic>} and reports
     *  DIVERGED_FROM_PLAN no matter how correct the implementation is. */
    // A LinkedHashMap, not Map.of: iteration order below feeds KAFKA_ROLE_VOCAB, and Map.of's
    // iteration order is salted per JVM run, which would make that message's wording
    // nondeterministic between runs.
    private static final Map<String, String> KAFKA_ROLES;
    static {
        Map<String, String> roles = new LinkedHashMap<>();
        roles.put("produces", "produces");
        roles.put("producer", "produces");
        roles.put("consumes", "consumes");
        roles.put("consumer", "consumes");
        KAFKA_ROLES = Collections.unmodifiableMap(roles);
    }
    /** The canonical role vocabulary — {@code KAFKA_ROLES}' distinct values, in declaration order —
     *  rendered into the error message the same way {@link #parseRestLine} renders
     *  {@code REST_METHODS}, so the literal cannot drift from the map. */
    private static final Set<String> KAFKA_ROLE_VOCAB = new LinkedHashSet<>(KAFKA_ROLES.values());

    private static void parseKafkaLine(String line, List<String> members, List<String> problems) {
        String[] parts = line.split("\\s+");
        if (parts.length != 2) {
            problems.add("malformed kafka declaration '" + line + "'; expected <role> <topic>");
            return;
        }
        String role = KAFKA_ROLES.get(parts[0].toLowerCase(Locale.ROOT));
        if (role == null) {
            problems.add("malformed kafka declaration '" + line
                    + "'; expected <role> <topic> with role one of " + KAFKA_ROLE_VOCAB);
            return;
        }
        members.add(role + " " + parts[1]);
    }

    /** The marker must be stripped before splitting on whitespace, not after: a marked line has
     *  three tokens ({@code role}, {@code topic}, {@code [unresolved]}), and leaving it in would
     *  make {@link #canonicalizeKafkaLine}'s {@code parts.length == 2} check see three, so
     *  {@code role} would come back {@code null} and the entire raw line — marker included — would
     *  go into the actual set as an unmatchable, uncanonicalized string. A declared {@code consumes
     *  <topic>} would then never be satisfied even when the implementation is genuinely correct and
     *  the marker is only present because {@code topicPattern} is unconditionally marked (see
     *  {@code KafkaExtractor}). */
    private static void canonicalizeKafkaActual(String body, List<String> out) {
        for (String raw : body.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue; // the "# actualized (kafka)" header
            }
            out.add(canonicalizeKafkaLine(stripUnresolvedMarker(line)));
        }
    }

    /** Two shapes reach here: a {@code @Value}/property-driven topic that genuinely did not
     *  resolve, and — because {@code KafkaExtractor} hardcodes {@code resolution() == "DYNAMIC"}
     *  for every {@code topicPattern} listener — a topic pattern whose text resolved perfectly
     *  well. A consumer of this list must therefore not treat an entry as "could have been any
     *  topic" (see {@code ContractRecheck.kafkaExplains}). The role half is never the unresolved
     *  one: it is a hardcoded {@code PRODUCER}/{@code CONSUMER} literal {@code KafkaExtractor}
     *  writes itself, never a resolved value. Shares
     *  {@link #canonicalizeKafkaLine} with {@link #canonicalizeKafkaActual} — the only difference
     *  is which lines pass the filter: every content line there, only marked ones here. */
    private static void unresolvedKafkaMembers(String body, List<String> out) {
        for (String raw : body.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || !line.endsWith(UNRESOLVED_MARKER)) {
                continue;
            }
            out.add(canonicalizeKafkaLine(stripUnresolvedMarker(line)));
        }
    }

    private static String stripUnresolvedMarker(String line) {
        return line.endsWith(UNRESOLVED_MARKER)
                ? line.substring(0, line.length() - UNRESOLVED_MARKER.length())
                : line;
    }

    private static String canonicalizeKafkaLine(String line) {
        String[] parts = line.split("\\s+");
        String role = parts.length == 2 ? KAFKA_ROLES.get(parts[0].toLowerCase(Locale.ROOT)) : null;
        return role == null ? line : role + " " + parts[1];
    }
}
