package sdd.core.contract;

/** The interface-contract kinds Gate 2's actualizer can extract, and nothing else. A contract
 *  may only declare what the actualizer can re-derive from source — response types, status codes
 *  and handler classes are deliberately absent because there is no check that could enforce them. */
public final class ContractKinds {
    public static final String JAVA_API = "java-api";
    public static final String REST = "rest";
    public static final String KAFKA = "kafka";

    private ContractKinds() {
    }

    public static boolean declarable(String kind) {
        return JAVA_API.equals(kind) || REST.equals(kind) || KAFKA.equals(kind);
    }
}
