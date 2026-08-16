package sdd.core.contract;

/** The interface-contract kinds Gate 2's actualizer can extract, and nothing else. A contract
 *  may only declare what the actualizer can re-derive from source — response types, status codes
 *  and handler classes are deliberately absent because there is no check that could enforce them. */
public final class ContractKinds {
    public static final String JAVA_API = "java-api";
    public static final String REST = "rest";
    public static final String KAFKA = "kafka";

    /**
     * A TypeScript package's exported surface, addressed by the specifier a consumer imports:
     * {@code @acme/web-sdk/contract#ShellContext.sdk: WebSdk}. The npm counterpart of
     * {@code java-api}, and it uses the module specifier where that uses a fully-qualified class
     * name because that is what identifies a symbol across repos in each ecosystem.
     */
    public static final String TS_API = "ts-api";

    /**
     * The HTTP calls a repo MAKES, as opposed to {@code rest}, which declares the endpoints a repo
     * SERVES. A separate kind rather than a second axis on {@code rest} because the re-check
     * actualizes {@code contract.provider()} and nothing else: making a consumer's declarations
     * checkable this way needs no structural change at all, whereas a per-consumer axis would
     * change what a finding is.
     */
    public static final String REST_CLIENT = "rest-client";

    private static final java.util.Set<String> DECLARABLE =
            java.util.Set.of(JAVA_API, REST, KAFKA, TS_API, REST_CLIENT);

    private ContractKinds() {
    }

    public static boolean declarable(String kind) {
        return DECLARABLE.contains(kind);
    }

    /** For error messages that list what a contract may be. */
    public static String describeDeclarable() {
        return String.join(", ", JAVA_API, REST, KAFKA, TS_API, REST_CLIENT);
    }
}
