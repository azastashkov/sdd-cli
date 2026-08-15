package sdd.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NpmToolTest {
    @TempDir Path repo;
    @TempDir Path nodeHome;

    /** The estate's real shape: useful scripts alongside two that must never be reachable. */
    private static final String ESTATE_PACKAGE_JSON = """
            {"name":"@acme/web-sdk","version":"0.2.1",
             "scripts":{
               "build":"tsc -p tsconfig.json",
               "test":"vitest run",
               "dev":"vite --port 3001",
               "release":"npm test && npm version patch && npm publish && git push --follow-tags"
             }}
            """;

    private void packageJson(String json) throws Exception {
        Files.writeString(repo.resolve("package.json"), json);
    }

    /** A fake npm on the PATH that node_home prepends, so no real npm is needed. */
    private void fakeNpm(String script) throws Exception {
        Path bin = Files.createDirectories(nodeHome.resolve("bin"));
        Path npm = bin.resolve("npm");
        Files.writeString(npm, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(npm, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test
    void theAllowlistIsAFixedSetNarrowedByTheRepoNeverWidenedByIt() throws Exception {
        packageJson(ESTATE_PACKAGE_JSON);

        // `release` runs `npm publish && git push --follow-tags`. Deriving the allowlist from
        // package.json would hand a model a tool that publishes to the public registry; `dev`
        // starts a server that never exits and would burn the whole timeout.
        assertThat(NpmTool.advertised(repo)).containsExactly("build", "test");
    }

    @Test
    void aRepoWithNoRecognisedScriptsAdvertisesNothing() throws Exception {
        packageJson("{\"name\":\"x\",\"scripts\":{\"dev\":\"vite\"}}");

        assertThat(NpmTool.advertised(repo)).isEmpty();
    }

    @Test
    void anUnknownScriptIsRefusedWithTheListOfWhatIsAvailable() throws Exception {
        packageJson(ESTATE_PACKAGE_JSON);
        NpmTool tool = new NpmTool(repo, nodeHome, Duration.ofSeconds(5));

        // npm answers an unknown script with an opaque ELIFECYCLE error; a model given that spends
        // turns guessing, so the refusal names what it could have run instead.
        assertThatThrownBy(() -> tool.run("typecheck"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("no npm script 'typecheck'")
                .hasMessageContaining("build, test");
    }

    @Test
    void aDisallowedScriptIsNeverRunEvenThoughTheRepoDefinesIt() throws Exception {
        packageJson(ESTATE_PACKAGE_JSON);
        fakeNpm("echo \"$@\" >> " + repo.resolve("ran.txt") + "; exit 0");
        NpmTool tool = new NpmTool(repo, nodeHome, Duration.ofSeconds(5));

        assertThatThrownBy(() -> tool.run("release")).isInstanceOf(ToolException.class);

        assertThat(repo.resolve("ran.txt")).doesNotExist();
    }

    @Test
    void commandShapeIsNpmRunScriptWithNoExtraArguments() throws Exception {
        packageJson(ESTATE_PACKAGE_JSON);
        // npm appends passthrough arguments to the END of the whole script string, so for a script
        // like `node a.mjs && node b.mjs` they would land on the wrong command. Nothing is appended.
        fakeNpm("for a in \"$@\"; do echo \"$a\" >> args.out; done; exit 0");
        NpmTool tool = new NpmTool(repo, nodeHome, Duration.ofSeconds(10));

        String out = tool.run("test");

        assertThat(out).startsWith("exit 0");
        assertThat(Files.readAllLines(repo.resolve("args.out"))).containsExactly("run", "test");
    }

    @Test
    void theEnvironmentWithholdsThePublishToken() throws Exception {
        packageJson(ESTATE_PACKAGE_JSON);
        fakeNpm("env > env.out; exit 0");
        new NpmTool(repo, nodeHome, Duration.ofSeconds(10)).run("test");

        List<String> env = Files.readAllLines(repo.resolve("env.out"));
        // Every publishing repo's .npmrc reads //registry.npmjs.org/:_authToken=${NPM_TOKEN}.
        // A credential that is never present cannot be spent by an accidental publish.
        assertThat(env).noneSatisfy(line -> assertThat(line).startsWith("NPM_TOKEN="));
        assertThat(env).anySatisfy(line -> assertThat(line).isEqualTo("CI=1"));
        assertThat(env).anySatisfy(line -> assertThat(line).isEqualTo("NO_COLOR=1"));
    }

    @Test
    void aFailingScriptReportsItsExitCodeRatherThanThrowing() throws Exception {
        packageJson(ESTATE_PACKAGE_JSON);
        fakeNpm("echo 'tests failed'; exit 1");
        NpmTool tool = new NpmTool(repo, nodeHome, Duration.ofSeconds(10));

        // The verdict is the exit code; VerificationRunner reads exactly this prefix.
        assertThat(tool.run("test")).startsWith("exit 1").contains("tests failed");
    }

    @Test
    void theToolIsNamedSoAModelCanTellTheTwoEcosystemsApart() throws Exception {
        packageJson(ESTATE_PACKAGE_JSON);
        NpmTool tool = new NpmTool(repo, nodeHome, Duration.ofSeconds(5));

        assertThat(tool.toolName()).isEqualTo("run_npm");
        assertThat(tool.taskDescription()).isEqualTo("build|test");
    }
}
