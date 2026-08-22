package sdd.plan.openspec;

import sdd.plan.gen.ExecutionOrder;
import sdd.plan.gen.PlanDrafter;
import sdd.plan.gen.Question;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactResult;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;
import sdd.plan.spec.Touchpoint;

import java.util.List;
import java.util.Map;

/**
 * The two-repo estate change, shared by the unit test and the npx harness.
 *
 * <p>One definition, because the harness must validate the very thing the unit test asserts about.
 * Two fixtures would let the validated bytes and the asserted bytes drift apart, which is the
 * failure mode that makes a conformance test decorative.
 */
public final class EstateChangeFixture {

    private EstateChangeFixture() {
    }

    public static NormalizedSpec spec() {
        return new NormalizedSpec("SPEC-TIERS", "Invalidate cached client tiers", "ana", "draft",
                "Tier updates do not take effect until the service restarts.",
                "Pricing caches the resolved tier for the process lifetime.",
                List.of(new SpecItem("R1", "pricing-core must expose a way to invalidate a tier."),
                        new SpecItem("R2", "svc-orders must invalidate on a tier-update event.")),
                List.of(new SpecItem("A1", "The next resolution returns the new tier."),
                        new SpecItem("A2", "The estate rebuild is green.")),
                List.of(new SpecItem("C1", "No schema change to the pricing database.")),
                List.of(new Touchpoint(Touchpoint.Kind.REPO, "pricing-core")),
                List.of("redis channel — pricing-core/src/main/java/Q.java:88"),
                List.of("Changing how tiers are computed."),
                List.of(new SpecItem("Q1", "Who owns the tier config?")), List.of(), List.of());
    }

    public static ImpactResult result() {
        return new ImpactResult(List.of(),
                List.of(new AffectedRepo("pricing-core", "seed", "SEED", List.of("R1"),
                                List.of("touchpoint")),
                        new AffectedRepo("svc-orders", "dependent", "CODE_CHANGE_LIKELY",
                                List.of("R2"), List.of("depends on pricing-core"))),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public static PlanDrafter.Draft draft() {
        return new PlanDrafter.Draft("Add invalidate() and call it on the event.",
                List.of(new PlanDrafter.DraftStep("pricing-core", List.of("R1"), "Add invalidate.",
                                List.of("src/main/java/TierResolver.java"), List.of("tier-api"),
                                List.of(), "minor", List.of(":pricing-core:test"),
                                List.of("capability: tier-resolution", "R1 -> A1")),
                        new PlanDrafter.DraftStep("svc-orders", List.of("R2"), "Handle the event.",
                                List.of("src/main/java/Handler.java"), List.of(), List.of("tier-api"),
                                "none", List.of(":svc-orders:test"),
                                List.of("capability: tier-consumption", "R2 -> A2"))),
                List.of(new PlanDrafter.DraftContract("tier-api", "java-api", "pricing-core",
                        List.of("svc-orders"), "TierResolver gains invalidate(String): void",
                        "binary-compatible",
                        List.of("com.acme.TierResolver#invalidate(String): void"))),
                List.of(new Question("Which tenant?", true)), List.of("a drafter note"), false);
    }

    public static List<ExecutionOrder.Unit> order() {
        return List.of(new ExecutionOrder.Unit(List.of("pricing-core")),
                new ExecutionOrder.Unit(List.of("svc-orders")));
    }

    public static Map<String, String> rendered() {
        List<OpenSpecInput> inputs = EstateInputs.forDraft(spec(), result(), order(), draft(), 1,
                Map.of("pricing-core", "a1b2c3d4e5f6", "svc-orders", "0badc0ffee11"));
        return EstateChange.render(spec(), result(), order(), List.of(), draft(), 1, inputs);
    }
}
