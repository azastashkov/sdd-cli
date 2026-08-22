package sdd.plan.openspec;

import sdd.plan.approve.EstateYaml;

import java.util.Map;

/**
 * Bridges the shared fixture across packages, so the plan-time estate and the rendered markdown are
 * asserted against the SAME inputs. Two fixtures would let the two halves of one change directory
 * drift apart while both tests stayed green.
 */
public final class EstateChangeFixtureAccess {

    private EstateChangeFixtureAccess() {
    }

    public static String planTimeEstate() {
        return EstateYaml.fromDraft(EstateChangeFixture.spec(), EstateChangeFixture.result(),
                EstateChangeFixture.order(), java.util.List.of(), EstateChangeFixture.draft(), 1,
                Map.of("pricing-core", "a1b2c3d4e5f6", "svc-orders", "0badc0ffee11"));
    }
}
