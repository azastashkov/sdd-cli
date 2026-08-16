package sdd.core.toolchain;

/**
 * How a consumer is made to build against a provider's CHANGED source rather than the artifact it
 * would normally resolve. Frozen into {@code plan.json} at Gate 1 and read back on every run,
 * including a {@code --resume} of a plan approved by an older binary.
 *
 * <p>The values stay plain strings at the JSON boundary for exactly that reason; this type exists so
 * the comparisons are not scattered string literals, and so an unrecognised value has one defined
 * meaning. {@link #of} answers {@link #NONE} for anything it does not know, which is the safe
 * direction: injecting nothing is always recoverable, whereas guessing at a substitution mechanism
 * would build a consumer against something nobody chose.
 */
public enum Mechanism {
    /** Nothing to inject: already composed, or no substitution is possible across ecosystems. */
    NONE,
    /** Gradle composite build — {@code --include-build <provider>} on the consumer's invocation. */
    INCLUDE_BUILD,
    /** Gradle fallback — the provider publishes to a run-scoped m2 the consumer resolves from. */
    MAVEN_LOCAL,
    /**
     * npm workspaces already resolve the provider to live source through a symlink, so there is
     * nothing to inject. The npm counterpart of INCLUDE_BUILD, and free for the same reason.
     */
    WORKSPACE_LINK,
    /**
     * The provider is packed at its planned version and unpacked over the consumer's
     * {@code node_modules/<package>}, then restored. The npm counterpart of MAVEN_LOCAL: a
     * run-scoped substitution that leaves the repository as it found it.
     */
    NPM_OVERLAY;

    public static Mechanism of(String recorded) {
        if (recorded == null) {
            return NONE;
        }
        for (Mechanism mechanism : values()) {
            if (mechanism.name().equals(recorded)) {
                return mechanism;
            }
        }
        return NONE;
    }

    /** Whether this mechanism needs the provider to produce something before consumers build. */
    public boolean requiresProviderArtifact() {
        return this == MAVEN_LOCAL || this == NPM_OVERLAY;
    }
}
