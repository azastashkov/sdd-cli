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
            // "self" is Bitbucket Data Center's magic user slug for "the authenticated user" — the
            // {self} path-parameter template from the brief resolves to this literal value, not a
            // brace-delimited placeholder (curly braces are not valid, unencoded URI characters).
            reportAtlassianProbe("atlassian:bitbucket:user", "Bitbucket", bb.site(), "/rest/api/1.0/users/self",
                    client, clientBuildError, truststore, "name", "displayName");
            reportAtlassianProbe("atlassian:bitbucket:project", "Bitbucket", bb.site(),
                    "/rest/api/1.0/projects/" + bb.project(), client, clientBuildError, truststore, "key", "name");
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
