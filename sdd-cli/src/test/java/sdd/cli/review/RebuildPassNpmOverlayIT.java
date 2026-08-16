package sdd.cli.review;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunGit;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;
import sdd.core.config.ConfigLoader;
import sdd.core.config.SddConfig;
import sdd.core.testing.FixtureRepo;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 2 has to verify the estate as a WHOLE. For an npm consumer that means it must be rebuilt
 * against the provider's checkpointed change, not the version the registry last published —
 * otherwise the report says the estate builds green while half the change was never in the build.
 */
@Tag("node-it")
class RebuildPassNpmOverlayIT {
    @TempDir Path ws;

    private static void requireNpm() {
        Assumptions.assumeTrue(sdd.core.ts.NodeLocator.find(null).isPresent(),
                "node/npm not available on this machine");
    }

    private static void writeSddYml(Path ws) throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen }
                """);
    }

    /**
     * A consumer whose `test` script asserts on the provider's code. It passes only when the
     * overlay actually replaced what is installed.
     */
    private static void npmScripts(FixtureRepo repo, String name, String testScript) throws Exception {
        Files.writeString(repo.path().resolve("package.json"), """
                {"name":"%s","version":"1.0.0","scripts":{"test":"%s"}}
                """.formatted(name, testScript));
    }

    @Test
    void aConsumerIsRebuiltAgainstTheProvidersCheckpointNotItsPublishedVersion() throws Exception {
        requireNpm();

        // The provider publishes @acme/lib. Its checkpoint changes what the package exports.
        FixtureRepo provider = FixtureRepo.in(ws, "provider");
        Files.writeString(provider.path().resolve("package.json"), """
                {"name":"@acme/lib","version":"1.1.0","main":"./index.js","files":["index.js"]}
                """);
        Files.writeString(provider.path().resolve("index.js"), "module.exports = 'PUBLISHED';\n");
        provider.commit("base");
        String providerBase = provider.headSha();
        String providerBranch = RunGit.currentBranch(provider.path());

        String providerRun = "sdd/SPEC-1-v1/provider";
        RunGit.startBranch(provider.path(), providerRun, providerBase);
        Files.writeString(provider.path().resolve("index.js"), "module.exports = 'CHECKPOINT';\n");
        provider.commit("checkpoint");
        String providerCheckpoint = provider.headSha();
        RunGit.checkout(provider.path(), providerBranch);

        // The consumer has the OLD package installed, exactly as a registry install would leave it.
        FixtureRepo consumer = FixtureRepo.in(ws, "consumer");
        npmScripts(consumer, "consumer",
                "node -e \\\"const v=require('@acme/lib'); if(v!=='CHECKPOINT') { console.error('got '+v); process.exit(1);} \\\"");
        Path installed = Files.createDirectories(
                consumer.path().resolve("node_modules/@acme/lib"));
        Files.writeString(installed.resolve("package.json"),
                "{\"name\":\"@acme/lib\",\"version\":\"1.0.0\",\"main\":\"./index.js\"}");
        Files.writeString(installed.resolve("index.js"), "module.exports = 'PUBLISHED';\n");
        consumer.commit("base");
        String consumerBase = consumer.headSha();
        String consumerBranch = RunGit.currentBranch(consumer.path());
        String consumerRun = "sdd/SPEC-1-v1/consumer";
        RunGit.startBranch(consumer.path(), consumerRun, consumerBase);
        consumer.commit("checkpoint");
        String consumerCheckpoint = consumer.headSha();
        RunGit.checkout(consumer.path(), consumerBranch);

        writeSddYml(ws);
        SddConfig config = ConfigLoader.load(ws);

        PlanModel plan = new PlanModel("SPEC-1", 1, "", "",
                List.of(new PlanModel.PlanRepo("provider", "seed", "SEED", "none", providerBase),
                        new PlanModel.PlanRepo("consumer", "dependent", "X", "none", consumerBase)),
                List.of(List.of("provider"), List.of("consumer")),
                List.of(new PlanModel.PlanEdge("consumer", "provider", "PINNED", "NPM_OVERLAY")),
                List.of(), List.of());

        RunState state = new RunState("SPEC-1-v1", List.of(
                new RepoRun("provider", RepoState.SUCCEEDED, providerRun, providerCheckpoint, "ok", null),
                new RepoRun("consumer", RepoState.SUCCEEDED, consumerRun, consumerCheckpoint, "ok", null)),
                null, 0L);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-1-v1", "{}", "");
        Map<String, Path> paths = Map.of("provider", provider.path(), "consumer", consumer.path());
        StringWriter err = new StringWriter();

        RebuildPass.Outcome outcome = RebuildPass.run(Set.of("consumer"), plan, state, paths,
                config, runDir, store, false, new PrintWriter(err));

        // The consumer's own test asserts it imported 'CHECKPOINT'. Before overlays reached Gate 2
        // it would have imported 'PUBLISHED' and this rebuild would have failed — or worse, passed
        // while proving nothing about the change under review.
        EstateRebuild.Result result = outcome.rebuilds().get("consumer");
        assertThat(result).isNotNull();
        assertThat(result.ok()).as("%s", result.log()).isTrue();
        assertThat(outcome.stagingFailures()).isEmpty();
        assertThat(outcome.restoreFailures()).isEmpty();

        // And the estate is left exactly as review found it: the consumer's installed copy is the
        // published one again, not the overlaid checkpoint.
        assertThat(Files.readString(installed.resolve("index.js")))
                .isEqualTo("module.exports = 'PUBLISHED';\n");
        assertThat(RunGit.currentBranch(provider.path())).isEqualTo(providerBranch);
        assertThat(RunGit.currentBranch(consumer.path())).isEqualTo(consumerBranch);
    }

    @Test
    void aProviderThatCannotBePackedInvalidatesTheVerdictRatherThanPassingQuietly() throws Exception {
        requireNpm();

        // A provider whose package.json is unreadable cannot be packed, so the consumer would be
        // rebuilt against its published version while the report claimed the estate was verified.
        FixtureRepo provider = FixtureRepo.in(ws, "provider");
        Files.writeString(provider.path().resolve("package.json"), "{ this is not json");
        provider.commit("base");
        String providerBase = provider.headSha();
        String providerRun = "sdd/SPEC-2-v1/provider";
        String providerBranch = RunGit.currentBranch(provider.path());
        RunGit.startBranch(provider.path(), providerRun, providerBase);
        provider.commit("checkpoint");
        String providerCheckpoint = provider.headSha();
        RunGit.checkout(provider.path(), providerBranch);

        FixtureRepo consumer = FixtureRepo.in(ws, "consumer");
        npmScripts(consumer, "consumer", "node -e \\\"process.exit(0)\\\"");
        Files.createDirectories(consumer.path().resolve("node_modules"));
        consumer.commit("base");
        String consumerBase = consumer.headSha();
        String consumerBranch = RunGit.currentBranch(consumer.path());
        String consumerRun = "sdd/SPEC-2-v1/consumer";
        RunGit.startBranch(consumer.path(), consumerRun, consumerBase);
        consumer.commit("checkpoint");
        String consumerCheckpoint = consumer.headSha();
        RunGit.checkout(consumer.path(), consumerBranch);

        writeSddYml(ws);
        SddConfig config = ConfigLoader.load(ws);
        PlanModel plan = new PlanModel("SPEC-2", 1, "", "",
                List.of(new PlanModel.PlanRepo("provider", "seed", "SEED", "none", providerBase),
                        new PlanModel.PlanRepo("consumer", "dependent", "X", "none", consumerBase)),
                List.of(List.of("provider"), List.of("consumer")),
                List.of(new PlanModel.PlanEdge("consumer", "provider", "PINNED", "NPM_OVERLAY")),
                List.of(), List.of());
        RunState state = new RunState("SPEC-2-v1", List.of(
                new RepoRun("provider", RepoState.SUCCEEDED, providerRun, providerCheckpoint, "ok", null),
                new RepoRun("consumer", RepoState.SUCCEEDED, consumerRun, consumerCheckpoint, "ok", null)),
                null, 0L);
        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-2-v1", "{}", "");
        Map<String, Path> paths = Map.of("provider", provider.path(), "consumer", consumer.path());

        RebuildPass.Outcome outcome = RebuildPass.run(Set.of("consumer"), plan, state, paths,
                config, runDir, store, false, new PrintWriter(new StringWriter()));

        // stagingFailures already means "verdicts that depend on this are not trustworthy", and
        // ReviewCommand refuses to exit 0 while it is non-empty. The consumer's own rebuild may
        // well pass; what must not happen is the pass being reported as proof.
        assertThat(outcome.stagingFailures()).isNotEmpty();
        assertThat(outcome.stagingFailures().toString()).contains("provider");
    }
}
