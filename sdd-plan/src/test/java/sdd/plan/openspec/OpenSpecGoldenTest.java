package sdd.plan.openspec;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The whole export for a real two-repo change, byte for byte.
 *
 * <p>The unit suite asserts rules one at a time; this asserts what a human would actually open in a
 * repository. It is also the fixture the npx harness validates, so the bytes checked in here are
 * the bytes the real OpenSpec CLI is asked to accept.
 *
 * <p>Regenerate with
 * {@code ./gradlew :sdd-plan:test --tests "*OpenSpecGoldenTest" -Dsdd.regenGolden=true}, then rerun
 * without the flag to confirm it is green, then read the diff before committing.
 */
class OpenSpecGoldenTest {

    private static final Path GOLDEN = Path.of("src/test/resources/golden/openspec");

    private static OpenSpecInput.Item item(String id, String text) {
        return new OpenSpecInput.Item(id, text);
    }

    private static final List<OpenSpecInput.Item> ACCEPTANCE = List.of(
            item("A1", "A tier update for a client makes the next resolution return the new tier "
                    + "without a restart."),
            item("A2", "svc-orders no longer serves a stale tier after a tier-update event."),
            item("A3", "The full estate rebuild is green."));

    private static final OpenSpecInput.Contract TIER_API = new OpenSpecInput.Contract(
            "tier-invalidation-api", "java-api", "pricing-core", List.of("svc-orders"),
            "TierResolver gains:\n  invalidate(clientId: String): void\nresolveTier(String): ClientTier",
            "binary-compatible",
            List.of("com.trading.pricing.core.TierResolver#invalidate(String): void"));

    private static OpenSpecInput provider() {
        return new OpenSpecInput("spec-tier-invalidation-v1", "pricing-core", "SEED",
                List.of("pricing-core", "svc-orders"),
                List.of(List.of("pricing-core"), List.of("svc-orders")),
                "SPEC-TIER-INVALIDATION", 1, "Invalidate cached client tiers when a tier update arrives",
                "Tier updates consumed by pricing-core do not take effect until the service "
                        + "restarts, because the resolved tier is cached for the lifetime of the process.",
                "", List.of("Changing how tiers are computed."),
                List.of(item("C1", "No schema change to the pricing database.")), List.of(),
                List.of(item("R1", "pricing-core must expose a way to invalidate a cached client "
                        + "tier so a tier update takes effect without a restart.")),
                ACCEPTANCE,
                new OpenSpecPlan("tier-resolution", Map.of("R1", List.of("A1", "A3")), List.of()),
                "Add an invalidate(clientId) entry point to TierResolver and drop the memoized "
                        + "entry in JdbcTierResolver. Keep the existing resolveTier signature untouched.",
                List.of("src/main/java/com/trading/pricing/core/JdbcTierResolver.java"),
                List.of("./gradlew :pricing-core:test"), "minor",
                List.of(TIER_API), List.of(), List.of(), "a1b2c3d4e5f6", false,
                Map.of("R2", "svc-orders"));
    }

    private static OpenSpecInput consumer() {
        return new OpenSpecInput("spec-tier-invalidation-v1", "svc-orders", "CODE_CHANGE_LIKELY",
                List.of("pricing-core", "svc-orders"),
                List.of(List.of("pricing-core"), List.of("svc-orders")),
                "SPEC-TIER-INVALIDATION", 1, "Invalidate cached client tiers when a tier update arrives",
                "Tier updates consumed by pricing-core do not take effect until the service "
                        + "restarts, because the resolved tier is cached for the lifetime of the process.",
                "", List.of("Changing how tiers are computed."),
                List.of(item("C1", "No schema change to the pricing database.")), List.of(),
                List.of(item("R2", "svc-orders must invalidate the cached tier when it handles a "
                        + "tier-update event.")),
                ACCEPTANCE,
                new OpenSpecPlan("tier-consumption", Map.of("R2", List.of("A2", "A3")), List.of()),
                "Call TierResolver.invalidate from the tier-update handler.",
                List.of("src/main/java/com/trading/orders/TierUpdateHandler.java"),
                List.of("./gradlew :svc-orders:test"), "none",
                List.of(), List.of(TIER_API), List.of(), "b2c3d4e5f6a1", false,
                Map.of("R1", "pricing-core"));
    }

    @Test
    void theExportMatchesTheGolden() throws IOException {
        Map<String, String> actual = new java.util.LinkedHashMap<>();
        for (OpenSpecInput in : List.of(provider(), consumer())) {
            OpenSpecChange.render(in).byPath(in.changeId())
                    .forEach((path, body) -> actual.put(in.repo() + "/" + path, body));
        }

        if (Boolean.getBoolean("sdd.regenGolden")) {
            for (Map.Entry<String, String> file : actual.entrySet()) {
                Path target = GOLDEN.resolve(file.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
            }
            fail("golden regenerated — rerun without -Dsdd.regenGolden to confirm it is green, "
                    + "then read the diff before committing " + GOLDEN.toAbsolutePath());
        }

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> file : actual.entrySet()) {
            Path target = GOLDEN.resolve(file.getKey());
            if (!Files.exists(target)) {
                problems.add("missing golden: " + file.getKey());
            } else if (!Files.readString(target, StandardCharsets.UTF_8).equals(file.getValue())) {
                problems.add("differs: " + file.getKey());
            }
        }
        try (var walk = Files.exists(GOLDEN) ? Files.walk(GOLDEN) : java.util.stream.Stream.<Path>of()) {
            walk.filter(Files::isRegularFile)
                    .map(p -> GOLDEN.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> !actual.containsKey(p))
                    .forEach(p -> problems.add("stale golden, no longer rendered: " + p));
        }
        if (!problems.isEmpty()) {
            fail(String.join("\n", problems) + "\n\nregenerate with: ./gradlew :sdd-plan:test "
                    + "--tests \"*OpenSpecGoldenTest\" -Dsdd.regenGolden=true");
        }
    }
}
