package sdd.agent.run;

import org.junit.jupiter.api.Test;
import sdd.core.toolchain.Toolchain;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cost of getting this wrong is asymmetric, which is why the list excludes more than it
 * includes. Calling an agent-caused error "infra" pauses the run and waits for a human who has
 * nothing to fix; calling a genuine outage a build error makes the agent retry against a network
 * that is not there until its budget is gone.
 */
class NpmInfraClassifierTest {

    private static boolean npm(String log) {
        return InfraClassifier.isInfra(log, Toolchain.NPM);
    }

    @Test
    void networkAndMachineFailuresAreInfra() {
        assertThat(npm("npm ERR! code ENOTFOUND\nnpm ERR! network request to https://registry.npmjs.org failed"))
                .isTrue();
        assertThat(npm("npm ERR! code ECONNREFUSED")).isTrue();
        assertThat(npm("npm ERR! network timeout at: https://registry.npmjs.org/react")).isTrue();
        assertThat(npm("npm ERR! code EACCES\nnpm ERR! syscall mkdir")).isTrue();
        assertThat(npm("npm ERR! nospc ENOSPC: no space left")).isTrue();
        assertThat(npm("FATAL ERROR: Reached heap limit Allocation failed")).isTrue();
    }

    @Test
    void aRegistryThatAnsweredIsNotInfra() {
        // The registry replied; the package or version simply does not exist, which usually means
        // the agent wrote a name or a range that is wrong. That is feedback, not a pause.
        assertThat(npm("npm ERR! code E404\nnpm ERR! 404 Not Found - GET https://registry.npmjs.org/@acme%2fnope"))
                .isFalse();
        assertThat(npm("npm ERR! code ERESOLVE\nnpm ERR! ERESOLVE unable to resolve dependency tree"))
                .isFalse();
        assertThat(npm("npm ERR! code ETARGET\nnpm ERR! notarget No matching version found for react@^99"))
                .isFalse();
    }

    @Test
    void anImportTheAgentGotWrongIsNotInfra() {
        assertThat(npm("Error: Cannot find module './missing.js'")).isFalse();
        assertThat(npm("exit 1\nsrc/a.ts(2,14): error TS2304: Cannot find name 'c'.")).isFalse();
    }

    @Test
    void aFailingTestRunIsNotInfra() {
        assertThat(npm("exit 1\n   × math > adds 1ms\n     → expected 2 to be 3\n")).isFalse();
    }

    @Test
    void theSharedTimeoutMarkerIsInfraInBothEcosystems() {
        assertThat(npm("timed out after 900s")).isTrue();
        assertThat(InfraClassifier.isInfra("timed out after 900s")).isTrue();
    }

    @Test
    void gradleClassificationIsUnchanged() {
        // The npm vocabulary must not leak into the Gradle path, and vice versa: "could not
        // resolve" still needs a network cause alongside it to count as infra.
        assertThat(InfraClassifier.isInfra("Could not resolve com.acme:lib:9.9.9")).isFalse();
        assertThat(InfraClassifier.isInfra(
                "Could not resolve com.acme:lib:1.0\nCaused by: java.net.UnknownHostException")).isTrue();
        assertThat(InfraClassifier.isInfra("Gradle build daemon disappeared unexpectedly")).isTrue();
        // An npm-only marker is not a Gradle infra signal.
        assertThat(InfraClassifier.isInfra("npm ERR! code ENOTFOUND")).isFalse();
    }
}
