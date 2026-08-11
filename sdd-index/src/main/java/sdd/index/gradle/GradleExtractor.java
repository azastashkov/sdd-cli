package sdd.index.gradle;

import org.gradle.tooling.CancellationTokenSource;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GradleExtractor {
    private static final Duration TIMEOUT = Duration.ofMinutes(10);
    private static final Pattern DIST_VERSION = Pattern.compile("gradle-([0-9][0-9.]*)-(?:bin|all)\\.zip");

    private final Map<Integer, Path> jdkHomes;

    public GradleExtractor(Map<Integer, Path> jdkHomes) {
        this.jdkHomes = jdkHomes;
    }

    public GradleModel.Extract extract(Path repoDir) {
        String version = wrapperVersion(repoDir);
        Path out = null;
        Path settingsOut = null;
        Path initScript = null;
        try {
            out = Files.createTempFile("sdd-extract", ".json");
            settingsOut = Files.createTempFile("sdd-settings", ".json");
            initScript = materializeInitScript();
            runBuild(repoDir, version, initScript, out, settingsOut);
            String projectsJson = Files.readString(out);
            String settingsJson = Files.size(settingsOut) == 0 ? null : Files.readString(settingsOut);
            return ExtractJsonParser.parse(projectsJson, settingsJson);
        } catch (IOException e) {
            throw new ExtractionException("io failure extracting " + repoDir + ": " + e.getMessage(), e);
        } finally {
            deleteQuietly(out);
            deleteQuietly(settingsOut);
            deleteQuietly(initScript);
        }
    }

    private void runBuild(Path repoDir, String version, Path initScript, Path out, Path settingsOut) {
        GradleConnector connector = GradleConnector.newConnector()
                .forProjectDirectory(repoDir.toFile())
                .useBuildDistribution();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        CancellationTokenSource cancel = GradleConnector.newCancellationTokenSource();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        String jdkRisk = jdkRiskNote(version, jdkHomes, Runtime.version().feature());
        String riskPrefix = jdkRisk == null ? "" : jdkRisk;
        try (ProjectConnection connection = connector.connect()) {
            List<String> args = new ArrayList<>(List.of(
                    "--init-script", initScript.toString(),
                    "-PsddOut=" + out,
                    "-PsddSettingsOut=" + settingsOut));
            if (versionAtLeast(version, 6, 6)) {
                args.add("--no-configuration-cache");
            }
            var build = connection.newBuild()
                    .forTasks("sddExtract")
                    .withArguments(args)
                    .setStandardError(stderr)
                    .withCancellationToken(cancel.token());
            Path jdk = jdkHomes.get(jdkMajorFor(version));
            if (jdk != null) {
                build.setJavaHome(jdk.toFile());
            }
            Future<?> run = executor.submit((Runnable) build::run);
            run.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            cancel.cancel();
            String tail = stderrTail(stderr);
            throw new ExtractionException(riskPrefix + "gradle extraction timed out after " + TIMEOUT
                    + " in " + repoDir + (tail.isEmpty() ? "" : "\nstderr: " + tail));
        } catch (Exception e) {
            String tail = stderrTail(stderr);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new ExtractionException(
                    riskPrefix + "gradle extraction failed in " + repoDir + ": " + cause.getMessage()
                            + (tail.isEmpty() ? "" : "\nstderr: " + tail), cause);
        } finally {
            executor.shutdownNow();
        }
    }

    public static String wrapperVersion(Path repoDir) {
        Path props = repoDir.resolve("gradle/wrapper/gradle-wrapper.properties");
        if (!Files.isRegularFile(props)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(props)) {
            Properties p = new Properties();
            p.load(in);
            String url = p.getProperty("distributionUrl", "");
            Matcher m = DIST_VERSION.matcher(url);
            return m.find() ? m.group(1) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Describes the risk that an extraction ran on the wrong JVM: the wrapper version maps to a
     * JDK major we have no {@code jdk_homes} entry for, and that major is not the JVM we are
     * running on. Returns {@code null} when there is no such risk.
     */
    static String jdkRiskNote(String wrapperVersion, Map<Integer, Path> jdkHomes, int currentMajor) {
        int mapped = jdkMajorFor(wrapperVersion);
        if (jdkHomes.get(mapped) != null || mapped == currentMajor) {
            return null;
        }
        return "no jdk_homes entry for " + mapped + " (wrapper " + wrapperVersion
                + "); ran on current JVM " + currentMajor + ". ";
    }

    public static int jdkMajorFor(String wrapperVersion) {
        if (wrapperVersion == null) {
            return 21;
        }
        if (versionAtLeast(wrapperVersion, 8, 5)) {
            return 21;
        }
        return versionAtLeast(wrapperVersion, 7, 3) ? 17 : 11;
    }

    private static boolean versionAtLeast(String version, int major, int minor) {
        if (version == null) {
            return true;
        }
        String[] parts = version.split("\\.");
        int maj = Integer.parseInt(parts[0]);
        int min = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        return maj > major || (maj == major && min >= minor);
    }

    private static Path materializeInitScript() throws IOException {
        Path script = Files.createTempFile("sdd-init", ".gradle");
        try (InputStream in = GradleExtractor.class.getResourceAsStream("/sdd/gradle/sdd-init.gradle")) {
            if (in == null) {
                throw new IllegalStateException("missing resource /sdd/gradle/sdd-init.gradle");
            }
            Files.write(script, in.readAllBytes());
        }
        return script;
    }

    private static String stderrTail(ByteArrayOutputStream stderr) {
        String s = stderr.toString(StandardCharsets.UTF_8);
        return s.length() <= 2000 ? s : s.substring(s.length() - 2000);
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // temp files; best effort
            }
        }
    }
}
