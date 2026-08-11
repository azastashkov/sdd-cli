package sdd.index.gradle;

public final class ModeClassifier {
    private ModeClassifier() {}

    public static ConsumptionMode classify(String declaredVersion, boolean producerInIncludedBuilds) {
        if (producerInIncludedBuilds) {
            return ConsumptionMode.COMPOSITE;
        }
        if (declaredVersion == null) {
            return ConsumptionMode.BOM_MANAGED;
        }
        if (declaredVersion.endsWith("-SNAPSHOT")) {
            return ConsumptionMode.SNAPSHOT;
        }
        if (declaredVersion.contains("+")
                || declaredVersion.startsWith("latest.")
                || declaredVersion.startsWith("[")
                || declaredVersion.startsWith("(")) {
            return ConsumptionMode.DYNAMIC;
        }
        return ConsumptionMode.PINNED;
    }

    public static String declaredVia(String declaredVersion, boolean inCatalog) {
        if (declaredVersion == null) {
            return "BOM";
        }
        return inCatalog ? "CATALOG" : "DIRECT";
    }
}
