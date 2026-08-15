package sdd.agent.tool;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.ts.TsSidecar;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The gate exists because a syntactically broken file makes every later read and edit the agent
 * performs meaningless, and it usually cannot tell. Correctness is the build's job; parseability is
 * this gate's.
 */
class FileToolsSyntaxGateTest {
    @TempDir Path repo;

    private FileTools tools(Optional<TsSidecar> sidecar) {
        return new FileTools(new PathJail(repo), sidecar);
    }

    private FileTools plain() {
        return tools(Optional.empty());
    }

    // ------------------------------------------------------------------ JSON

    @Test
    void malformedJsonIsRejected() {
        // package.json is load-bearing now: dependency reading, version bumping and every npm
        // invocation go through it, and a malformed one fails with an error pointing nowhere near
        // the edit that caused it.
        assertThatThrownBy(() -> plain().applyEdit("package.json", "", "{ \"name\": }"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not valid JSON");
        assertThat(repo.resolve("package.json")).doesNotExist();
    }

    @Test
    void validJsonIsWritten() {
        plain().applyEdit("package.json", "", "{\"name\":\"x\",\"version\":\"1.0.0\"}");

        assertThat(repo.resolve("package.json")).exists();
    }

    // ------------------------------------------------------------------ TypeScript, no node

    @Test
    void withoutNodeATypeScriptEditIsAcceptedButTheMissingGateIsRecorded() {
        FileTools tools = plain();

        // Fails OPEN: rejecting an edit because the checker is missing would wedge the agent
        // against an error it cannot resolve, burning the escalation ladder for nothing.
        tools.applyEdit("src/broken.ts", "", "export function f( {\n");

        assertThat(repo.resolve("src/broken.ts")).exists();
        // ...but never SILENTLY: an unchecked edit is a recorded fact.
        assertThat(tools.tsGateUnavailable()).isNotNull().contains("node");
    }

    @Test
    void aJavaEditIsStillGatedExactlyAsBefore() {
        assertThatThrownBy(() -> plain().applyEdit("A.java", "", "class A { void x( }"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("syntax error");
    }

    // ------------------------------------------------------------------ TypeScript, real node

    private FileTools withSidecar() {
        Optional<TsSidecar> sidecar = TsSidecar.create(null);
        Assumptions.assumeTrue(sidecar.isPresent(), "node not available on this machine");
        return tools(sidecar);
    }

    @Test
    @Tag("node-it")
    void brokenTypeScriptIsRejectedAndNotWritten() {
        FileTools tools = withSidecar();

        assertThatThrownBy(() -> tools.applyEdit("src/broken.ts", "", "export function f( {\n"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("syntax error");

        assertThat(repo.resolve("src/broken.ts")).doesNotExist();
        assertThat(tools.tsGateUnavailable()).isNull();
    }

    @Test
    @Tag("node-it")
    void validTypeScriptIncludingGenericsAndJsxIsAccepted() throws Exception {
        FileTools tools = withSidecar();

        // Both of these defeat the obvious hand-rolled bracket heuristic, and a false rejection is
        // strictly worse than no gate: the agent cannot fix an error that is not there.
        tools.applyEdit("src/generic.ts", "", "export const lt = (a: number, b: number) => a < b;\n");
        tools.applyEdit("src/view.tsx", "", "export const C = () => <div a={1 < 2}>x</div>;\n");

        assertThat(Files.readString(repo.resolve("src/generic.ts"))).contains("a < b");
        assertThat(Files.readString(repo.resolve("src/view.tsx"))).contains("<div");
        assertThat(tools.tsGateUnavailable()).isNull();
    }
}
