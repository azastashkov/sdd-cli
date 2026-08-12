package sdd.cli.implement;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PropagationTest {
    // from=consumer, to=provider. svc consumes lib (INCLUDE_BUILD) and legacy (MAVEN_LOCAL); app consumes svc (NONE).
    private static final List<PlanModel.PlanEdge> EDGES = List.of(
            new PlanModel.PlanEdge("svc", "lib", "SNAPSHOT", "INCLUDE_BUILD"),
            new PlanModel.PlanEdge("svc", "legacy", "PINNED", "MAVEN_LOCAL"),
            new PlanModel.PlanEdge("app", "svc", "COMPOSITE", "NONE"));
    private static final Map<String, Path> PATHS = Map.of(
            "lib", Path.of("/w/lib"), "legacy", Path.of("/w/legacy"), "svc", Path.of("/w/svc"));

    @Test
    void injectsOnlyIncludeBuildProviders() {
        assertThat(Propagation.includeBuildArgs("svc", EDGES, PATHS))
                .containsExactly("--include-build", "/w/lib");   // NOT legacy (MAVEN_LOCAL)
    }

    @Test
    void noFlagsForARepoWithNoIncludeBuildInboundEdges() {
        assertThat(Propagation.includeBuildArgs("app", EDGES, PATHS)).isEmpty();   // its edge is NONE
        assertThat(Propagation.includeBuildArgs("lib", EDGES, PATHS)).isEmpty();   // provider only, no inbound
    }

    @Test
    void skipsAProviderMissingFromTheKb() {
        List<PlanModel.PlanEdge> edges = List.of(
                new PlanModel.PlanEdge("svc", "ghost", "SNAPSHOT", "INCLUDE_BUILD"));
        assertThat(Propagation.includeBuildArgs("svc", edges, PATHS)).isEmpty();
    }

    @Test
    void injectsTransitiveIncludeBuildProviders() {
        // svc -> lib -> core, both INCLUDE_BUILD, no direct svc -> core edge: svc's composite must still
        // pull in core, or lib builds inside svc's composite against a stale binary core.
        List<PlanModel.PlanEdge> edges = List.of(
                new PlanModel.PlanEdge("svc", "lib", "SNAPSHOT", "INCLUDE_BUILD"),
                new PlanModel.PlanEdge("lib", "core", "SNAPSHOT", "INCLUDE_BUILD"));
        Map<String, Path> paths = Map.of("lib", Path.of("/w/lib"), "core", Path.of("/w/core"));
        assertThat(Propagation.includeBuildArgs("svc", edges, paths))
                .containsExactly("--include-build", "/w/lib", "--include-build", "/w/core");
    }

    @Test
    void stopsTheClosureAtAMavenLocalHop() {
        List<PlanModel.PlanEdge> edges = List.of(
                new PlanModel.PlanEdge("svc", "lib", "SNAPSHOT", "INCLUDE_BUILD"),
                new PlanModel.PlanEdge("lib", "core", "PINNED", "MAVEN_LOCAL"));
        Map<String, Path> paths = Map.of("lib", Path.of("/w/lib"), "core", Path.of("/w/core"));
        assertThat(Propagation.includeBuildArgs("svc", edges, paths))
                .containsExactly("--include-build", "/w/lib");   // NOT core: MAVEN_LOCAL breaks the chain
    }

    @Test
    void dedupesASharedProviderInADiamondClosure() {
        // svc -> a -> core and svc -> b -> core: core must appear exactly once.
        List<PlanModel.PlanEdge> edges = List.of(
                new PlanModel.PlanEdge("svc", "a", "SNAPSHOT", "INCLUDE_BUILD"),
                new PlanModel.PlanEdge("svc", "b", "SNAPSHOT", "INCLUDE_BUILD"),
                new PlanModel.PlanEdge("a", "core", "SNAPSHOT", "INCLUDE_BUILD"),
                new PlanModel.PlanEdge("b", "core", "SNAPSHOT", "INCLUDE_BUILD"));
        Map<String, Path> paths = Map.of("a", Path.of("/w/a"), "b", Path.of("/w/b"), "core", Path.of("/w/core"));
        assertThat(Propagation.includeBuildArgs("svc", edges, paths))
                .containsExactly(
                        "--include-build", "/w/a",
                        "--include-build", "/w/b",
                        "--include-build", "/w/core");
    }
}
