package sdd.plan.openspec;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The whole root change, byte for byte.
 *
 * <p>The unit suite asserts rules one at a time; this asserts what a human actually opens in the
 * workspace. It is also the same render the npx harness validates, so the bytes checked in here are
 * the bytes the real OpenSpec CLI was asked to accept.
 *
 * <p>Regenerate with
 * {@code ./gradlew :sdd-plan:test --tests "*EstateGoldenTest" -Dsdd.regenGolden=true}, then rerun
 * without the flag and READ THE DIFF. A golden that is regenerated without being read is a test
 * that asserts whatever the code currently does.
 */
class EstateGoldenTest {

    private static final Path GOLDEN = Path.of("src/test/resources/golden/estate");

    @Test
    void theRootChangeRendersExactlyTheCheckedInBytes() throws IOException {
        Map<String, String> rendered = EstateChangeFixture.rendered();

        if (Boolean.getBoolean("sdd.regenGolden")) {
            for (Map.Entry<String, String> file : rendered.entrySet()) {
                Path target = GOLDEN.resolve(file.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
            }
            fail("golden regenerated — rerun without -Dsdd.regenGolden to confirm it is green, "
                    + "and read the diff before committing it");
        }

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> file : rendered.entrySet()) {
            Path expected = GOLDEN.resolve(file.getKey());
            if (!Files.exists(expected)) {
                problems.add("no golden for " + file.getKey());
                continue;
            }
            assertThat(file.getValue())
                    .as("%s", file.getKey())
                    .isEqualTo(Files.readString(expected, StandardCharsets.UTF_8));
        }
        // A dropped file must fail too. Without this, deleting an artifact from the render silently
        // passes: nothing compares what is on disk against what was produced.
        try (var walk = Files.exists(GOLDEN) ? Files.walk(GOLDEN) : java.util.stream.Stream.<Path>of()) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !rendered.containsKey(GOLDEN.relativize(p).toString()))
                    .forEach(p -> problems.add("stale golden, no longer rendered: " + p));
        }
        assertThat(problems).as("regenerate with -Dsdd.regenGolden=true").isEmpty();
    }
}
