package sdd.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import sdd.core.config.ConfigLoader;
import sdd.core.config.ModelEndpoint;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.core.llm.EndpointProbe;

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
        }
        return allOk ? 0 : 1;
    }

    private void report(boolean ok, String check, String detail) {
        if (!ok) {
            allOk = false;
        }
        spec.commandLine().getOut().printf("[%s] %s — %s%n", ok ? " OK " : "FAIL", check, detail);
    }
}
