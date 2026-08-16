package sdd.agent.run;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import sdd.core.toolchain.Toolchain;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns a raw build log into a deterministic ≤4k-token summary (design): compile errors are scraped
 * from the console — fed the FULL log so head-of-log root-cause errors survive — while test
 * failures come from JUnit XML reports on disk wherever they exist. Reports are harvested only for
 * test-running tasks, so a compile-only run (or a timeout) never surfaces stale failures from an
 * earlier run. A green build compacts to a short tail.
 *
 * <p><b>Where the npm side has to relax the rule, and why it is safe.</b> "Never console-scrape
 * test failures" holds for Gradle because Gradle GUARANTEES a structured artifact under
 * {@code build/test-results}. npm guarantees nothing: vitest writes no report unless asked, and a
 * repo's {@code test} script may be anything at all. Injecting a reporter flag was rejected — npm
 * appends passthrough arguments to the end of the whole script string, so for a chained or
 * workspace-fanning script they land on the wrong command, and it would silently run something the
 * humans never run. So XML is used when a FRESH report exists, and otherwise the console is parsed
 * under a header that says so, making the weaker provenance visible in the output itself rather
 * than a footnote here.
 *
 * <p>The verdict never depends on any of this. {@code VerificationRunner} decides on the
 * {@code "exit 0"} prefix and Gate 2 on the exit code, so a mis-parsed line changes what the model
 * is shown and can never turn a red build green.
 */
public final class OutputCompactor {
    static final int MAX_CHARS = 6000;
    static final int MAX_ERRORS = 20;
    static final int MAX_FAILURES = 20;
    static final Set<String> TEST_TASKS = Set.of("test", "check", "build");
    /** npm has no aggregate task; only `test` runs tests. */
    static final Set<String> NPM_TEST_TASKS = Set.of("test");
    private static final Pattern JAVAC = Pattern.compile("^(.*\\.java):(\\d+): error: (.*)$");

    /** {@code src/a.ts(12,5): error TS2304: ...} — what tsc prints when stdout is not a terminal. */
    private static final Pattern TSC_PLAIN =
            Pattern.compile("^(.+?)\\((\\d+),(\\d+)\\): error (TS\\d+): (.*)$");
    /** {@code src/a.ts:12:5 - error TS2304: ...} — the same, when a repo passes --pretty. */
    private static final Pattern TSC_PRETTY =
            Pattern.compile("^(.+?):(\\d+):(\\d+) - error (TS\\d+): (.*)$");
    /** A failing vitest case: {@code   × math > adds correctly 3ms}. */
    private static final Pattern VITEST_CASE =
            Pattern.compile("^\\s*[\u00d7\u2717]\\s+(.+?)(?:\\s+\\d+m?s)?$");
    /** Its reason on the following line: {@code     → expected 2 to be 3}. */
    private static final Pattern VITEST_REASON = Pattern.compile("^\\s*\u2192\\s*(.*)$");
    /** A whole file that failed to run, which produces no per-case lines at all. */
    private static final Pattern VITEST_FILE = Pattern.compile("^\\s*FAIL\\s+(.+?)$");

    private final Path repoRoot;
    private final Toolchain toolchain;
    /** When the run started, so a stale committed report is never read as this run's result. */
    private final Instant startedAt;

    /** A Gradle compactor; the shape every existing caller and its pinned output already expect. */
    public OutputCompactor(Path repoRoot) {
        this(repoRoot, Toolchain.GRADLE, Instant.EPOCH);
    }

    public OutputCompactor(Path repoRoot, Toolchain toolchain, Instant startedAt) {
        this.repoRoot = repoRoot;
        this.toolchain = toolchain;
        this.startedAt = startedAt;
    }

    public String compact(String rawGradleOutput, String task) {
        List<String> lines = rawGradleOutput.lines().toList();
        String header = lines.isEmpty() ? "" : lines.get(0);   // "exit N" / "timed out ..."
        StringBuilder out = new StringBuilder(header).append(" (").append(task).append(")").append('\n');

        List<String> compileErrors = compileErrors(lines);
        if (!compileErrors.isEmpty()) {
            out.append("Compile errors:\n");
            appendCapped(out, compileErrors, MAX_ERRORS, "compile errors");
        }

        boolean ranTests = testTask(task) && rawGradleOutput.startsWith("exit ");
        List<String> failures = ranTests ? testFailures() : List.of();
        boolean fromConsole = false;
        if (ranTests && failures.isEmpty() && toolchain == Toolchain.NPM) {
            failures = consoleTestFailures(lines);
            fromConsole = !failures.isEmpty();
        }
        if (!failures.isEmpty()) {
            out.append(failures.size()).append(fromConsole
                    ? " failed (from console output — no machine-readable test report was produced):\n"
                    : " failed:\n");
            appendCapped(out, failures, MAX_FAILURES, "test failures");
        }

        if (compileErrors.isEmpty() && failures.isEmpty()) {
            int nl = rawGradleOutput.indexOf('\n');          // append the BODY only — header is never duplicated
            String body = nl >= 0 ? rawGradleOutput.substring(nl + 1) : "";
            String tail = body.length() > 2000 ? body.substring(body.length() - 2000) : body;
            out.append(tail);
        }
        String result = out.toString();
        return result.length() > MAX_CHARS
                ? result.substring(0, MAX_CHARS) + "\n... (compacted output truncated)" : result;
    }

    private List<String> compileErrors(List<String> lines) {
        List<String> errors = new ArrayList<>();
        for (String line : lines) {
            Matcher javac = JAVAC.matcher(line);
            if (javac.matches()) {
                errors.add(shortPath(javac.group(1)) + ":" + javac.group(2)
                        + ": error: " + javac.group(3));
                continue;
            }
            if (toolchain != Toolchain.NPM) {
                continue;
            }
            Matcher tsc = TSC_PLAIN.matcher(line);
            if (!tsc.matches()) {
                tsc = TSC_PRETTY.matcher(line);
            }
            if (tsc.matches()) {
                // Rendered in the Java path's shape so both ecosystems read the same way.
                errors.add(shortPath(tsc.group(1)) + ":" + tsc.group(2)
                        + ": error: " + tsc.group(4) + " " + tsc.group(5));
            }
        }
        return errors;
    }

    private static void appendCapped(StringBuilder out, List<String> items, int cap, String noun) {
        int shown = Math.min(items.size(), cap);
        for (int i = 0; i < shown; i++) {
            out.append("  ").append(items.get(i)).append('\n');
        }
        if (items.size() > cap) {
            out.append("  ... ").append(items.size() - cap).append(" more ").append(noun).append(" omitted\n");
        }
    }

    /** Which task names are expected to have run tests, per toolchain. */
    private boolean testTask(String task) {
        return toolchain == Toolchain.NPM ? NPM_TEST_TASKS.contains(task) : TEST_TASKS.contains(task);
    }

    private List<String> testFailures() {
        List<String> failures = new ArrayList<>();
        if (repoRoot == null || !Files.isDirectory(repoRoot)) {
            return failures;
        }
        List<Path> reports = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(repoRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".xml"))
                    .filter(this::isReportLocation)
                    .filter(this::freshEnough)
                    .sorted()
                    .forEach(reports::add);
        } catch (IOException | UncheckedIOException e) {
            return failures;
        }
        for (Path report : reports) {
            parseReport(report, failures);
        }
        return failures;
    }

    private boolean isReportLocation(Path file) {
        String path = file.toString().replace('\\', '/');
        if (toolchain != Toolchain.NPM) {
            return path.contains("/build/test-results/");
        }
        // npm has no single convention, so the conventional homes are all accepted.
        return path.contains("/test-results/") || path.contains("/reports/")
                || file.getFileName().toString().startsWith("junit")
                || path.contains("/coverage/");
    }

    /**
     * Gradle's report directory is task-owned and cleaned, so anything in it belongs to the run
     * that just happened. An npm repo has no such guarantee — a {@code junit.xml} can simply be
     * checked in — so a report is only believed when it was written after this run started. Without
     * this the compactor can report a stale success or a stale failure as though it were current,
     * which is worse than reporting nothing.
     */
    private boolean freshEnough(Path file) {
        if (toolchain != Toolchain.NPM) {
            return true;
        }
        try {
            return !Files.getLastModifiedTime(file).toInstant().isBefore(startedAt);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Vitest failures read off the console, used only when no fresh report exists. Pairs each
     * failing case with the reason printed beneath it.
     */
    private static List<String> consoleTestFailures(List<String> lines) {
        List<String> failures = new ArrayList<>();
        List<String> fileFailures = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher caseMatch = VITEST_CASE.matcher(lines.get(i));
            if (caseMatch.matches()) {
                String reason = "";
                if (i + 1 < lines.size()) {
                    Matcher reasonMatch = VITEST_REASON.matcher(lines.get(i + 1));
                    if (reasonMatch.matches()) {
                        reason = ": " + reasonMatch.group(1).strip();
                    }
                }
                failures.add(caseMatch.group(1).strip() + reason);
                continue;
            }
            Matcher fileMatch = VITEST_FILE.matcher(lines.get(i));
            if (fileMatch.matches()) {
                fileFailures.add(fileMatch.group(1).strip());
            }
        }
        // A file that failed to load produces no per-case lines at all; reporting it is the only
        // way the model learns its test file does not even run.
        if (failures.isEmpty()) {
            return List.copyOf(fileFailures);
        }
        return failures;
    }

    private static void parseReport(Path report, List<String> failures) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            Document doc = factory.newDocumentBuilder().parse(report.toFile());
            NodeList cases = doc.getElementsByTagName("testcase");
            for (int i = 0; i < cases.getLength(); i++) {
                Element testcase = (Element) cases.item(i);
                Element problem = firstChild(testcase, "failure");
                if (problem == null) {
                    problem = firstChild(testcase, "error");
                }
                if (problem != null) {
                    String message = problem.getAttribute("message");
                    String type = problem.getAttribute("type");
                    String detail = message.isBlank()   // blank message → first non-empty line of the element text
                            ? problem.getTextContent().lines().map(String::strip)
                                    .filter(s -> !s.isEmpty()).findFirst().orElse("")
                            : message.lines().findFirst().orElse("");
                    failures.add(testcase.getAttribute("classname") + "#" + testcase.getAttribute("name")
                            + ": " + type + ": " + detail);
                }
            }
        } catch (Exception e) {
            // unreadable/oddly-formatted report — skip, deterministic best effort
        }
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList children = parent.getElementsByTagName(tag);
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                return (Element) children.item(i);
            }
        }
        return null;
    }

    private static String shortPath(String path) {
        int slash = path.replace('\\', '/').lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
