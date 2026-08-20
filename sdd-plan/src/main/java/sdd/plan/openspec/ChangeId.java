package sdd.plan.openspec;

/**
 * The OpenSpec change id for one sdd plan: {@code kebab(specId) + "-v" + planVersion}.
 *
 * <p>This is deliberately the run id in kebab form. {@code ImplementCommand} derives
 * {@code runId = sanitize(plan.specId()) + "-v" + plan.planVersion()}, so a human reading
 * {@code openspec/changes/spec-101-v2/} in a repo and {@code .sdd/runs/SPEC-101-v2/} in the
 * workspace is looking at the same change, and neither name needs a lookup table.
 *
 * <p>Two properties matter, and they pull in opposite directions on purpose:
 *
 * <ul>
 *   <li><b>Identical across every affected repo.</b> OpenSpec has no cross-repo linkage of any kind
 *       — its "stores" feature explicitly does not route work to repositories — so the shared id is
 *       the only thing connecting one repo's slice to another's. It is a convention, and
 *       {@code proposal.md} states it in prose rather than inventing a YAML key the validator would
 *       reject.
 *   <li><b>Different across plan versions.</b> {@code sdd plan revise} bumps {@code plan_version},
 *       and a v1 change may already be committed in some repo. Reusing the id would have v2 either
 *       silently overwrite a landed change or collide with it; a new id leaves both visible, which
 *       is what a human needs in order to archive one and apply the other.
 * </ul>
 */
public final class ChangeId {

    private ChangeId() {
    }

    /** @throws IllegalArgumentException if {@code planVersion} is not positive */
    public static String of(String specId, int planVersion) {
        if (planVersion < 1) {
            throw new IllegalArgumentException("plan version must be positive, got " + planVersion);
        }
        return Kebab.of(specId) + "-v" + planVersion;
    }
}
