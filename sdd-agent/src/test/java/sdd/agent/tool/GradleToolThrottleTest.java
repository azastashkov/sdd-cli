package sdd.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;

class GradleToolThrottleTest {
    @TempDir Path ws;

    private Path repoWith(String name, String script) throws Exception {
        Path repo = Files.createDirectories(ws.resolve(name));
        Path gradlew = repo.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        return repo;
    }

    @Test
    void aSingletonSemaphoreSerializesConcurrentGradleRuns() throws Exception {
        // Overlap-marker scheme (portable — BSD date has no %N): each stub flags itself as
        // running, records an overlap file if it ever sees the other's flag, and unflags on exit.
        // Under a 1-permit semaphore the overlap file must never appear.
        Path overlap = ws.resolve("overlap");
        String scriptA = "[ -f " + ws.resolve("b.running") + " ] && touch " + overlap + "; "
                + "touch " + ws.resolve("a.running") + "; sleep 0.3; "
                + "[ -f " + ws.resolve("b.running") + " ] && touch " + overlap + "; "
                + "rm -f " + ws.resolve("a.running") + "; exit 0";
        String scriptB = "[ -f " + ws.resolve("a.running") + " ] && touch " + overlap + "; "
                + "touch " + ws.resolve("b.running") + "; sleep 0.3; "
                + "[ -f " + ws.resolve("a.running") + " ] && touch " + overlap + "; "
                + "rm -f " + ws.resolve("b.running") + "; exit 0";
        Path a = repoWith("a", scriptA);
        Path b = repoWith("b", scriptB);
        Semaphore permits = new Semaphore(1);
        GradleTool toolA = new GradleTool(a, null, Duration.ofMinutes(1), List.of(), permits);
        GradleTool toolB = new GradleTool(b, null, Duration.ofMinutes(1), List.of(), permits);

        Thread threadA = Thread.ofVirtual().start(() -> toolA.run("check"));
        Thread threadB = Thread.ofVirtual().start(() -> toolB.run("check"));
        threadA.join();
        threadB.join();

        assertThat(Files.exists(overlap))
                .as("runs must not overlap under a 1-permit semaphore").isFalse();
    }
}
