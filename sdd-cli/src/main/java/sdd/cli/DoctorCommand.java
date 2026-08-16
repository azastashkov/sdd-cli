package sdd.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import sdd.core.config.AtlassianConfig;
import sdd.core.config.AtlassianSite;
import sdd.core.config.BitbucketSite;
import sdd.core.config.ConfigLoader;
import sdd.core.config.ModelEndpoint;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.core.http.AtlassianProbe;
import sdd.core.http.HttpClients;
import sdd.core.llm.EndpointProbe;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "doctor", description = "Check that sdd's environment is ready")
public final class DoctorCommand implements Callable<Integer> {
    @Option(names = "--workspace", description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

    @Spec CommandSpec spec;

    private boolean allOk = true;

    @Override
    public Integer call() {
        int javaMajor = Runtime.version().feature();
        report(javaMajor >= 21, "java", "runtime " + javaMajor);

        SddConfig config = null;
        try {
            config = ConfigLoader.load(workspace);
            report(true, "config", workspace.resolve("sdd.yml").toString());
        } catch (RuntimeException e) {
            report(false, "config", e.getMessage());
        }

        try (Database db = Database.open(workspace)) {
            report(true, "database", ".sdd/index.db schema v" + db.schemaVersion());
        } catch (RuntimeException e) {
            report(false, "database", e.getMessage());
        }

        if (config != null) {
            for (Map.Entry<String, ModelEndpoint> entry : config.models().entrySet()) {
                EndpointProbe.ProbeResult result = EndpointProbe.probe(entry.getValue());
                report(result.ok(), "model:" + entry.getKey(),
                        entry.getValue().baseUrl() + " → " + result.detail());
            }
            if (config.atlassian() != null) {
                probeAtlassian(config.atlassian());
            }
        }
        return allOk ? 0 : 1;
    }

    // A missing atlassian: block must not change this output at all — every check below only
    // runs when the corresponding site is actually configured, matching how model probes only run
    // per declared models: entry above.
    private void probeAtlassian(AtlassianConfig ac) {
        HttpClient client;
        String clientBuildError = null;
        try {
            client = HttpClients.build(ac.tls(), ac.proxy());
        } catch (RuntimeException e) {
            // A bad atlassian.tls.truststore (missing file, unreadable, wrong password) fails
            // HttpClients.build itself, before any site is even probed. Report it against every
            // configured site rather than crashing doctor — one broken truststore should not hide
            // whether the rest of the estate's checks (java, config, database, models) are fine.
            client = null;
            clientBuildError = e.getMessage();
        }
        Path truststore = ac.tls() != null ? ac.tls().truststore() : null;

        if (ac.jira() != null) {
            reportAtlassianProbe("atlassian:jira", "Jira", ac.jira(), "/rest/api/2/myself",
                    client, clientBuildError, truststore, "name", "displayName");
        }
        if (ac.confluence() != null) {
            reportAtlassianProbe("atlassian:confluence", "Confluence", ac.confluence(), "/rest/api/user/current",
                    client, clientBuildError, truststore, "username", "displayName");
        }
        if (ac.bitbucket() != null) {
            BitbucketSite bb = ac.bitbucket();
            // Fix 2 (review): Bitbucket Data Center's REST 1.0 API has no /users/self resource —
            // /users/{userSlug} needs a real slug doctor does not have, so a second probe there
            // would almost always 404 and report a healthy Bitbucket as down, which is expensive
            // on the closed network doctor runs on first. The one probe below
            // (GET /rest/api/1.0/projects/{project}, already needed to confirm the project itself
            // is reachable) also authenticates, so its X-AUSERNAME response header gives the same
            // identity confirmation without a second call.
            if (clientBuildError != null) {
                report(false, "atlassian:bitbucket", clientBuildError);
            } else {
                AtlassianProbe.ProbeResult result = AtlassianProbe.probeHeaderLabel("Bitbucket", bb.site(),
                        "/rest/api/1.0/projects/" + bb.project(), client, truststore, "X-AUSERNAME");
                report(result.ok(), "atlassian:bitbucket", bb.site().baseUrl() + " → " + result.detail());
            }
        }
    }

    private void reportAtlassianProbe(String check, String siteName, AtlassianSite site, String path,
            HttpClient client, String clientBuildError, Path truststore, String... labelFields) {
        if (clientBuildError != null) {
            report(false, check, clientBuildError);
            return;
        }
        AtlassianProbe.ProbeResult result = AtlassianProbe.probe(siteName, site, path, client, truststore, labelFields);
        report(result.ok(), check, site.baseUrl() + " → " + result.detail());
    }

    private void report(boolean ok, String check, String detail) {
        if (!ok) {
            allOk = false;
        }
        spec.commandLine().getOut().printf("[%s] %s — %s%n", ok ? " OK " : "FAIL", check, detail);
    }
}
