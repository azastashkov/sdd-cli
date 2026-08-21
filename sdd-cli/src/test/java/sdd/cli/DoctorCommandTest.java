package sdd.cli;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class DoctorCommandTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    @TempDir Path ws;

    private String yaml() {
        return """
                models:
                  planner:
                    base_url: %s/v1
                    model: deepseek-v4-flash
                  coder:
                    base_url: %s/v1
                    model: qwen
                """.formatted(wm.baseUrl(), wm.baseUrl());
    }

    private record Run(int exitCode, String out) {}

    private Run doctor(Path workspace, String... extraArgs) {
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(new SddCli());
        cmd.setOut(new PrintWriter(sw, true));
        cmd.setErr(new PrintWriter(sw, true));
        String[] args = new String[3 + extraArgs.length];
        args[0] = "doctor";
        args[1] = "--workspace";
        args[2] = workspace.toString();
        System.arraycopy(extraArgs, 0, args, 3, extraArgs.length);
        int code = cmd.execute(args);
        return new Run(code, sw.toString());
    }

    @Test
    void allChecksPassExitsZero() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));

        Run run = doctor(ws);

        assertThat(run.out()).contains("[ OK ] java")
                .contains("[ OK ] config")
                .contains("[ OK ] database")
                .contains("[ OK ] model:planner")
                .contains("[ OK ] model:coder");
        assertThat(run.exitCode()).isZero();
    }

    // A GigaChat-style gateway serves /chat/completions and nothing else. Gated on ok(), --tools
    // printed one red line for /models and never ran the tool-call probe — silently withholding
    // the one check that says whether an agent run can work against that endpoint at all.
    @Test
    void toolProbeStillRunsOnAGatewayThatServesNoModelsRoute() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        wm.stubFor(get("/v1/models").willReturn(notFound()));
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson("""
                {"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"c1",
                  "type":"function","function":{"name":"report_status",
                  "arguments":"{\\"status\\":\\"ok\\"}"}}]},"finish_reason":"tool_calls"}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1}}
                """)));

        Run run = doctor(ws, "--endpoint", "planner", "--tools");

        assertThat(run.out()).contains("[ OK ] model:planner:tools");
        // The /models line still reports honestly, and names both readings of a 404 — a gateway
        // without a listing route, or a base_url with /chat/completions left on it.
        assertThat(run.out()).contains("model:planner").contains("base_url is wrong");
    }

    @Test
    void unreachableModelEndpointFails() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        wm.stubFor(get("/v1/models").willReturn(serverError()));

        Run run = doctor(ws);

        assertThat(run.out()).contains("[FAIL] model:planner");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void missingConfigFailsButStillReportsJava() {
        Run run = doctor(ws);

        assertThat(run.out()).contains("[ OK ] java").contains("[FAIL] config");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    // --- atlassian: block probes -----------------------------------------------------------

    @Test
    void absentAtlassianBlockChangesDoctorsOutputNotAtAll() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));

        Run run = doctor(ws);

        assertThat(run.out()).doesNotContain("atlassian");
    }

    @Test
    void reachableJiraSiteReportsOkWithTheUsername() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml() + """
                atlassian:
                  jira:
                    base_url: %s
                    token: sk-jira-test
                """.formatted(wm.baseUrl()));
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));
        wm.stubFor(get("/rest/api/2/myself").willReturn(okJson("{\"name\":\"jsmith\"}")));

        Run run = doctor(ws);

        assertThat(run.out()).contains("[ OK ] atlassian:jira").contains("HTTP 200 as jsmith");
        // Independently optional: jira configured alone must not produce confluence/bitbucket lines.
        assertThat(run.out()).doesNotContain("atlassian:confluence").doesNotContain("atlassian:bitbucket");
        assertThat(run.exitCode()).isZero();
    }

    @Test
    void unreachableJiraSiteFailsAndFailsTheOverallExitCode() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml() + """
                atlassian:
                  jira:
                    base_url: %s
                    token: sk-jira-test
                """.formatted(wm.baseUrl()));
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));
        wm.stubFor(get("/rest/api/2/myself").willReturn(unauthorized()));

        Run run = doctor(ws);

        assertThat(run.out()).contains("[FAIL] atlassian:jira")
                .contains("Jira rejected the configured token (HTTP 401) — reissue it");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void reachableConfluenceSiteReportsOk() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml() + """
                atlassian:
                  confluence:
                    base_url: %s
                    token: sk-confluence-test
                """.formatted(wm.baseUrl()));
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));
        wm.stubFor(get("/rest/api/user/current").willReturn(okJson("{\"username\":\"jsmith\"}")));

        Run run = doctor(ws);

        assertThat(run.out()).contains("[ OK ] atlassian:confluence").contains("HTTP 200 as jsmith");
    }

    // Fix 2 (review): Bitbucket DC has no /users/self resource, so doctor probes only
    // /rest/api/1.0/projects/{project} and reads the username from that response's
    // X-AUSERNAME header — a single call, not two.
    @Test
    void bitbucketProbesTheProjectEndpointAndReadsTheUsernameFromTheAusernameHeader() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml() + """
                atlassian:
                  bitbucket:
                    base_url: %s
                    token: sk-bb-test
                    project: TRADING
                """.formatted(wm.baseUrl()));
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));
        wm.stubFor(get("/rest/api/1.0/projects/TRADING").willReturn(okJson("{\"key\":\"TRADING\"}")
                .withHeader("X-AUSERNAME", "jsmith")));

        Run run = doctor(ws);

        assertThat(run.out()).contains("[ OK ] atlassian:bitbucket").contains("HTTP 200 as jsmith");
        wm.verify(0, getRequestedFor(urlEqualTo("/rest/api/1.0/users/self")));
    }

    // --- Task 8 Part B: diagnostics -----------------------------------------------------------

    @Test
    void diagnosticsAreWrittenByDefaultEvenWithoutTheReportFlag() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));

        Run run = doctor(ws);

        assertThat(run.exitCode()).isZero();
        Path dir = ws.resolve(".sdd/diagnostics");
        assertThat(Files.isDirectory(dir)).isTrue();
        try (var files = Files.list(dir)) {
            assertThat(files.count()).isEqualTo(1);
        }
        // No stdout announcement without --report — "the flag adds a file; it does not alter
        // behaviour" (brief B5).
        assertThat(run.out()).doesNotContain("diagnostics report written");
    }

    @Test
    void reportFlagWritesASelfContainedFileAndAnnouncesItOnStdoutWithoutChangingExitCode() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));

        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(new SddCli());
        cmd.setOut(new PrintWriter(sw, true));
        cmd.setErr(new PrintWriter(sw, true));
        int code = cmd.execute("doctor", "--workspace", ws.toString(), "--report");

        assertThat(code).isZero();
        assertThat(sw.toString()).contains("diagnostics report written:").contains("safe to share");
        // The stdout report path must actually exist and contain the header + probe outcomes.
        String printed = sw.toString();
        String pathLine = printed.lines().filter(l -> l.contains("diagnostics report written:")).findFirst().orElseThrow();
        Path reportFile = Path.of(pathLine.substring(pathLine.indexOf(':') + 1).trim());
        assertThat(reportFile).exists();
        String content = Files.readString(reportFile);
        assertThat(content).contains("=== sdd diagnostics ===").contains("probe java:").contains("probe config:");
    }

    @Test
    void reportFlagWithAnExplicitPathWritesThere() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));
        Path target = ws.resolve("share-me.log");

        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(new SddCli());
        cmd.setOut(new PrintWriter(sw, true));
        cmd.setErr(new PrintWriter(sw, true));
        int code = cmd.execute("doctor", "--workspace", ws.toString(), "--report", target.toString());

        assertThat(code).isZero();
        assertThat(target).exists();
        assertThat(sw.toString()).contains("diagnostics report written: " + target);
    }

    @Test
    void aJiraTokenNeverReachesTheDiagnosticsFileEvenWhenTheProbeIsUsedAndFails() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml() + """
                atlassian:
                  jira:
                    base_url: %s
                    token: sk-super-secret-jira-token
                """.formatted(wm.baseUrl()));
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));
        wm.stubFor(get("/rest/api/2/myself").willReturn(unauthorized()));

        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(new SddCli());
        cmd.setOut(new PrintWriter(sw, true));
        cmd.setErr(new PrintWriter(sw, true));
        cmd.execute("doctor", "--workspace", ws.toString(), "--report");

        Path dir = ws.resolve(".sdd/diagnostics");
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) {
                assertThat(Files.readString(f)).doesNotContain("sk-super-secret-jira-token");
            }
        }
    }

    // --- Gate review minors -----------------------------------------------------------------

    @Test
    void anInvalidReportPathFailsCleanlyRatherThanThrowingOutOfCall() throws Exception {
        // Path.of(report) used to sit outside every guard, so a malformed --report argument
        // propagated an uncaught InvalidPathException out of call() instead of the clean [FAIL]
        // line every other doctor check produces. A NUL byte is invalid in a path on every
        // platform this runs on.
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));

        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(new SddCli());
        cmd.setOut(new PrintWriter(sw, true));
        cmd.setErr(new PrintWriter(sw, true));
        int code = cmd.execute("doctor", "--workspace", ws.toString(), "--report", "bad path");

        assertThat(code).isEqualTo(1);
        assertThat(sw.toString()).contains("[FAIL] report-path");
    }

    @Test
    void aTlsBlockWithNoSiteConfiguredStillReportsATruststoreLoadFailure() throws Exception {
        // atlassian.tls set, but no jira/confluence/bitbucket site — clientBuildError was computed
        // but every report() call for it lived inside an `if (ac.<site>() != null)` branch, so
        // doctor used to exit 0 having printed nothing about a truststore that does not even load.
        Files.writeString(ws.resolve("sdd.yml"), yaml() + """
                atlassian:
                  tls:
                    truststore: %s
                """.formatted(ws.resolve("does-not-exist.jks")));
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));

        Run run = doctor(ws);

        assertThat(run.out()).contains("[FAIL] atlassian:tls");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void aDiagnosticsWriteFailureNeverChangesDoctorsExitCodeOrStdout() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));
        // .sdd/diagnostics cannot be created: a plain FILE sits where the directory must go.
        Files.createDirectories(ws.resolve(".sdd"));
        Files.writeString(ws.resolve(".sdd/diagnostics"), "blocking");

        Run run = doctor(ws);

        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("[ OK ] java").contains("[ OK ] config").contains("[ OK ] database");
    }

    // --- Phase 3: mTLS pre-flight, --endpoint, diagnostics -----------------------------------

    // A self-contained cert/key generator, deliberately NOT sdd-core's CertFixtures: CertFixtures
    // lives in sdd-core's ordinary src/test/java (not src/testFixtures/java), so it is invisible
    // from sdd-cli's test source set without moving it — a change to already-passing test wiring
    // this task's "every pre-existing test must pass unmodified" makes not worth risking for what
    // doctor's checks actually need: one self-signed cert/key pair (no CA chain, no live TLS
    // handshake — the WireMock stub these tests probe stays plain HTTP, exactly like every other
    // DoctorCommandTest; an HttpClient built with a client-cert SSLContext still sends a plain HTTP
    // request untouched). Same openssl-via-ProcessBuilder approach CertFixtures itself uses, so no
    // checked-in certificate is ever committed here either.
    private record GeneratedCert(Path cert, Path key) {}

    private GeneratedCert selfSignedClientCert(Path dir, String prefix, int validityDays) throws Exception {
        Path key = dir.resolve(prefix + ".key");
        Path cert = dir.resolve(prefix + ".crt");
        runOpenssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", key.toString(),
                "-out", cert.toString(), "-days", String.valueOf(validityDays),
                "-subj", "/CN=sdd-doctor-test-client");
        return new GeneratedCert(cert, key);
    }

    private Path encryptedKeyFrom(Path unencryptedKey, Path dir, String filename, String password) throws Exception {
        Path enc = dir.resolve(filename);
        runOpenssl("pkcs8", "-topk8", "-in", unencryptedKey.toString(), "-out", enc.toString(),
                "-passout", "pass:" + password);
        return enc;
    }

    private Path pkcs1KeyFrom(Path pkcs8Key, Path dir, String filename) throws Exception {
        Path out = dir.resolve(filename);
        runOpenssl("rsa", "-in", pkcs8Key.toString(), "-traditional", "-out", out.toString());
        return out;
    }

    private void runOpenssl(String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("openssl"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("openssl fixture command failed: " + command + "\n" + output);
        }
    }

    private Instant notAfterOf(Path certPath) throws Exception {
        try (var in = Files.newInputStream(certPath)) {
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            return cert.getNotAfter().toInstant();
        }
    }

    // models.planner/models.coder are unconditionally required by ConfigLoader.load
    // (ConfigLoader.java:53-55) — every yaml fixture in this file, including the pre-existing
    // yaml() helper above, defines both for that reason. This variant adds a third, tls-configured
    // endpoint (corp) alongside them rather than replacing them.
    private String corpYaml(Path certPath, Path keyPath) {
        return """
                models:
                  planner:
                    base_url: %s/v1
                    model: deepseek-v4-flash
                  coder:
                    base_url: %s/v1
                    model: qwen
                  corp:
                    base_url: %s/v1
                    model: DeepSeek-V4-Flash
                    tls:
                      cert: %s
                      key: %s
                """.formatted(wm.baseUrl(), wm.baseUrl(), wm.baseUrl(), certPath, keyPath);
    }

    // Mirrors doctor(Path) but wraps DoctorCommand through SddCli's already-built subcommand tree
    // (new CommandLine(new SddCli()) constructs every subcommand instance eagerly, including
    // DoctorCommand — the standard picocli lifecycle) so an injected clockForTest and extra flags
    // (--endpoint) can be exercised without touching the untouched doctor(Path) helper every
    // pre-existing test above already depends on.
    private Run doctor(Path workspace, InstantSource clock, String... extraArgs) {
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(new SddCli());
        ((DoctorCommand) cmd.getSubcommands().get("doctor").getCommand()).clockForTest = clock;
        cmd.setOut(new PrintWriter(sw, true));
        cmd.setErr(new PrintWriter(sw, true));
        List<String> args = new java.util.ArrayList<>(List.of("doctor", "--workspace", workspace.toString()));
        args.addAll(List.of(extraArgs));
        int code = cmd.execute(args.toArray(new String[0]));
        return new Run(code, sw.toString());
    }

    @Test
    void mtlsEndpointWithAValidCertificatePassesThePreflightAndReportsTheSubject() throws Exception {
        GeneratedCert cert = selfSignedClientCert(ws, "client", 3650);
        Files.writeString(ws.resolve("sdd.yml"), corpYaml(cert.cert(), cert.key()));
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));

        Run run = doctor(ws, InstantSource.system());

        assertThat(run.out()).contains("[ OK ] model:corp:tls").contains("sdd-doctor-test-client")
                .contains("[ OK ] model:corp");
        assertThat(run.exitCode()).isZero();
    }

    @Test
    void missingClientCertFileFailsThePreflightCheckAndNamesThePath() throws Exception {
        GeneratedCert cert = selfSignedClientCert(ws, "client", 3650);
        Path missingCert = ws.resolve("does-not-exist.crt");
        Files.writeString(ws.resolve("sdd.yml"), corpYaml(missingCert, cert.key()));
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));

        Run run = doctor(ws, InstantSource.system());

        assertThat(run.out()).contains("[FAIL] model:corp:tls").contains(missingCert.toString())
                .contains("does not exist");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void pkcs1KeyProducesTheOpensslConversionHintInThePreflight() throws Exception {
        GeneratedCert cert = selfSignedClientCert(ws, "client", 3650);
        Path pkcs1Key = pkcs1KeyFrom(cert.key(), ws, "client_pkcs1.key");
        Files.writeString(ws.resolve("sdd.yml"), corpYaml(cert.cert(), pkcs1Key));
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));

        Run run = doctor(ws, InstantSource.system());

        assertThat(run.out()).contains("[FAIL] model:corp:tls").contains("RSA PRIVATE KEY")
                .contains("openssl pkcs8 -topk8 -nocrypt -in " + pkcs1Key + " -out " + pkcs1Key + ".pk8.pem");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    // The expiry check must be deterministic — never dependent on the real wall clock, since the
    // generated cert here is genuinely valid at generation time. Advancing the INJECTED clock past
    // its notAfter is what makes it "expired" for this test, without a checked-in cert that will
    // itself eventually expire and break the build years from now.
    @Test
    void anExpiredClientCertificateIsCaughtDeterministicallyViaTheInjectedClock() throws Exception {
        GeneratedCert cert = selfSignedClientCert(ws, "client", 1);
        Instant notAfter = notAfterOf(cert.cert());
        Files.writeString(ws.resolve("sdd.yml"), corpYaml(cert.cert(), cert.key()));
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));

        Run run = doctor(ws, InstantSource.fixed(notAfter.plusSeconds(3600)));

        assertThat(run.out()).contains("[FAIL] model:corp:tls").contains("expired");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void aCertExpiringWithinThirtyDaysWarnsButStillPassesTheCheck() throws Exception {
        GeneratedCert cert = selfSignedClientCert(ws, "client", 40);
        Instant notAfter = notAfterOf(cert.cert());
        Files.writeString(ws.resolve("sdd.yml"), corpYaml(cert.cert(), cert.key()));
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));

        Run run = doctor(ws, InstantSource.fixed(notAfter.minus(java.time.Duration.ofDays(15))));

        assertThat(run.out()).contains("[ OK ] model:corp:tls")
                .contains("  warn: client certificate for model:corp:tls expires in");
        assertThat(run.exitCode()).isZero();
    }

    // Phase 1's HttpClients.keyFilePermissionWarning was deliberately left unwired — sdd-core has
    // no writer. This is that wiring, finally exercised end to end through sdd doctor.
    @Test
    void worldReadableKeyFileEmitsThePermissionWarning() throws Exception {
        GeneratedCert cert = selfSignedClientCert(ws, "client", 3650);
        Files.setPosixFilePermissions(cert.key(), java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OTHERS_READ));
        Files.writeString(ws.resolve("sdd.yml"), corpYaml(cert.cert(), cert.key()));
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));

        Run run = doctor(ws, InstantSource.system());

        assertThat(run.out()).contains("  warn: client key " + cert.key() + " is group- or world-readable");
    }

    @Test
    void endpointFlagProbesOnlyTheNamedTier() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));

        Run run = doctor(ws, InstantSource.system(), "--endpoint", "planner");

        assertThat(run.out()).contains("[ OK ] model:planner").doesNotContain("model:coder");
        assertThat(run.exitCode()).isZero();
    }

    @Test
    void endpointFlagWithAnUnknownNameFailsCleanlyWithoutChangingOtherChecks() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));

        Run run = doctor(ws, InstantSource.system(), "--endpoint", "does-not-exist");

        assertThat(run.out()).contains("[FAIL] endpoint").contains("does-not-exist")
                .contains("[ OK ] java").contains("[ OK ] config").contains("[ OK ] database");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    // Carried forward from Phase 2's review: TlsConfig.keyPassword must never reach any diagnostic
    // line. This is the end-to-end proof, not a code-reading claim — a real encrypted key, a real
    // password, doctor run with --report (the path most likely to interpolate everything it knows),
    // and neither stdout nor the written diagnostics file may contain it.
    @Test
    void aConfiguredKeyPasswordNeverReachesStdoutOrTheDiagnosticsFile() throws Exception {
        GeneratedCert cert = selfSignedClientCert(ws, "client", 3650);
        String password = "s3cr3t-test-key-pass-42";
        Path encKey = encryptedKeyFrom(cert.key(), ws, "client_enc.key", password);
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner:
                    base_url: %s/v1
                    model: deepseek-v4-flash
                  coder:
                    base_url: %s/v1
                    model: qwen
                  corp:
                    base_url: %s/v1
                    model: DeepSeek-V4-Flash
                    tls:
                      cert: %s
                      key: %s
                      key_password: %s
                """.formatted(wm.baseUrl(), wm.baseUrl(), wm.baseUrl(), cert.cert(), encKey, password));
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));

        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(new SddCli());
        cmd.setOut(new PrintWriter(sw, true));
        cmd.setErr(new PrintWriter(sw, true));
        int code = cmd.execute("doctor", "--workspace", ws.toString(), "--report");

        assertThat(sw.toString()).doesNotContain(password);
        assertThat(code).isZero();
        Path dir = ws.resolve(".sdd/diagnostics");
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) {
                assertThat(Files.readString(f)).doesNotContain(password);
            }
        }
    }

    @Test
    void diagnosticsFileRecordsCertSubjectExpiryAndTruststoreForATlsConfiguredEndpoint() throws Exception {
        GeneratedCert cert = selfSignedClientCert(ws, "client", 3650);
        Files.writeString(ws.resolve("sdd.yml"), corpYaml(cert.cert(), cert.key()));
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));

        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(new SddCli());
        cmd.setOut(new PrintWriter(sw, true));
        cmd.setErr(new PrintWriter(sw, true));
        int code = cmd.execute("doctor", "--workspace", ws.toString(), "--report");
        assertThat(code).isZero();

        String printed = sw.toString();
        String pathLine = printed.lines().filter(l -> l.contains("diagnostics report written:"))
                .findFirst().orElseThrow();
        Path reportFile = Path.of(pathLine.substring(pathLine.indexOf(':') + 1).trim());
        String content = Files.readString(reportFile);
        assertThat(content).contains("model-tls name=corp").contains("cert=" + cert.cert())
                .contains("sdd-doctor-test-client").contains("truststore=(JDK default truststore)");
    }

    // ------------------------------------------------------------------ --model-name

    /**
     * Answering "which model on this gateway can drive an agent" used to mean a throwaway tier per
     * candidate — editing config on the machine where editing config is the expensive part.
     */
    @Test
    void modelNameProbesADifferentModelWithoutTouchingTheConfig() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[{\"id\":\"GigaChat-2-Max\"}]}")));

        Run run = doctor(ws, "--endpoint", "planner", "--model-name", "GigaChat-2-Max");

        assertThat(run.out()).contains("probing model 'GigaChat-2-Max' over planner's transport")
                .contains("sdd.yml says 'deepseek-v4-flash', and is unchanged");
        // The file really is untouched.
        assertThat(Files.readString(ws.resolve("sdd.yml"))).contains("deepseek-v4-flash");
    }

    /** The override is validated against the listing like any other configured name. */
    @Test
    void anOverriddenNameIsStillCheckedAgainstWhatTheGatewayServes() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[{\"id\":\"GigaChat-2-Max\"}]}")));

        Run run = doctor(ws, "--endpoint", "planner", "--model-name", "no-such-model");

        assertThat(run.out()).contains("'no-such-model' is NOT served here");
    }

    /** Without --endpoint it would probe every tier as the same model and report one answer thrice. */
    @Test
    void modelNameWithoutAnEndpointIsRefusedByName() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());

        Run run = doctor(ws, "--model-name", "GigaChat-2-Max");

        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(run.out()).contains("--model-name needs --endpoint");
    }

    /** Absent, nothing changes: the configured model is probed and no extra line is printed. */
    @Test
    void withoutTheOverrideTheConfiguredModelIsProbedSilently() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));

        Run run = doctor(ws, "--endpoint", "planner");

        assertThat(run.out()).doesNotContain("probing model");
    }
}
