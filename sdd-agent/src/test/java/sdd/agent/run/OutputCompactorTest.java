package sdd.agent.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OutputCompactorTest {
    @TempDir Path repo;

    private void writeReport(String name, String body) throws Exception {
        Path results = Files.createDirectories(repo.resolve("svc/build/test-results/test"));
        Files.writeString(results.resolve(name), body);
    }

    @Test
    void scrapesJavacErrorsFromRawOutput() {
        String raw = """
                exit 1
                > Task :compileJava FAILED
                /r/src/main/java/A.java:12: error: cannot find symbol
                  symbol:   variable tier
                /r/src/main/java/A.java:40: error: ';' expected
                BUILD FAILED
                """;

        String compact = new OutputCompactor(repo).compact(raw, "compileJava");

        assertThat(compact).startsWith("exit 1")
                .contains("A.java:12: error: cannot find symbol")
                .contains("A.java:40: error: ';' expected");
    }

    @Test
    void summarizesJunitXmlFailuresNotConsoleText() throws Exception {
        writeReport("TEST-com.acme.FooTest.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.acme.FooTest" tests="2" skipped="0" failures="1" errors="0">
                  <testcase name="passes" classname="com.acme.FooTest" time="0.01"/>
                  <testcase name="tierIsApplied" classname="com.acme.FooTest" time="0.02">
                    <failure message="expected: 2 but was: 1" type="org.opentest4j.AssertionFailedError">stack...</failure>
                  </testcase>
                </testsuite>
                """);

        String compact = new OutputCompactor(repo).compact("exit 1\nThere were failing tests.\n", "test");

        assertThat(compact).startsWith("exit 1")
                .contains("1 failed").contains("com.acme.FooTest#tierIsApplied")
                .contains("expected: 2 but was: 1")
                .doesNotContain("stack...");
    }

    @Test
    void staleTestResultsAreNotHarvestedForACompileTask() throws Exception {
        writeReport("TEST-com.acme.FooTest.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.acme.FooTest" tests="1" failures="1" errors="0">
                  <testcase name="old" classname="com.acme.FooTest">
                    <failure message="stale failure" type="X">s</failure>
                  </testcase>
                </testsuite>
                """);

        String compact = new OutputCompactor(repo).compact("exit 0\nBUILD SUCCESSFUL\n", "compileJava");

        assertThat(compact).doesNotContain("stale failure").doesNotContain("failed");
    }

    @Test
    void blankFailureMessageFallsBackToElementText() throws Exception {
        writeReport("TEST-com.acme.BarTest.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.acme.BarTest" tests="1" failures="0" errors="1">
                  <testcase name="npes" classname="com.acme.BarTest">
                    <error message="" type="java.lang.NullPointerException">java.lang.NullPointerException
                	at com.acme.Bar.run(Bar.java:5)</error>
                  </testcase>
                </testsuite>
                """);

        String compact = new OutputCompactor(repo).compact("exit 1\n", "test");

        assertThat(compact).contains("com.acme.BarTest#npes")
                .contains("java.lang.NullPointerException");
    }

    @Test
    void greenBuildCompactsToAShortTailWithNoDuplicatedHeader() {
        String compact = new OutputCompactor(repo).compact("exit 0\nBUILD SUCCESSFUL in 3s\n", "check");

        assertThat(compact).startsWith("exit 0").contains("BUILD SUCCESSFUL")
                .doesNotContain("exit 0\nexit 0");
    }

    @Test
    void greenOutputsForDifferentTasksDiffer() {
        OutputCompactor c = new OutputCompactor(repo);
        String compileOut = c.compact("exit 0\n", "compileJava");
        String testOut = c.compact("exit 0\n", "test");
        assertThat(compileOut).isNotEqualTo(testOut);
        assertThat(compileOut).startsWith("exit 0");
        assertThat(testOut).startsWith("exit 0");
    }

    @Test
    void timeoutDoesNotHarvestStaleTestResults() throws Exception {
        writeReport("TEST-com.acme.FooTest.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.acme.FooTest" tests="1" failures="1" errors="0">
                  <testcase name="old" classname="com.acme.FooTest">
                    <failure message="stale failure" type="X">s</failure>
                  </testcase>
                </testsuite>
                """);

        String compact = new OutputCompactor(repo).compact("timed out after 5s", "test");

        assertThat(compact).contains("timed out").doesNotContain("stale failure").doesNotContain("failed");
    }

    @Test
    void capsCompileErrorsWithOmittedMarker() {
        StringBuilder raw = new StringBuilder("exit 1\n");
        for (int i = 0; i < 25; i++) {
            raw.append("/r/F").append(i).append(".java:1: error: boom").append(i).append('\n');
        }
        String compact = new OutputCompactor(repo).compact(raw.toString(), "compileJava");
        assertThat(compact).startsWith("exit 1").contains("5 more compile errors omitted");
    }
}
