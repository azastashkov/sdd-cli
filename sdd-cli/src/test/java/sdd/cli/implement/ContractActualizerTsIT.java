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
        return actualize(kind, List.of());
    }

    private String actualize(String kind, List<String> declared) throws Exception {
        PlanModel.PlanContract contract = new PlanModel.PlanContract(
                "c1", kind, "web-sdk", List.of("mfe-a"), "b", null, declared);
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
    void aDeclaredTsApiContractActualizesOnlyTheExportsItDeclares() throws Exception {
        writeWebSdk();

        String body = actualize("ts-api", List.of("@azastashkov/web-sdk#Tick.price: number"));

        // Same rule java-api has had since 5C-1: a declared block is the strongest selector there
        // is, and there is no whole-surface fallback beside it.
        assertThat(body).contains("@azastashkov/web-sdk\n");
        assertThat(body).contains("  Tick.price: number\n");
        assertThat(body).doesNotContain("Session");          // a real export of the same package
        assertThat(body).doesNotContain("LoginRequest");
        assertThat(body).doesNotContain("@azastashkov/web-sdk/contract");   // nothing declared there
    }

    @Test
    void aDeclarationOnALargePackageIsNotLostToTheTruncationCap() throws Exception {
        // Reproduces trading-web-sdk at Gate 2: ~53kB of declaration surface against MAX_BODY=4000,
        // so the declared members sat past the cut and the contract could only ever report
        // NOT_COMPARABLE — a conformance axis that cannot reach a verdict on a real package.
        StringBuilder src = new StringBuilder("export interface Wanted { field: string; }\n");
        for (int i = 0; i < 400; i++) {
            src.append("export interface Filler").append(i).append(" { a: string; b: number; }\n");
        }
        write("package.json", """
                {"name":"@acme/big","exports":{".":{"types":"./dist/index.d.ts"}}}
                """);
        write("tsconfig.json", """
                {"compilerOptions":{"outDir":"dist","rootDir":"src","declaration":true}}
                """);
        write("src/index.ts", src.toString());

        // The surface really is over the cap, so this test would pass vacuously without the guard.
        assertThat(actualize("ts-api")).contains(ContractActualizer.TRUNCATION_MARKER);

        String body = actualize("ts-api", List.of("@acme/big#Wanted.field: string"));

        assertThat(body).doesNotContain(ContractActualizer.TRUNCATION_MARKER);
        assertThat(DeclaredContract.parse("ts-api", "@acme/big#Wanted.field: string")
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

    // -- stream-descriptor ----------------------------------------------------------------------

    /** trading-web-sdk's own MD_STREAM_DESCRIPTOR, copied down to the property order. */
    private static final String STREAM_ENGINE = """
            import type { StreamDescriptorDto } from './types.js';
            export const MD_STREAM_DESCRIPTOR: StreamDescriptorDto = {
              stream: 'md',
              owner: 'pricing',
              products: ['PRODUCT1', 'PRODUCT2', 'PRODUCT3'],
              activation: 'onSubscribe',
              key: {
                fields: [
                  { name: 'clientId', required: true },
                  { name: 'securityType', required: true },
                ],
                entitlementField: 'clientId',
                productField: 'securityType',
              },
              channels: [
                { template: 'md.tick.{securityType}.{clientId}', fanout: 'channelKeyed', frameType: 'md.tick' },
                { template: 'md.reject.{securityType}.{clientId}', fanout: 'channelKeyed', frameType: 'md.reject' },
                { template: 'feed.status.{securityType}', scope: 'product', fanout: 'channelKeyed', frameType: 'feed.status' },
              ],
            };
            """;

    @Test
    void aTypeScriptDescriptorActualizesToTheSameTwoAxesTheJavaBuilderDoes() throws Exception {
        write("package.json", "{\"name\":\"@azastashkov/web-sdk\",\"main\":\"./src/index.ts\"}");
        write("src/stream-engine.ts", STREAM_ENGINE);

        String body = actualize("stream-descriptor");

        // Byte-identical to what the Java half produces, which is what lets one contract be
        // declared twice — once per provider — with the same block on both sides.
        assertThat(body).contains("md key clientId,securityType\n");
        assertThat(body).contains("md channels md.tick,md.reject,feed.status\n");
        assertThat(DeclaredContract.parse("stream-descriptor", String.join("\n", List.of(
                        "md key clientId,securityType",
                        "md channels md.tick,md.reject,feed.status")))
                .missingFrom(body)).isEmpty();
    }

    @Test
    void aKeyFieldOrderChangeIsADivergence() throws Exception {
        write("package.json", "{\"name\":\"@azastashkov/web-sdk\",\"main\":\"./src/index.ts\"}");
        write("src/stream-engine.ts", STREAM_ENGINE);

        // The order decides how a subscription key is encoded on the wire. Two ends that agree on
        // the set and not the order produce keys that never match, so a set comparison here would
        // call a live failure agreement.
        assertThat(DeclaredContract.parse("stream-descriptor", "md key securityType,clientId")
                .missingFrom(actualize("stream-descriptor")))
                .containsExactly("md key securityType,clientId");
    }

    @Test
    void aComputedDescriptorValueIsMarkedUnreadRatherThanResolved() throws Exception {
        write("package.json", "{\"name\":\"@acme/lib\",\"main\":\"./src/index.ts\"}");
        write("src/index.ts", """
                const TICK = 'md.tick';
                export const D = {
                  stream: 'md',
                  key: { fields: [{ name: 'clientId', required: true }] },
                  channels: [{ template: 'a', frameType: TICK }, { template: 'b', frameType: 'md.reject' }],
                };
                """);

        String body = actualize("stream-descriptor");

        // A descriptor is a wire format. A guess about a wire format that reaches a contract body
        // is worse than an admission that it could not be read.
        assertThat(body).contains("md channels ?,md.reject [unresolved]\n");
        assertThat(DeclaredContract.parse("stream-descriptor", "md channels md.tick,md.reject")
                .unresolvedMembers(body)).containsExactly("md channels");
        // ...and it excuses only its own axis, never the key.
        assertThat(DeclaredContract.parse("stream-descriptor", "md key nope")
                .unresolvedMembers(body)).doesNotContain("md key");
    }
}
