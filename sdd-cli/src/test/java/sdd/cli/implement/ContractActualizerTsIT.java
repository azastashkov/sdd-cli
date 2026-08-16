package sdd.cli.implement;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.contract.DeclaredContract;
import sdd.core.ts.TsSidecar;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The TypeScript kinds end to end: a real npm package on disk, the real compiler, and the body a
 * human reads at Gate 2. What this pins is the join between the two halves of the protocol — the
 * actualizer writes a body and {@code DeclaredContract} matches declarations against it, and the
 * only way to know those agree is to run both over the same tree.
 */
@Tag("node-it")
class ContractActualizerTsIT {
    @TempDir Path repo;

    @BeforeEach
    void requireNode() {
        Assumptions.assumeTrue(TsSidecar.create(null).isPresent(), "node not available");
    }

    private void write(String rel, String content) throws Exception {
        Path file = repo.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** The shape of trading-web-sdk: an exports map with a types-only subpath, a class with an
     *  http wrapper, and a re-exported interface declared in another file. */
    private void writeWebSdk() throws Exception {
        write("package.json", """
                {"name":"@azastashkov/web-sdk","version":"0.2.1",
                 "exports":{
                   ".":{"types":"./dist/index.d.ts","import":"./dist/index.js"},
                   "./contract":{"types":"./dist/contract.d.ts"}}}
                """);
        write("tsconfig.json", """
                {"compilerOptions":{"outDir":"dist","rootDir":"src","declaration":true}}
                """);
        write("src/types.ts", """
                /** A price tick. */
                export interface Tick {
                  price: number;
                  symbol: string;
                }
                """);
        write("src/index.ts", """
                export * from './types.js';
                export interface Session { token: string; }
                export async function login(body: LoginRequest): Promise<Session> {
                  const res = await fetch('/api/auth/login', { method: 'POST', body });
                  return res.json() as Promise<Session>;
                }
                export interface LoginRequest { user: string; }
                """);
        write("src/contract.ts", """
                export interface ShellContext {
                  registry: Tick;
                }
                import type { Tick } from './types.js';
                """);
    }

    private String actualize(String kind) throws Exception {
        PlanModel.PlanContract contract = new PlanModel.PlanContract(
                "c1", kind, "web-sdk", List.of("mfe-a"), "b", null, List.of());
        Map<String, String> bodies = ContractActualizer.actualize(repo, List.of(contract));
        return bodies.getOrDefault("c1", "");
    }

    @Test
    void anExportIsActualizedUnderTheSpecifierAConsumerImports() throws Exception {
        writeWebSdk();

        String body = actualize("ts-api");

        // Not the file it is declared in: a contract naming src/types.ts would be checkable and
        // yet name something no consumer can import, and the knowledge base records the specifier.
        assertThat(body).contains("@azastashkov/web-sdk\n");
        assertThat(body).contains("  Tick: interface\n");
        assertThat(body).contains("  Tick.price: number\n");
        assertThat(body).doesNotContain("src/types.ts");
    }

    @Test
    void aDeclarationWrittenAgainstThatBodyMatchesIt() throws Exception {
        writeWebSdk();
        String body = actualize("ts-api");

        // The two halves of the protocol, joined: whatever the actualizer emits is what a human
        // may declare, with no transformation step between them that either side could get wrong.
        assertThat(DeclaredContract.parse("ts-api", String.join("\n", List.of(
                        "@azastashkov/web-sdk#Tick.price: number",
                        "@azastashkov/web-sdk#Tick.symbol: string",
                        "@azastashkov/web-sdk#login(LoginRequest): Promise<Session>")))
                .missingFrom(body)).isEmpty();
    }

    @Test
    void aSubpathExportKeepsItsOwnSpecifier() throws Exception {
        writeWebSdk();

        assertThat(actualize("ts-api")).contains("@azastashkov/web-sdk/contract\n");
    }

    @Test
    void aMemberWithNoWrittenTypeIsMarkedUnresolvedRatherThanGuessed() throws Exception {
        write("package.json", """
                {"name":"@acme/lib","exports":{".":{"types":"./dist/index.d.ts"}}}
                """);
        write("tsconfig.json", "{\"compilerOptions\":{\"outDir\":\"dist\",\"rootDir\":\"src\"}}");
        write("src/index.ts", """
                export class Store {
                  ready = true;
                  size(): number { return 0; }
                }
                """);

        String body = actualize("ts-api");

        // `ready` has an inferred type. Inference needs the checker with lib files loaded, which
        // the sidecar deliberately does not do — so the type is marked unread, never guessed at.
        assertThat(body).contains("  Store.ready: ? [unresolved]\n");
        assertThat(body).contains("  Store.size(): number\n");
        assertThat(DeclaredContract.parse("ts-api", "@acme/lib#Store.ready: boolean")
                .unresolvedMembers(body)).containsExactly("@acme/lib#Store.ready:?");
    }

    @Test
    void callSitesAreActualizedWithTheFileAHumanCanOpen() throws Exception {
        writeWebSdk();

        String body = actualize("rest-client");

        assertThat(body).contains("POST /api/auth/login -> src/index.ts#login");
        assertThat(DeclaredContract.parse("rest-client", "POST /api/auth/login")
                .missingFrom(body)).isEmpty();
    }

    @Test
    void aPathMentionedOnlyInADocCommentIsNotACallSite() throws Exception {
        write("package.json", "{\"name\":\"@acme/lib\",\"main\":\"./src/index.ts\"}");
        write("src/index.ts", """
                /**
                 * Streams are published at /api/streams by the admin service.
                 */
                export async function load(): Promise<unknown> {
                  return (await fetch('/config/streams.json')).json();
                }
                """);

        String body = actualize("rest-client");

        // The regression lock for the AST-only rule. A text scraper records a call to /api/streams
        // from a repo that never makes one, which is a fabricated edge in the estate graph.
        assertThat(body).contains("GET /config/streams.json");
        assertThat(body).doesNotContain("/api/streams");
    }

    @Test
    void aRepoWithNoPackageJsonActualizesToNothingRatherThanFailing() throws Exception {
        write("src/main/java/A.java", "public class A {}");

        assertThat(actualize("ts-api")).isEmpty();
        assertThat(actualize("rest-client")).isEmpty();
    }
}
