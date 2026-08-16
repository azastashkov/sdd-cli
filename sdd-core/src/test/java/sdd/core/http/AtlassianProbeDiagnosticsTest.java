package sdd.core.http;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.config.AtlassianSite;
import sdd.core.diagnostics.DiagnosticWriter;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code AtlassianProbe} builds a {@code RestClient} internally (see its {@code run} method), so
 * threading an optional {@link DiagnosticWriter} through it is what lets {@code sdd doctor}'s
 * probes contribute to the same diagnostic file as everything else in a command invocation.
 * Additive — {@link AtlassianProbeTest} is untouched.
 */
class AtlassianProbeDiagnosticsTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    @TempDir
    Path tmp;

    @Test
    void probeLogsTheRequestThroughTheGivenDiagnosticsWriter() throws IOException {
        wm.stubFor(get("/rest/api/2/myself").willReturn(okJson("{\"name\":\"jsmith\"}")));
        AtlassianSite site = new AtlassianSite(wm.baseUrl(), "sk-x", "JIRA_PAT", Duration.ofSeconds(5), null);
        Path file = tmp.resolve("d.log");
        DiagnosticWriter w = new DiagnosticWriter(file, Set.of("sk-x"),
                InstantSource.fixed(Instant.parse("2026-08-17T10:00:00Z")), null);

        AtlassianProbe.probe("Jira", site, "/rest/api/2/myself", HttpClient.newHttpClient(), null, w, "name");
        w.close();

        String content = Files.readString(file);
        assertThat(content).contains("Jira").contains("/rest/api/2/myself").contains("status=200");
        assertThat(content).doesNotContain("sk-x");
    }

    @Test
    void probeHeaderLabelLogsThroughTheGivenDiagnosticsWriter() throws IOException {
        wm.stubFor(get("/rest/api/1.0/projects/TRADING").willReturn(okJson("{\"key\":\"TRADING\"}")
                .withHeader("X-AUSERNAME", "jsmith")));
        AtlassianSite site = new AtlassianSite(wm.baseUrl(), "sk-x", "BITBUCKET_PAT", Duration.ofSeconds(5), null);
        Path file = tmp.resolve("d.log");
        DiagnosticWriter w = new DiagnosticWriter(file, Set.of("sk-x"),
                InstantSource.fixed(Instant.parse("2026-08-17T10:00:00Z")), null);

        AtlassianProbe.probeHeaderLabel("Bitbucket", site, "/rest/api/1.0/projects/TRADING",
                HttpClient.newHttpClient(), null, w, "X-AUSERNAME");
        w.close();

        assertThat(Files.readString(file)).contains("Bitbucket").contains("/rest/api/1.0/projects/TRADING");
    }

    @Test
    void aNullDiagnosticsWriterIsANoOpAndBehaviorIsUnchanged() {
        wm.stubFor(get("/rest/api/2/myself").willReturn(okJson("{\"name\":\"jsmith\"}")));
        AtlassianSite site = new AtlassianSite(wm.baseUrl(), "sk-x", "JIRA_PAT", Duration.ofSeconds(5), null);

        AtlassianProbe.ProbeResult r = AtlassianProbe.probe("Jira", site, "/rest/api/2/myself",
                HttpClient.newHttpClient(), null, (DiagnosticWriter) null, "name");

        assertThat(r.ok()).isTrue();
    }
}
