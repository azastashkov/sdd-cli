package sdd.agent.run;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InfraClassifierTest {
    @Test
    void matchesEachInfraFamily() {
        // Resolution failures only count as infra when a network cause co-occurs (precision rule).
        assertThat(InfraClassifier.isInfra(
                "exit 1\n* What went wrong:\nCould not resolve com.acme:lib:1.0.\nCaused by: java.net.UnknownHostException: repo.maven.apache.org"))
                .isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\nCould not download guava-33.0.jar\nConnection refused")).isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\nCould not GET 'https://repo.maven.apache.org/...'\nConnection reset by peer")).isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\njava.net.UnknownHostException: repo.maven.apache.org")).isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\nConnection refused (Connection refused)")).isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\nConnection reset by peer")).isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\nconnect timed out")).isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\nRead timed out")).isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\nNo route to host")).isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\nGradle build daemon disappeared unexpectedly")).isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\nUnable to start the daemon process.")).isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\nTimeout waiting to lock journal cache")).isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\nCannot connect to the Docker daemon at unix:///var/run/docker.sock")).isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\nNo space left on device")).isTrue();
        assertThat(InfraClassifier.isInfra("timed out after 900s")).isTrue();   // GradleTool's timeout string
    }

    @Test
    void realBuildFailuresAreNotInfra() {
        assertThat(InfraClassifier.isInfra("exit 1\nA.java:3: error: ';' expected")).isFalse();
        assertThat(InfraClassifier.isInfra("exit 1\n> Task :test FAILED\n3 tests completed, 1 failed")).isFalse();
        assertThat(InfraClassifier.isInfra("exit 0\nBUILD SUCCESSFUL")).isFalse();
        assertThat(InfraClassifier.isInfra("")).isFalse();
        assertThat(InfraClassifier.isInfra("exit 1\nCould not get unknown property 'foo' for root project")).isFalse();
    }

    @Test
    void resolutionFailuresWithoutANetworkCauseAreNotInfra() {
        // Live-smoke false positive: the repository answered and the artifact is absent (agent
        // bumped to a nonexistent version) — a build/config error, not infrastructure. See the
        // class javadoc for the incident this precision rule was added for.
        assertThat(InfraClassifier.isInfra("""
                FAILURE: Build failed with an exception.
                Could not determine the dependencies of task ':services:pricing-b:test'.
                > Could not resolve all dependencies for configuration ':services:pricing-b:testRuntimeClasspath'.
                   > Could not find com.trading:mock-pricing-venue:0.2.0-SNAPSHOT.
                """)).isFalse();

        assertThat(InfraClassifier.isInfra(
                "exit 1\nCould not resolve com.acme:lib\nCaused by: java.net.UnknownHostException: repo.maven.apache.org"))
                .isTrue();
        assertThat(InfraClassifier.isInfra(
                "exit 1\nCould not GET 'https://repo.maven.apache.org/x'\nConnection refused"))
                .isTrue();
    }
}
