package sdd.index.ts;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.ts.TsSidecar;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real compiler over a miniature package reproducing every call shape the estate actually uses.
 * Tagged and assumption-guarded so a machine without node skips it rather than failing.
 */
@Tag("node-it")
class TsCallSiteExtractionIT {
    @TempDir Path repo;

    private record Site(String verb, String path, String resolution, String kind) {}

    private TsSidecar sidecar() {
        Optional<TsSidecar> sidecar = TsSidecar.create(null);
        Assumptions.assumeTrue(sidecar.isPresent(), "node not available on this machine");
        return sidecar.get();
    }

    private List<Site> extract(String... sources) throws Exception {
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < sources.length; i += 2) {
            Path file = repo.resolve(sources[i]);
            Files.createDirectories(file.getParent());
            Files.writeString(file, sources[i + 1]);
            files.add(file);
        }
        TsSidecar.Result result = sidecar().httpCallSites(repo, files);
        assertThat(result.ok()).as("%s", result.error()).isTrue();
        List<Site> sites = new ArrayList<>();
        for (JsonNode s : result.json().path("sites")) {
            sites.add(new Site(s.path("verb").asText(),
                    s.path("pathValue").isNull() ? null : s.path("pathValue").asText(),
                    s.path("resolution").asText(),
                    s.path("kind").asText()));
        }
        return sites;
    }

    /** The wrapper the estate's SDK uses: verb encoded in the method name, path as the argument. */
    private static final String HTTP_CLIENT = """
            export class HttpClient {
              private readonly baseUrl: string;
              private readonly fetchImpl: typeof fetch;
              constructor(options: { baseUrl?: string; fetchImpl?: typeof fetch } = {}) {
                this.baseUrl = options.baseUrl ?? '';
                this.fetchImpl = options.fetchImpl ?? ((i, n) => fetch(i, n));
              }
              getJson<T>(path: string, headers?: Record<string, string>): Promise<T> {
                return this.request<T>('GET', path, undefined, headers);
              }
              postJson<T>(path: string, body: unknown): Promise<T> {
                return this.request<T>('POST', path, body);
              }
              private async request<T>(method: string, path: string, body?: unknown,
                                       extra?: Record<string, string>): Promise<T> {
                const response = await this.fetchImpl(this.baseUrl + path, { method, body: null });
                return response.json() as Promise<T>;
              }
            }
            """;

    @Test
    void aDocCommentIsNeverACallSite() throws Exception {
        // The regression lock for the rule the whole design rests on. The estate's own SDK
        // documents /api/streams in a JSDoc block and does not call it; a text scraper reports a
        // caller that does not exist, and nothing downstream can tell that fact from a real one.
        List<Site> sites = extract("src/http.ts", HTTP_CLIENT, "src/doc.ts", """
                import { HttpClient } from './http.js';
                /**
                 * Mirrors `/api/streams`, sibling of `/config/remotes.json`.
                 * See also POST /api/orders.
                 */
                export class Documented {
                  constructor(private readonly http: HttpClient) {}
                  real(): Promise<unknown> { return this.http.getJson('/api/real'); }
                }
                """);

        assertThat(sites).extracting(Site::path).containsExactly("/api/real");
    }

    @Test
    void verbComesFromTheWrapperChainNotTheMethodName() throws Exception {
        List<Site> sites = extract("src/http.ts", HTTP_CLIENT, "src/orders.ts", """
                import { HttpClient } from './http.js';
                export class Orders {
                  constructor(private readonly http: HttpClient) {}
                  submit(): Promise<unknown> { return this.http.postJson('/api/orders', {}); }
                  list(): Promise<unknown> { return this.http.getJson('/api/orders'); }
                }
                """);

        assertThat(sites).containsExactlyInAnyOrder(
                new Site("POST", "/api/orders", "LITERAL", "TS_HTTP_WRAPPER"),
                new Site("GET", "/api/orders", "LITERAL", "TS_HTTP_WRAPPER"));
    }

    @Test
    void aWrappersOwnInternalCallIsNotReported() throws Exception {
        // Reporting it would claim the SDK calls one unknown path in addition to every real one.
        List<Site> sites = extract("src/http.ts", HTTP_CLIENT, "src/one.ts", """
                import { HttpClient } from './http.js';
                export class One {
                  constructor(private readonly http: HttpClient) {}
                  go(): Promise<unknown> { return this.http.getJson('/api/one'); }
                }
                """);

        assertThat(sites).hasSize(1);
    }

    @Test
    void interpolationsFillingWholeSegmentsBecomeTemplateParameters() throws Exception {
        List<Site> sites = extract("src/http.ts", HTTP_CLIENT, "src/candles.ts", """
                import { HttpClient } from './http.js';
                export class Candles {
                  constructor(private readonly http: HttpClient) {}
                  symbols(t: string): Promise<unknown> {
                    const q = new URLSearchParams({ a: '1' });
                    return this.http.getJson(`/api/candles/${t}/symbols?${q.toString()}`);
                  }
                }
                """);

        // The query string is dropped — it is not part of a route — and the one interpolation
        // fills a whole segment, so it becomes a parameter the matcher can compare with Spring's.
        assertThat(sites).containsExactly(
                new Site("GET", "/api/candles/{}/symbols", "TEMPLATE_PARAM", "TS_HTTP_WRAPPER"));
    }

    @Test
    void anInterpolationInsideASegmentMakesTheWholePathUnresolvable() throws Exception {
        // `/api/v{}/orders` would match paths this call can never reach, because templatesMatch
        // compares segment by segment. Refusing to answer is the only correct answer.
        List<Site> sites = extract("src/http.ts", HTTP_CLIENT, "src/versioned.ts", """
                import { HttpClient } from './http.js';
                export class Versioned {
                  constructor(private readonly http: HttpClient) {}
                  go(major: string): Promise<unknown> {
                    return this.http.getJson(`/api/v${major}/orders`);
                  }
                }
                """);

        assertThat(sites).containsExactly(new Site("GET", null, "DYNAMIC", "TS_HTTP_WRAPPER"));
    }

    @Test
    void aBareFetchWithNoInitIsAGetAndAnInitObjectSuppliesTheVerb() throws Exception {
        List<Site> sites = extract("src/raw.ts", """
                export async function load(): Promise<unknown> {
                  const a = await fetch('/config/remotes.json');
                  const b = await fetch('/api/things', { method: 'DELETE' });
                  return [a, b];
                }
                """);

        assertThat(sites).containsExactlyInAnyOrder(
                new Site("GET", "/config/remotes.json", "LITERAL", "TS_FETCH"),
                new Site("DELETE", "/api/things", "LITERAL", "TS_FETCH"));
    }

    @Test
    void aVerbThatCannotBeReadIsAnyRatherThanAssumedToBeGet() throws Exception {
        // fetch's default is GET, but only when nothing overrides it. A spread might carry a
        // method, so claiming GET here would be inventing a fact.
        List<Site> sites = extract("src/raw.ts", """
                export async function load(init: RequestInit): Promise<unknown> {
                  return fetch('/api/things', { ...init });
                }
                """);

        assertThat(sites).containsExactly(new Site("ANY", "/api/things", "LITERAL", "TS_FETCH"));
    }
}
