package sdd.plan.approve;

import java.nio.file.Path;

/** Approve-time probe: can this consumer build with the provider substituted via --include-build? */
public interface SmokeRunner {

    Result probe(Path consumerRepo, Path providerRepo);

    record Result(boolean ok, String detail) {
    }
}
