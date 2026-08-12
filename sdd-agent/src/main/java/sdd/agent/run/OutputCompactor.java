package sdd.agent.run;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns raw Gradle output into a deterministic ≤4k-token summary (design): javac error lines are
 * scraped from the console — fed the FULL log via GradleTool.runFull so head-of-log root-cause
 * errors survive — while test failures come from the JUnit XML reports on disk, NEVER from console
 * scraping. XML is harvested only for test-running tasks, so a compile-only run (or a timeout)
 * never surfaces stale failures from an earlier test run. A green build compacts to a short tail.
 */
public final class OutputCompactor {
    static final int MAX_CHARS = 6000;
    static final int MAX_ERRORS = 20;
    static final int MAX_FAILURES = 20;
    static final Set<String> TEST_TASKS = Set.of("test", "check", "build");
    private static final Pattern JAVAC = Pattern.compile("^(.*\\.java):(\\d+): error: (.*)$");

    private final Path repoRoot;

    public OutputCompactor(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    public String compact(String rawGradleOutput, String task) {
        List<String> lines = rawGradleOutput.lines().toList();
        String header = lines.isEmpty() ? "" : lines.get(0);   // "exit N" / "timed out ..."
        StringBuilder out = new StringBuilder(header).append(" (").append(task).append(")").append('\n');

        List<String> compileErrors = new ArrayList<>();
        for (String line : lines) {
            Matcher m = JAVAC.matcher(line);
            if (m.matches()) {
                compileErrors.add(shortPath(m.group(1)) + ":" + m.group(2) + ": error: " + m.group(3));
            }
        }
        if (!compileErrors.isEmpty()) {
            out.append("Compile errors:\n");
            appendCapped(out, compileErrors, MAX_ERRORS, "compile errors");
        }

        List<String> failures = TEST_TASKS.contains(task) && rawGradleOutput.startsWith("exit ") ? testFailures() : List.of();
        if (!failures.isEmpty()) {
            out.append(failures.size()).append(" failed:\n");
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

    private static void appendCapped(StringBuilder out, List<String> items, int cap, String noun) {
        int shown = Math.min(items.size(), cap);
        for (int i = 0; i < shown; i++) {
            out.append("  ").append(items.get(i)).append('\n');
        }
        if (items.size() > cap) {
            out.append("  ... ").append(items.size() - cap).append(" more ").append(noun).append(" omitted\n");
        }
    }

    private List<String> testFailures() {
        List<String> failures = new ArrayList<>();
        if (repoRoot == null || !Files.isDirectory(repoRoot)) {
            return failures;
        }
        List<Path> reports = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(repoRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().replace('\\', '/').contains("/build/test-results/"))
                    .filter(p -> p.getFileName().toString().endsWith(".xml"))
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
