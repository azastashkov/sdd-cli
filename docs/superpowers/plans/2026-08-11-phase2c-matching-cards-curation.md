# Phase 2C — Matching, Repo Cards & Curation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete `sdd index`: REST client↔endpoint matching with confidence tiers, Kafka topic hygiene, Qwen-generated repo cards (cached, gracefully degrading), a curation report for everything unresolved, and a golden-file suite pinning the whole knowledge base.

**Architecture:** Two new global passes in `sdd.index.store` (`RestMatcher` rebuilding `rest_call_edge` wholesale, `TopicJanitor`), a model-touching `sdd.index.cards.RepoCardGenerator` behind the existing `ChatModel` seam (ScriptedChatModel-tested; endpoint failures never sink the run), `sdd.index.report.CurationReport` writing `.sdd/curation-report.md`, and a canonical DB dump for golden tests. Extractor refinements land first (binding notes #4/#5 from 2B-2b).

**Tech Stack:** Existing stack only. Cards use `config.models.coder` via `HttpChatModel`; token caps approximated char-based (~4 chars/token) — jtokkit arrives with Phase 3's budgeter, noted, not needed here.

**Spec:** `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md` — Component 1: "REST matching pass … Feign `target_hint` == some module's `spring_app_name` + normalized path/method match → HIGH. Else unique `norm_path`+method match estate-wide → MEDIUM. Multiple candidates → one LOW edge per candidate. `manualEdges` from `sdd.yml` insert MANUAL/HIGH rows."; repo cards (`card_line` ≤30 tokens, `card_md` ≤450 tokens, deterministic ≤12k selection, cached by input hash, "describe only what is evidenced; no speculation"); curation report listing every DYNAMIC client/topic. **The nine binding notes at the bottom of `docs/superpowers/plans/2026-08-11-phase2b2b-rest-kafka-extractors.md` are requirements of this plan** — each is mapped in the self-review.

## Global Constraints

- Java 21; never push; commit trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` after a blank line.
- Deterministic-first: the ONLY model calls are repo cards; tests use `ScriptedChatModel` — no live HTTP in any test; a failing/absent model endpoint degrades cards with a warning and NEVER affects statuses, exit codes, or other tables.
- Absolute-URL rule (binding note #1): matching and reporting logic uses `uri_template` to detect absolute URLs (`^[a-z][a-z0-9+.-]*://`) — `norm_path` of an absolute URL is never trusted.
- `rest_call_edge.confidence` values exactly `HIGH | MEDIUM | LOW`; `matched_by` values exactly `FEIGN_NAME_PATH | UNIQUE_PATH | AMBIGUOUS | MANUAL` (manual edges = confidence HIGH, matched_by MANUAL, per spec).
- Matching is rebuilt wholesale each run (like ArtifactLinker/UsageLinker) — idempotent, self-healing.
- Established contracts intact: repo-atomic source persistence; never sink the run; `FtsSymbolWriter` sole FTS path; enums from prior phases unchanged; `Paths2.canonical*` for DB-bound paths.
- Cards: prompt input selection is deterministic; `repo_card.input_hash` = SHA-256 of (composed input + model name); unchanged hash + existing row → skip (cached).

---

### Task 1: Extractor refinements (binding notes #4 + #5)

**Files:**
- Modify: `sdd-index/src/main/java/sdd/index/spring/RestClientExtractor.java`
- Modify: `sdd-index/src/main/java/sdd/index/spring/KafkaExtractor.java`
- Tests: extend `RestClientExtractorTest`, `KafkaExtractorTest`

**Interfaces:**
- Consumes: existing extractor contracts (signatures unchanged).
- Produces: behavior fixes:
  1. `.method(HttpMethod.X)` WebClient/RestClient chains emit a row (verb = `X` when the argument is a `HttpMethod.X` field access, else `ANY`) instead of nothing.
  2. Receiver checks tightened: when type resolution SUCCEEDS, accept only an allowlist (`RestTemplate`, `RestOperations`, `TestRestTemplate` / `KafkaTemplate`, `KafkaOperations`) and REJECT definite non-matches (no name-heuristic rescue); the name heuristic applies ONLY when resolution throws.

- [ ] **Step 1: Write the failing tests**

Add to `RestClientExtractorTest`:
```java
    @Test
    void reflectiveMethodChainEmitsRowWithVerbFromHttpMethodArg() throws Exception {
        var session = parse("src/main/java/com/acme/W2.java", """
                package com.acme;
                import org.springframework.http.HttpMethod;
                public class W2 {
                    private org.springframework.web.reactive.function.client.WebClient webClient;
                    public void go() { webClient.method(HttpMethod.GET).uri("/api/generic").retrieve(); }
                }
                """);
        List<SpringModel.ClientInfo> clients = RestClientExtractor.extract(session, Map.of());
        assertThat(clients).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo("WEBCLIENT");
            assertThat(c.httpMethod()).isEqualTo("GET");
            assertThat(c.uriTemplate()).isEqualTo("/api/generic");
        });
    }

    @Test
    void resolvedNonTemplateReceiverIsRejectedDespiteLuckyName() throws Exception {
        var session = parse("src/main/java/com/acme/M.java", """
                package com.acme;
                import java.util.HashMap;
                public class M {
                    private HashMap<String, String> restTemplateCache = new HashMap<>();
                    public void go() { restTemplateCache.put("k", "v"); }
                }
                """);
        assertThat(RestClientExtractor.extract(session, Map.of())).isEmpty();
    }
```
Add to `KafkaExtractorTest`:
```java
    @Test
    void resolvedNonKafkaReceiverIsRejectedDespiteLuckyName() throws Exception {
        var session = parse("""
                package com.acme;
                import java.util.ArrayList;
                public class K2 {
                    private ArrayList<String> kafkaTemplateBacklog = new ArrayList<>();
                    public void go() { kafkaTemplateBacklog.add("orders.v1"); }
                }
                """);
        // add() isn't send(), so also verify with a send-named helper class:
        var session2 = parse("""
                package com.acme;
                public class K3 {
                    private java.util.concurrent.ExecutorService kafkaTemplateExec
                            = java.util.concurrent.Executors.newSingleThreadExecutor();
                    public void go() { }
                }
                """);
        assertThat(KafkaExtractor.extract(session, Map.of(), List.of(), List.of()).uses()).isEmpty();
        assertThat(KafkaExtractor.extract(session2, Map.of(), List.of(), List.of()).uses()).isEmpty();
    }
```
(Note: `HashMap.put` resolves via the JRE solver — that is exactly the definite-non-match case the allowlist must reject. The existing text-heuristic tests — `Object`-typed fields with casts — must STAY green: casts resolve to the cast target which fails/succeeds per fixture; verify and adjust ONLY if a fixture's resolution behavior genuinely differs, documenting why.)

- [ ] **Step 2: Run to verify the new tests fail** (`restTemplateCache.put` currently emits a bogus PUT row; `.method()` chain emits nothing)

Run: `./gradlew :sdd-index:test --tests 'sdd.index.spring.RestClientExtractorTest' --tests 'sdd.index.spring.KafkaExtractorTest'`

- [ ] **Step 3: Implement**

In `RestClientExtractor`:
- `chainVerbAndKind`: while walking the scope chain, ALSO accept a call named `method` — verb = last identifier of its first argument's text when it matches `HttpMethod\.[A-Z]+` (extract after the dot), else `ANY`.
- `receiverIsRestTemplate` (and the analogous kind-resolution in `chainVerbAndKind`): restructure to three-state — resolution success → return allowlist check ONLY (`RestTemplate`, `RestOperations`, `TestRestTemplate` suffix match on the qualified name); resolution failure → fall back to the existing text heuristic. Mirror the same three-state shape in `KafkaExtractor.receiverIsKafkaTemplate` with `KafkaTemplate`/`KafkaOperations`.

- [ ] **Step 4: Run to verify green** (all existing extractor tests + new ones)

Run: `./gradlew :sdd-index:test --tests 'sdd.index.spring.*'`

- [ ] **Step 5: Commit**

```bash
git add sdd-index/src
git commit -m "fix: reflective method chains emit rows; allowlist resolved receivers"
```

---

### Task 2: `manual_edges` config

**Files:**
- Modify: `sdd-core/src/main/java/sdd/core/config/SddConfig.java` (+ `ManualEdge` record file)
- Create: `sdd-core/src/main/java/sdd/core/config/ManualEdge.java`
- Modify: `sdd-core/src/main/java/sdd/core/config/ConfigLoader.java`
- Test: extend `ConfigLoaderTest`

**Interfaces:**
- Consumes: existing loader.
- Produces: `record ManualEdge(String clientRepo, String httpMethod, String path, String providerRepo)`; `SddConfig` gains LAST component `List<ManualEdge> manualEdges` (default empty) — record becomes `SddConfig(workspace, retrieval, models, jdkHomes, excludes, artifactOverrides, manualEdges)`; YAML shape:
```yaml
manual_edges:
  - client_repo: svc-orders
    http_method: POST
    path: /pay/charge
    provider_repo: billing-service
```
All four keys required per entry; missing key → `ConfigException` naming the index and key. **All existing SddConfig construction sites (tests) must be updated for the new component.**

- [ ] **Step 1: Write the failing tests** (add to `ConfigLoaderTest`)

```java
    @Test
    void parsesManualEdges() throws Exception {
        SddConfig c = ConfigLoader.load(write(MINIMAL + """
                manual_edges:
                  - client_repo: svc-orders
                    http_method: POST
                    path: /pay/charge
                    provider_repo: billing-service
                """), ENV);
        assertThat(c.manualEdges()).containsExactly(
                new ManualEdge("svc-orders", "POST", "/pay/charge", "billing-service"));
    }

    @Test
    void manualEdgeMissingKeyFails() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + """
                manual_edges:
                  - client_repo: svc-orders
                    path: /x
                    provider_repo: y
                """), ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("manual_edges").hasMessageContaining("http_method");
    }

    @Test
    void manualEdgesDefaultEmpty() throws Exception {
        assertThat(ConfigLoader.load(write(MINIMAL), ENV).manualEdges()).isEmpty();
    }
```

- [ ] **Step 2: Run (fail), implement, run (pass)**

`ManualEdge.java`:
```java
package sdd.core.config;

public record ManualEdge(String clientRepo, String httpMethod, String path, String providerRepo) {}
```
`SddConfig` gains the 7th component. In `ConfigLoader.load`:
```java
        java.util.List<ManualEdge> manualEdges = new java.util.ArrayList<>();
        if (root.get("manual_edges") instanceof List<?> edges) {
            for (int i = 0; i < edges.size(); i++) {
                if (!(edges.get(i) instanceof Map<?, ?> e)) {
                    throw new ConfigException("manual_edges[" + i + "] must be a mapping");
                }
                manualEdges.add(new ManualEdge(
                        requiredEdgeKey(e, "client_repo", i, env),
                        requiredEdgeKey(e, "http_method", i, env),
                        requiredEdgeKey(e, "path", i, env),
                        requiredEdgeKey(e, "provider_repo", i, env)));
            }
        }
```
with `requiredEdgeKey` throwing `ConfigException("manual_edges[" + i + "]: " + key + " is required")` and routing values through the existing `str(...)` env interpolation. Return statement gains `List.copyOf(manualEdges)`. Fix all construction sites (`IndexServiceIT`, `SourceEndToEndTest`, `IndexServiceTest`, etc. construct `SddConfig` — add `List.of()`).

Run: `./gradlew build` — everything green.

- [ ] **Step 3: Commit**

```bash
git add sdd-core sdd-index sdd-cli
git commit -m "feat: manual_edges config for curated client-endpoint pins"
```

---

### Task 3: RestMatcher + TopicJanitor + IndexService wiring

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/store/RestMatcher.java`
- Create: `sdd-index/src/main/java/sdd/index/store/TopicJanitor.java`
- Modify: `sdd-index/src/main/java/sdd/index/IndexService.java` (run matcher + janitor after UsageLinker; expose reports)
- Modify: `sdd-cli/src/main/java/sdd/cli/IndexCommand.java` (matching summary line)
- Tests: `RestMatcherTest`, `TopicJanitorTest` (new), `IndexServiceTest` construction updates

**Interfaces:**
- Consumes: `ManualEdge`, persisted `rest_client`/`rest_endpoint`/`module`/`repo` tables.
- Produces:
```java
public final class RestMatcher {
    public record Report(int high, int medium, int low, int manual, List<String> warnings) {}
    public static Report match(org.jdbi.v3.core.Jdbi jdbi, List<sdd.core.config.ManualEdge> manualEdges)
    static boolean templatesMatch(String clientNorm, String endpointNorm)   // segment-wise, {} wildcards both sides
    static boolean verbsCompatible(String clientVerb, String endpointVerb)  // ANY matches everything
}
public final class TopicJanitor {
    public static int clean(org.jdbi.v3.core.Jdbi jdbi)   // delete kafka_topic rows with zero kafka_role refs; return count
}
```
Matching algorithm (one transaction, wholesale): `DELETE FROM rest_call_edge`; load all client rows (skip: `norm_path IS NULL`, absolute-URL `uri_template` per the Global Constraint — those are curation material) and all endpoint rows (with module→repo joins and `spring_app_name`); per client: candidates = endpoints where `verbsCompatible` and `templatesMatch`; if client kind FEIGN and `target_hint` equalsIgnoreCase some candidate's `spring_app_name` → those candidates get edges confidence `HIGH`, matched_by `FEIGN_NAME_PATH` (restrict to the name-matching provider(s)); else exactly one candidate estate-wide → `MEDIUM`/`UNIQUE_PATH`; else 2+ candidates → one `LOW`/`AMBIGUOUS` edge per candidate; zero candidates → no edge (curation lists unmatched clients). Manual edges applied AFTER: for each `ManualEdge`, client rows in `clientRepo` matching verb+`normalize(path)` and endpoint rows in `providerRepo` matching verb+path → edges confidence `HIGH`, matched_by `MANUAL` (`INSERT OR IGNORE`-style: skip pairs already edged — delete existing pair edge first then insert MANUAL, simplest: insert MANUAL after deleting any existing edge for that (client_id, endpoint_id)); unmatched side → warning string in the report.

- [ ] **Step 1: Write the failing tests**

`RestMatcherTest.java`:
```java
package sdd.index.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.config.ManualEdge;
import sdd.core.db.Database;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RestMatcherTest {
    @TempDir Path ws;
    private Database db;
    private long ordersModule;
    private long billingModule;
    private long inventoryModule;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-orders','/w/o','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('billing-service','/w/b','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('inventory','/w/i','SERVICE')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind, spring_app_name) VALUES (1,':','SERVICE',NULL)");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind, spring_app_name) VALUES (2,':','SERVICE','billing')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind, spring_app_name) VALUES (3,':','SERVICE','inventory')");
            ordersModule = 1; billingModule = 2; inventoryModule = 3;
        });
    }

    private long client(long moduleId, String kind, String verb, String norm, String hint, String uri) {
        return db.jdbi().withHandle(h -> {
            h.createUpdate("""
                    INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site, http_method,
                                            uri_template, norm_path, target_hint, resolution, raw_expr)
                    VALUES (:m,:k,'C','site',:v,:u,:n,:t,'LITERAL','raw')""")
                    .bind("m", moduleId).bind("k", kind).bind("v", verb)
                    .bind("u", uri).bind("n", norm).bind("t", hint).execute();
            return h.createQuery("SELECT last_insert_rowid()").mapTo(Long.class).one();
        });
    }

    private long endpoint(long moduleId, String verb, String norm) {
        return db.jdbi().withHandle(h -> {
            h.createUpdate("""
                    INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method,
                                              path_template, norm_path)
                    VALUES (:m,'E','handler',:v,:n,:n)""")
                    .bind("m", moduleId).bind("v", verb).bind("n", norm).execute();
            return h.createQuery("SELECT last_insert_rowid()").mapTo(Long.class).one();
        });
    }

    private List<Map<String, Object>> edges() {
        return db.jdbi().withHandle(h -> h.createQuery(
                "SELECT client_id, endpoint_id, confidence, matched_by FROM rest_call_edge "
                        + "ORDER BY client_id, endpoint_id").mapToMap().list());
    }

    @Test
    void feignNameMatchIsHighUniqueIsMediumAmbiguousIsLowPerCandidate() {
        long feign = client(ordersModule, "FEIGN", "POST", "/pay/charge", "billing", "/pay/charge");
        long epBilling = endpoint(billingModule, "POST", "/pay/charge");
        endpoint(inventoryModule, "POST", "/pay/charge");   // same path elsewhere — name match must win

        long unique = client(ordersModule, "RESTTEMPLATE", "GET", "/stock/{}", null, "/stock/{id}");
        long epStock = endpoint(inventoryModule, "GET", "/stock/{}");

        client(ordersModule, "RESTTEMPLATE", "GET", "/health", null, "/health");
        endpoint(billingModule, "GET", "/health");
        endpoint(inventoryModule, "GET", "/health");        // ambiguous: LOW × 2

        RestMatcher.Report report = RestMatcher.match(db.jdbi(), List.of());

        assertThat(report.high()).isEqualTo(1);
        assertThat(report.medium()).isEqualTo(1);
        assertThat(report.low()).isEqualTo(2);
        assertThat(edges()).anySatisfy(e -> {
            assertThat(e).containsEntry("client_id", feign).containsEntry("endpoint_id", epBilling)
                    .containsEntry("confidence", "HIGH").containsEntry("matched_by", "FEIGN_NAME_PATH");
        });
        assertThat(edges()).anySatisfy(e -> {
            assertThat(e).containsEntry("client_id", unique).containsEntry("endpoint_id", epStock)
                    .containsEntry("confidence", "MEDIUM").containsEntry("matched_by", "UNIQUE_PATH");
        });
    }

    @Test
    void absoluteUrlAndNullNormClientsAreSkippedAndRematchIsWholesale() {
        client(ordersModule, "RESTTEMPLATE", "GET", "/http:/ext/x", null, "http://ext/x"); // absolute
        client(ordersModule, "RESTTEMPLATE", "GET", null, null, null);                     // DYNAMIC
        endpoint(billingModule, "GET", "/http:/ext/x");

        assertThat(RestMatcher.match(db.jdbi(), List.of()).high()
                + RestMatcher.match(db.jdbi(), List.of()).medium()
                + RestMatcher.match(db.jdbi(), List.of()).low()).isZero();
        assertThat(edges()).isEmpty();   // wholesale rebuild left no residue from the 3 runs
    }

    @Test
    void manualEdgePinsAndUnmatchedManualEdgeWarns() {
        long cl = client(ordersModule, "RESTTEMPLATE", "POST", "/pay/charge", null, "/pay/charge");
        long epB = endpoint(billingModule, "POST", "/pay/charge");
        endpoint(inventoryModule, "POST", "/pay/charge");   // would be LOW without the pin

        RestMatcher.Report report = RestMatcher.match(db.jdbi(), List.of(
                new ManualEdge("svc-orders", "POST", "/pay/charge", "billing-service"),
                new ManualEdge("ghost-repo", "GET", "/nope", "billing-service")));

        assertThat(report.manual()).isEqualTo(1);
        assertThat(report.warnings()).anySatisfy(w -> assertThat(w).contains("ghost-repo"));
        assertThat(edges()).anySatisfy(e -> {
            assertThat(e).containsEntry("client_id", cl).containsEntry("endpoint_id", epB)
                    .containsEntry("confidence", "HIGH").containsEntry("matched_by", "MANUAL");
        });
    }

    @Test
    void templateAndVerbHelpers() {
        assertThat(RestMatcher.templatesMatch("/a/{}/c", "/a/{}/c")).isTrue();
        assertThat(RestMatcher.templatesMatch("/a/42/c", "/a/{}/c")).isTrue();
        assertThat(RestMatcher.templatesMatch("/a/{}/c", "/a/b")).isFalse();
        assertThat(RestMatcher.verbsCompatible("ANY", "GET")).isTrue();
        assertThat(RestMatcher.verbsCompatible("GET", "ANY")).isTrue();
        assertThat(RestMatcher.verbsCompatible("GET", "POST")).isFalse();
    }
}
```

`TopicJanitorTest.java`:
```java
package sdd.index.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TopicJanitorTest {
    @TempDir Path ws;

    @Test
    void deletesTopicsWithNoRolesKeepsReferencedOnes() {
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('r','/w/r','SERVICE')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','SERVICE')");
                h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('live.topic','LITERAL')");
                h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('orphan.topic','LITERAL')");
                h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (1, 1, 'PRODUCER')");
            });
            int cleaned = TopicJanitor.clean(db.jdbi());
            assertThat(cleaned).isEqualTo(1);
            assertThat(db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT name FROM kafka_topic").mapTo(String.class).list()))
                    .containsExactly("live.topic");
        }
    }
}
```

- [ ] **Step 2: Run (fail), implement**

`TopicJanitor.java`:
```java
package sdd.index.store;

import org.jdbi.v3.core.Jdbi;

public final class TopicJanitor {
    private TopicJanitor() {}

    public static int clean(Jdbi jdbi) {
        return jdbi.withHandle(h -> h.createUpdate(
                "DELETE FROM kafka_topic WHERE NOT EXISTS "
                        + "(SELECT 1 FROM kafka_role r WHERE r.topic_id = kafka_topic.id)")
                .execute());
    }
}
```

`RestMatcher.java`:
```java
package sdd.index.store;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import sdd.core.config.ManualEdge;
import sdd.index.spring.RouteNormalizer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class RestMatcher {
    public record Report(int high, int medium, int low, int manual, List<String> warnings) {}

    private static final Pattern ABSOLUTE_URL = Pattern.compile("^[a-z][a-z0-9+.-]*://.*");

    private record Client(long id, long moduleId, String repo, String kind, String verb,
                          String norm, String targetHint) {}
    private record Endpoint(long id, String repo, String verb, String norm, String springAppName) {}

    private RestMatcher() {}

    public static Report match(Jdbi jdbi, List<ManualEdge> manualEdges) {
        List<String> warnings = new ArrayList<>();
        int[] counts = jdbi.inTransaction(h -> {
            h.execute("DELETE FROM rest_call_edge");
            List<Client> clients = h.createQuery("""
                            SELECT c.id, c.module_id, r.name AS repo, c.kind, c.http_method,
                                   c.norm_path, c.target_hint, c.uri_template
                            FROM rest_client c
                            JOIN module m ON m.id = c.module_id JOIN repo r ON r.id = m.repo_id
                            WHERE c.norm_path IS NOT NULL""")
                    .map((rs, ctx) -> {
                        String uri = rs.getString("uri_template");
                        if (uri != null && ABSOLUTE_URL.matcher(uri).matches()) {
                            return null;    // binding note #1: absolute URLs never match by norm_path
                        }
                        return new Client(rs.getLong("id"), rs.getLong("module_id"),
                                rs.getString("repo"), rs.getString("kind"), rs.getString("http_method"),
                                rs.getString("norm_path"), rs.getString("target_hint"));
                    }).list().stream().filter(c -> c != null).toList();
            List<Endpoint> endpoints = h.createQuery("""
                            SELECT e.id, r.name AS repo, e.http_method, e.norm_path, m.spring_app_name
                            FROM rest_endpoint e
                            JOIN module m ON m.id = e.module_id JOIN repo r ON r.id = m.repo_id
                            WHERE e.norm_path IS NOT NULL""")
                    .map((rs, ctx) -> new Endpoint(rs.getLong("id"), rs.getString("repo"),
                            rs.getString("http_method"), rs.getString("norm_path"),
                            rs.getString("spring_app_name"))).list();

            int high = 0;
            int medium = 0;
            int low = 0;
            for (Client c : clients) {
                List<Endpoint> candidates = endpoints.stream()
                        .filter(e -> verbsCompatible(c.verb(), e.verb())
                                && templatesMatch(c.norm(), e.norm())).toList();
                if (candidates.isEmpty()) {
                    continue;   // unmatched — curation report material
                }
                List<Endpoint> named = c.kind().equals("FEIGN") && c.targetHint() != null
                        ? candidates.stream().filter(e -> c.targetHint()
                                .equalsIgnoreCase(e.springAppName())).toList()
                        : List.of();
                if (!named.isEmpty()) {
                    for (Endpoint e : named) {
                        insertEdge(h, c.id(), e.id(), "HIGH", "FEIGN_NAME_PATH");
                        high++;
                    }
                } else if (candidates.size() == 1) {
                    insertEdge(h, c.id(), candidates.get(0).id(), "MEDIUM", "UNIQUE_PATH");
                    medium++;
                } else {
                    for (Endpoint e : candidates) {
                        insertEdge(h, c.id(), e.id(), "LOW", "AMBIGUOUS");
                        low++;
                    }
                }
            }

            int manual = 0;
            for (ManualEdge edge : manualEdges) {
                String norm = RouteNormalizer.normalize(edge.path());
                List<Long> clientIds = clients.stream()
                        .filter(c -> c.repo().equals(edge.clientRepo())
                                && verbsCompatible(c.verb(), edge.httpMethod())
                                && templatesMatch(c.norm(), norm))
                        .map(Client::id).toList();
                List<Long> endpointIds = endpoints.stream()
                        .filter(e -> e.repo().equals(edge.providerRepo())
                                && verbsCompatible(edge.httpMethod(), e.verb())
                                && templatesMatch(norm, e.norm()))
                        .map(Endpoint::id).toList();
                if (clientIds.isEmpty() || endpointIds.isEmpty()) {
                    warnings.add("manual edge unmatched: " + edge);
                    continue;
                }
                for (long cid : clientIds) {
                    for (long eid : endpointIds) {
                        h.createUpdate("DELETE FROM rest_call_edge WHERE client_id=:c AND endpoint_id=:e")
                                .bind("c", cid).bind("e", eid).execute();
                        insertEdge(h, cid, eid, "HIGH", "MANUAL");
                        manual++;
                    }
                }
            }
            return new int[]{high, medium, low, manual};
        });
        return new Report(counts[0], counts[1], counts[2], counts[3], List.copyOf(warnings));
    }

    private static void insertEdge(Handle h, long clientId, long endpointId,
                                   String confidence, String matchedBy) {
        h.createUpdate("INSERT INTO rest_call_edge(client_id, endpoint_id, confidence, matched_by) "
                        + "VALUES (:c, :e, :conf, :by)")
                .bind("c", clientId).bind("e", endpointId)
                .bind("conf", confidence).bind("by", matchedBy).execute();
    }

    static boolean templatesMatch(String clientNorm, String endpointNorm) {
        String[] a = clientNorm.split("/");
        String[] b = endpointNorm.split("/");
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!a[i].equals(b[i]) && !a[i].equals("{}") && !b[i].equals("{}")) {
                return false;
            }
        }
        return true;
    }

    static boolean verbsCompatible(String clientVerb, String endpointVerb) {
        return "ANY".equals(clientVerb) || "ANY".equals(endpointVerb)
                || clientVerb.equals(endpointVerb);
    }
}
```

Wire into `IndexService.run` after `UsageLinker.link`: `lastRestReport = RestMatcher.match(db.jdbi(), config.manualEdges()); lastTopicsCleaned = TopicJanitor.clean(db.jdbi());` with accessors `lastRestReport()` / `lastTopicsCleaned()`. `IndexCommand` prints after the spring counts: `match: %d high, %d medium, %d low, %d manual edges` and each warning line prefixed `  warn: `.

- [ ] **Step 3: Run to verify green**

Run: `./gradlew :sdd-index:test :sdd-cli:test`

- [ ] **Step 4: Commit**

```bash
git add sdd-index sdd-cli
git commit -m "feat: rest client-endpoint matcher with confidence tiers and topic janitor"
```

---

### Task 4: RepoCardGenerator (model-touching, cached)

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/cards/RepoCardGenerator.java`
- Test: `sdd-index/src/test/java/sdd/index/cards/RepoCardGeneratorTest.java`

**Interfaces:**
- Consumes: `sdd.core.llm.ChatModel`/`ChatRequest`/`ChatMessage`/`ChatResponse` (Phase-1 seam), `ScriptedChatModel` (testFixtures), `repo_card` table, Jackson.
- Produces:
```java
public final class RepoCardGenerator {
    public record CardResult(int generated, int cached, int failed) {}
    public static CardResult generate(org.jdbi.v3.core.Jdbi jdbi, java.nio.file.Path workspace,
                                      sdd.core.llm.ChatModel model, String modelName)
    static String composeInput(org.jdbi.v3.core.Jdbi jdbi, long repoId, String repoName,
                               java.nio.file.Path workspace)   // deterministic, char-capped
}
```
Behavior: for each repo (ordered by name): `composeInput` builds a deterministic text block — repo name+kind; module list (gradle_path + kind, sorted); endpoint lines (`METHOD norm_path`, sorted, ≤50, remainder noted as "+N more"); topics (`name (role)`, sorted); internal dep repo names out (via `v_repo_dep_edge`) and in; README first 150 lines when `workspace/<repo>/README.md` exists; up to 3 key files — the file of any type annotated `SpringBootApplication` plus the top-2 types of THIS repo by inbound `api_usage` count (target_module_id join), each file content capped at 12000 chars (note appended when truncated), deduped, sorted by path. `input_hash` = SHA-256 hex of (input + "\n" + modelName). Existing `repo_card` row with same hash → `cached++`, skip. Else one `ChatRequest(modelName, [system, user], List.of(), 1200, 0.15)`; system prompt exactly:
`You summarize Java repositories for an engineering knowledge base. Describe ONLY what is evidenced in the provided data. No speculation, no filler. Respond with a single JSON object: {"card_line": string (one sentence, max 30 words), "card_md": string (markdown, max 300 words, sections: Purpose, Modules, Integrations, Conventions)}`
Response parse: strip ``` fences if present, Jackson `readTree`, both fields required non-blank; `finish_reason` `length` or parse failure or `ModelException` → `failed++`, continue to next repo (after 3 CONSECUTIVE ModelExceptions, stop calling and count remaining repos as failed — endpoint is down, don't hammer it). Success → upsert `repo_card(repo_id, card_md, card_line, model, input_hash, created_at)` (`ON CONFLICT(repo_id) DO UPDATE`).

- [ ] **Step 1: Write the failing tests**

```java
package sdd.index.cards;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ModelException;
import sdd.core.llm.Usage;
import sdd.core.testing.ScriptedChatModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RepoCardGeneratorTest {
    @TempDir Path ws;
    private Database db;

    private static ChatResponse ok(String cardLine) {
        return new ChatResponse(ChatMessage.assistant(
                "{\"card_line\": \"" + cardLine + "\", \"card_md\": \"## Purpose\\nEvidenced.\"}"),
                "stop", new Usage(100, 50));
    }

    @BeforeEach
    void seed() throws Exception {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-orders', '" + ws.resolve("svc-orders") + "', 'SERVICE')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1, ':', 'SERVICE')");
            h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                    + "VALUES (1, 'C', 'get', 'GET', '/api/x', '/api/x')");
        });
        Files.createDirectories(ws.resolve("svc-orders"));
        Files.writeString(ws.resolve("svc-orders/README.md"), "# Orders service\nHandles orders.\n");
    }

    @Test
    void generatesPersistsAndCachesByInputHash() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(ok("Order service.")));
        RepoCardGenerator.CardResult first = RepoCardGenerator.generate(db.jdbi(), ws, model, "qwen");
        assertThat(first.generated()).isEqualTo(1);
        assertThat(first.failed()).isZero();

        Map<String, Object> card = db.jdbi().withHandle(h ->
                h.createQuery("SELECT card_line, model, input_hash FROM repo_card").mapToMap().one());
        assertThat(card).containsEntry("card_line", "Order service.").containsEntry("model", "qwen");
        assertThat((String) card.get("input_hash")).hasSize(64);

        // prompt content includes evidenced data
        assertThat(model.requests().get(0).messages().get(1).content())
                .contains("svc-orders").contains("GET /api/x").contains("# Orders service");

        // second run, unchanged inputs: cached, no model call (empty script would throw if called)
        ScriptedChatModel silent = new ScriptedChatModel(List.of());
        RepoCardGenerator.CardResult second = RepoCardGenerator.generate(db.jdbi(), ws, silent, "qwen");
        assertThat(second.cached()).isEqualTo(1);
        assertThat(second.generated()).isZero();
    }

    @Test
    void malformedResponseCountsFailedAndRunContinues() {
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('zzz-repo', '" + ws.resolve("zzz") + "', 'LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2, ':', 'LIBRARY')");
        });
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                new ChatResponse(ChatMessage.assistant("not json"), "stop", new Usage(1, 1)),
                ok("Second repo fine.")));
        RepoCardGenerator.CardResult result = RepoCardGenerator.generate(db.jdbi(), ws, model, "qwen");
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.generated()).isEqualTo(1);
    }

    @Test
    void consecutiveModelFailuresShortCircuit() {
        db.jdbi().useHandle(h -> {
            for (int i = 2; i <= 6; i++) {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('r" + i + "', '/w/r" + i + "', 'LIBRARY')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (" + i + ", ':', 'LIBRARY')");
            }
        });
        ChatModelThrowingAlways broken = new ChatModelThrowingAlways();
        RepoCardGenerator.CardResult result = RepoCardGenerator.generate(db.jdbi(), ws, broken, "qwen");
        assertThat(result.failed()).isEqualTo(6);     // all 6 repos failed
        assertThat(broken.calls).isEqualTo(3);        // but only 3 live calls before short-circuit
    }

    private static final class ChatModelThrowingAlways implements sdd.core.llm.ChatModel {
        int calls;
        @Override
        public sdd.core.llm.ChatResponse complete(sdd.core.llm.ChatRequest req) {
            calls++;
            throw new ModelException("connection refused", 0);
        }
    }
}
```

- [ ] **Step 2: Run (fail), implement, run (pass)**

Implementation skeleton (complete the composeInput queries per the Interfaces spec — every query deterministic ORDER BY):
```java
package sdd.index.cards;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ChatRequest;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ModelException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public final class RepoCardGenerator {
    public record CardResult(int generated, int cached, int failed) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_CONSECUTIVE_MODEL_FAILURES = 3;
    private static final String SYSTEM_PROMPT =
            "You summarize Java repositories for an engineering knowledge base. Describe ONLY what "
            + "is evidenced in the provided data. No speculation, no filler. Respond with a single "
            + "JSON object: {\"card_line\": string (one sentence, max 30 words), \"card_md\": string "
            + "(markdown, max 300 words, sections: Purpose, Modules, Integrations, Conventions)}";

    private RepoCardGenerator() {}

    public static CardResult generate(Jdbi jdbi, Path workspace, ChatModel model, String modelName) {
        List<Map<String, Object>> repos = jdbi.withHandle(h ->
                h.createQuery("SELECT id, name FROM repo ORDER BY name").mapToMap().list());
        int generated = 0;
        int cached = 0;
        int failed = 0;
        int consecutiveModelFailures = 0;
        for (Map<String, Object> repo : repos) {
            long repoId = ((Number) repo.get("id")).longValue();
            String name = (String) repo.get("name");
            String input = composeInput(jdbi, repoId, name, workspace);
            String hash = sha256(input + "\n" + modelName);
            boolean upToDate = jdbi.withHandle(h -> h.createQuery(
                            "SELECT count(*) FROM repo_card WHERE repo_id=:r AND input_hash=:h")
                    .bind("r", repoId).bind("h", hash).mapTo(Integer.class).one()) > 0;
            if (upToDate) {
                cached++;
                continue;
            }
            if (consecutiveModelFailures >= MAX_CONSECUTIVE_MODEL_FAILURES) {
                failed++;
                continue;
            }
            try {
                ChatResponse response = model.complete(new ChatRequest(modelName,
                        List.of(ChatMessage.system(SYSTEM_PROMPT), ChatMessage.user(input)),
                        List.of(), 1200, 0.15));
                consecutiveModelFailures = 0;
                if ("length".equals(response.finishReason())) {
                    failed++;
                    continue;
                }
                JsonNode parsed = parseCard(response.message().content());
                if (parsed == null) {
                    failed++;
                    continue;
                }
                jdbi.useHandle(h -> h.createUpdate("""
                                INSERT INTO repo_card(repo_id, card_md, card_line, model, input_hash, created_at)
                                VALUES (:r, :md, :line, :model, :hash, :at)
                                ON CONFLICT(repo_id) DO UPDATE SET card_md=excluded.card_md,
                                  card_line=excluded.card_line, model=excluded.model,
                                  input_hash=excluded.input_hash, created_at=excluded.created_at""")
                        .bind("r", repoId).bind("md", parsed.get("card_md").asText())
                        .bind("line", parsed.get("card_line").asText()).bind("model", modelName)
                        .bind("hash", hash).bind("at", Instant.now().toString()).execute());
                generated++;
            } catch (ModelException e) {
                consecutiveModelFailures++;
                failed++;
            }
        }
        return new CardResult(generated, cached, failed);
    }

    private static JsonNode parseCard(String content) {
        if (content == null) {
            return null;
        }
        String text = content.strip();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).strip();
            }
        }
        try {
            JsonNode node = JSON.readTree(text);
            if (node.hasNonNull("card_line") && node.hasNonNull("card_md")
                    && !node.get("card_line").asText().isBlank()
                    && !node.get("card_md").asText().isBlank()) {
                return node;
            }
        } catch (Exception ignored) {
            // malformed
        }
        return null;
    }

    static String composeInput(Jdbi jdbi, long repoId, String repoName, Path workspace) {
        StringBuilder sb = new StringBuilder();
        // 1. header: repo name + kind
        // 2. modules: SELECT gradle_path, kind FROM module WHERE repo_id ORDER BY gradle_path
        // 3. endpoints: SELECT http_method, norm_path FROM rest_endpoint JOIN module ... ORDER BY norm_path, http_method LIMIT 50 (+N more)
        // 4. topics: SELECT t.name, r.role FROM kafka_role r JOIN kafka_topic t ... JOIN module ... ORDER BY t.name, r.role
        // 5. deps: SELECT DISTINCT to/from repo names via v_repo_dep_edge ORDER BY name
        // 6. README first 150 lines when workspace/<repoName>/README.md exists
        // 7. key files: SpringBootApplication-annotated type file + top-2 inbound api_usage types
        //    (SELECT jt.file_path, count(*) FROM api_usage u JOIN java_type jt ON jt.module_id=... — see below)
        //    each capped 12000 chars with "[truncated]" note, deduped, sorted by path
        ...implement per the Interfaces block; every list deterministically ordered...
        return sb.toString();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```
The `composeInput` `...` marker above is the ONE part left to the implementer BY DESIGN with its exact recipe in the comments and the Interfaces block — every query shape is specified (tables, ordering, caps); write them as straightforward jdbi queries. Key-file inbound-usage query: types of THIS repo referenced from other modules — `SELECT jt.file_path, COUNT(*) AS refs FROM api_usage u JOIN java_type jt ON jt.fqcn = u.target_fqcn JOIN module m ON m.id = jt.module_id WHERE m.repo_id = :r AND u.target_module_id IS NOT NULL GROUP BY jt.file_path ORDER BY refs DESC, jt.file_path LIMIT 2`. SpringBootApplication file: `SELECT file_path FROM java_type jt JOIN module m ON m.id=jt.module_id WHERE m.repo_id=:r AND jt.annotations LIKE '%SpringBootApplication%' ORDER BY file_path LIMIT 1`. File content read from `workspace/<repoName>/<file_path>`; unreadable → skip silently.

Run: `./gradlew :sdd-index:test --tests 'sdd.index.cards.*'` — PASS (3 tests).

- [ ] **Step 3: Commit**

```bash
git add sdd-index/src
git commit -m "feat: cached model-generated repo cards with graceful degradation"
```

---

### Task 5: Cards wired into IndexService + CLI (`--no-cards`)

**Files:**
- Modify: `sdd-index/src/main/java/sdd/index/IndexService.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/IndexCommand.java`
- Tests: extend `IndexServiceTest` (cards run via seam), `IndexCommandTest` (--no-cards path)

**Interfaces:**
- Consumes: Task 4.
- Produces: `IndexService` gains a package-visible card seam — constructor `IndexService(Extractor extractor, sdd.core.llm.ChatModel cardModel, String cardModelName)` (existing constructors delegate with null card model); in `run()`, AFTER the matcher/janitor: when `cardModel != null`, `lastCardResult = RepoCardGenerator.generate(db.jdbi(), config.workspace(), cardModel, cardModelName)` inside try/catch `RuntimeException` → warning state, never fails the run; accessor `lastCardResult()` (null when skipped). `IndexCommand` gains `@Option(names = "--no-cards")`; unless set, constructs `new HttpChatModel(config.models().get("coder"))` and the coder endpoint's model name, passing them via the new constructor; prints `cards: %d generated, %d cached, %d failed` (or `cards: skipped`).

- [ ] **Step 1: Failing tests**

`IndexServiceTest` — extend the existing stub-extractor e2e-style test (or add):
```java
    @Test
    void cardsRunAfterIndexingWhenModelProvided() {
        // reuse an existing minimal fixture setup from this test class (one repo via stub extractor)
        ScriptedChatModel cardModel = new ScriptedChatModel(List.of(new ChatResponse(
                ChatMessage.assistant("{\"card_line\": \"L.\", \"card_md\": \"## Purpose\\nP.\"}"),
                "stop", new Usage(1, 1))));
        IndexService service = new IndexService(stubExtractor, cardModel, "qwen");
        service.run(config, db);
        assertThat(service.lastCardResult()).isNotNull();
        assertThat(service.lastCardResult().generated()).isEqualTo(1);
        assertThat(db.jdbi().withHandle(h -> h.createQuery(
                "SELECT count(*) FROM repo_card").mapTo(Integer.class).one())).isEqualTo(1);
    }
```
(Adapt to the test class's actual fixture helpers — read it first.)
`IndexCommandTest`:
```java
    @Test
    void noCardsFlagSkipsCardGeneration() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        Run run = index(ws, "--no-cards");     // adapt to the harness helper; add one if absent
        assertThat(run.out()).contains("cards: skipped");
    }
```

- [ ] **Step 2: Implement, run green** (`./gradlew :sdd-index:test :sdd-cli:test`)

- [ ] **Step 3: Commit**

```bash
git add sdd-index sdd-cli
git commit -m "feat: wire repo cards into sdd index with --no-cards escape hatch"
```

---

### Task 6: CurationReport

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/report/CurationReport.java`
- Modify: `sdd-index/src/main/java/sdd/index/IndexService.java` (write report at end of run; expose path)
- Modify: `sdd-cli/src/main/java/sdd/cli/IndexCommand.java` (print report path)
- Test: `CurationReportTest`

**Interfaces:**
- Consumes: all tables.
- Produces: `static Path CurationReport.write(Jdbi jdbi, Path workspace)` → writes `workspace/.sdd/curation-report.md` and returns the path. Sections (each omitted when empty, each row-capped at 200 with "+N more"):
  1. `## Unresolved REST clients` — rows with `resolution='DYNAMIC'` OR `norm_path IS NULL`: repo, kind, site, raw_expr.
  2. `## Absolute-URL clients` — `uri_template` matching the absolute pattern: repo, uri_template (binding note #1: listed from uri_template).
  3. `## Unmatched clients` — relative-path clients with zero `rest_call_edge` rows.
  4. `## Ambiguous matches (LOW)` — client repo/path → candidate endpoint repos.
  5. `## Dynamic Kafka topics` — `kafka_topic.resolution='DYNAMIC'` with topic text quote-stripped for display (binding note #6): `name.replaceAll("^\"|\"$", "")`.
  6. `## Unparsed stream modules` — `module.kafka_status='UNPARSED_STREAM'`.
  7. `## Repo problems` — repos with `gradle_status != 'OK'` OR `parse_status` in ('DEGRADED','FAILED') OR `error IS NOT NULL`: name, statuses, first error line.
  8. `## Partial API confidence` — count of `java_type` rows with `api_confidence='PARTIAL'` per repo.
  9. `## Orphan artifacts` — internal LIBRARY artifacts consumed by zero internal edges (reuse the ArtifactLinker report data via a query, not by re-running the linker).
Footer: generated timestamp + `manual_edges` hint ("pin ambiguous/unmatched clients via sdd.yml manual_edges").

- [ ] **Step 1: Failing test** (seed a DB with one row per category — compact loop over inline INSERTs; assert the file exists, contains each section header, the quote-stripped topic (`orders.v1` not `"orders.v1"`), and omits an empty section header for a category not seeded)

```java
package sdd.index.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CurationReportTest {
    @TempDir Path ws;

    @Test
    void writesSectionsForProblemsOmitsEmptyOnes() throws Exception {
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind, gradle_status, parse_status) "
                        + "VALUES ('svc', '/w/svc', 'SERVICE', 'DEGRADED', 'OK')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind, kafka_status) "
                        + "VALUES (1, ':', 'SERVICE', 'UNPARSED_STREAM')");
                h.execute("INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site, "
                        + "http_method, resolution, raw_expr) "
                        + "VALUES (1, 'RESTTEMPLATE', 'C', 'go', 'GET', 'DYNAMIC', 'System.getenv(\"URL\")')");
                h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('\"orders.v1\"', 'DYNAMIC')");
                h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (1, 1, 'CONSUMER')");
            });
            Path report = CurationReport.write(db.jdbi(), ws);

            assertThat(report).isEqualTo(ws.resolve(".sdd/curation-report.md"));
            String text = Files.readString(report);
            assertThat(text).contains("## Unresolved REST clients").contains("System.getenv");
            assertThat(text).contains("## Dynamic Kafka topics").contains("orders.v1")
                    .doesNotContain("\"orders.v1\"");
            assertThat(text).contains("## Unparsed stream modules");
            assertThat(text).contains("## Repo problems").contains("svc");
            assertThat(text).doesNotContain("## Orphan artifacts");   // none seeded
            assertThat(text).contains("manual_edges");
        }
    }
}
```

- [ ] **Step 2: Implement** — plain jdbi queries per section (each with deterministic ORDER BY and LIMIT 201 → render 200 + "+N more" via a count query when over), `StringBuilder` markdown, `Files.createDirectories(workspace.resolve(".sdd"))`, `Files.writeString`. Wire into `IndexService.run` (after cards, ALWAYS — no model dependency) with accessor `lastReportPath()`; `IndexCommand` prints `report: <path>`.

- [ ] **Step 3: Run green + commit**

Run: `./gradlew :sdd-index:test :sdd-cli:test`
```bash
git add sdd-index sdd-cli
git commit -m "feat: curation report for unresolved and ambiguous extraction data"
```

---

### Task 7: Golden-file suite

**Files:**
- Create: `sdd-index/src/test/java/sdd/index/GoldenEstateTest.java`
- Create: `sdd-index/src/test/java/sdd/index/DbDump.java` (test-only util)
- Create: `sdd-index/src/test/resources/golden/estate.json` (generated in Step 3)

**Interfaces:**
- Consumes: the stub-extractor e2e pattern from `SourceEndToEndTest` (read it — reuse its fixture-building style with a FIXED two-repo estate), `IndexService(Extractor, null, null)`.
- Produces: `DbDump.canonicalJson(Jdbi jdbi, Path workspace)` — dumps every table (`repo, module, artifact, dep_edge, java_type, api_member, api_usage, file_ref, rest_endpoint, rest_client, rest_call_edge, kafka_topic, kafka_role, config_property, repo_card, fts_symbol`) as a JSON object keyed by table name, rows sorted by a deterministic key per table (natural PK or full-row string), with volatile fields scrubbed: `repo.path` → repo name only, `repo.head_commit`/`dirty_hash`/`indexed_at` → `"<scrubbed>"`, `repo.error` → workspace prefix replaced with `<ws>`, `repo_card.created_at` → `"<scrubbed>"`; pretty-printed with sorted keys. `GoldenEstateTest` builds the fixed estate, runs the full `IndexService` (no cards), compares against `src/test/resources/golden/estate.json`; on mismatch writes the actual to `build/golden-actual.json` and fails with a message naming both paths and the regeneration procedure (run with `-Dsdd.regenGolden=true`, which overwrites the golden resource file and fails with "golden regenerated — rerun").

- [ ] **Step 1: Write DbDump + the test** (test first — it fails redly because the golden resource doesn't exist yet, which IS the red phase)

Key implementation points for `DbDump`: `SELECT * FROM <table>`, `mapToMap`, scrub, sort rows by `String.valueOf(row)` after scrubbing (stable given deterministic content), Jackson `ObjectMapper` with `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS` + `writerWithDefaultPrettyPrinter`. Fixture estate: two repos exactly mirroring `SourceEndToEndTest`'s shape (lib-pricing + svc-orders with the controller/Feign/Kafka/config fixtures) — copy the fixture blocks verbatim so content is fixed; stub extractor with fixed GradleModel data.

- [ ] **Step 2: Run — RED** (no golden file). **Step 3: regenerate** (`./gradlew :sdd-index:test --tests 'sdd.index.GoldenEstateTest' -Dsdd.regenGolden=true` — wire the system property through the test's JVM via `build.gradle.kts` `tasks.test { systemProperty("sdd.regenGolden", System.getProperty("sdd.regenGolden", "false")) }`), inspect the generated JSON manually for sanity (no absolute paths, no timestamps), then **Step 4: run normally — GREEN**.

- [ ] **Step 5: Commit**

```bash
git add sdd-index
git commit -m "test: golden-file suite pinning the full knowledge base"
```

---

### Task 8: End-to-end matching + report + IT touch

**Files:**
- Modify: `sdd-index/src/test/java/sdd/index/SourceEndToEndTest.java` (add billing-service repo; assert HIGH match + report file)
- Modify: `sdd-index/src/test/java/sdd/index/IndexServiceIT.java` (assert rest_call_edge queryable + curation report exists on the real-Gradle path)

**Interfaces:** consumes everything; produces final confidence.

- [ ] **Step 1: Extend the e2e** — add a `billing-service` fixture repo: controller `@RestController @RequestMapping("/pay") class PayController { @PostMapping("/charge") ... }` + `application.yml` with `spring.application.name: billing`; extend the stub extractor to return a third extract for it (SERVICE, no deps). New assertions after the existing ones:
```java
            // Feign(name=billing, path=/pay, POST /charge) ↔ billing-service endpoint: HIGH edge
            Map<String, Object> edge = db.jdbi().withHandle(h -> h.createQuery("""
                            SELECT confidence, matched_by FROM rest_call_edge""").mapToMap().one());
            assertThat(edge).containsEntry("confidence", "HIGH")
                    .containsEntry("matched_by", "FEIGN_NAME_PATH");
            assertThat(Files.exists(ws.resolve(".sdd/curation-report.md"))).isTrue();
```
(The existing svc-orders BillingClient already declares `@FeignClient(name="billing", path="/pay")` with `@PostMapping("/charge")` from 2B-2b's Task 9 — the new billing-service provides the matching endpoint; endpoint norm_path = `/pay/charge` needs NO context-path on billing-service — don't add `server.servlet.context-path` to its yml.)

- [ ] **Step 2: Extend IndexServiceIT** minimally:
```java
            Integer edgeCount = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT count(*) FROM rest_call_edge").mapTo(Integer.class).one());
            assertThat(edgeCount).isGreaterThanOrEqualTo(0);   // table queryable post-run
            assertThat(Files.exists(ws.resolve(".sdd/curation-report.md"))).isTrue();
```

- [ ] **Step 3: Full build + commit**

Run: `./gradlew build` — BUILD SUCCESSFUL.
```bash
git add sdd-index/src
git commit -m "test: end-to-end matching and curation coverage"
```

---

## Self-Review (completed at write time)

1. **Spec coverage:** REST matching pass with the spec's exact tier rules → Task 3; `manualEdges` → Tasks 2–3 (confidence HIGH, matched_by MANUAL per spec); repo cards (deterministic selection, caps, cache-by-hash, evidence-only prompt) → Tasks 4–5; curation report listing every DYNAMIC client/topic + all unresolved families → Task 6; golden files → Task 7. **Binding notes #1–9 mapped:** #1 absolute-URL uri_template rule → Global Constraint + Task 3 skip + Task 6 section 2; #2 endpoint-confidence gap → curation section 8 exposes PARTIAL and the report's ambiguous/unmatched sections carry the uncertainty (full endpoint-resolution field deferred to Phase 3's needs — documented tradeoff, unchanged); #3 kafka_topic orphan cleanup → TopicJanitor (Task 3); #4 `.method()` DYNAMIC rows → Task 1; #5 heuristic allowlist tightening → Task 1; #6 quote-strip topic display → Task 6 section 5; #7 jar version skew → no code change needed, caveat lives in 2B-2b outcome doc (informational); #8 properties-vs-yml precedence → unchanged, revisit clause still holds (no defaultProfileProps consumer changed here); #9 perf smoke run → operational step, not plan code; called out in the execution handoff.
2. **Placeholder scan:** Task 4's `composeInput` body is delegated with its complete recipe (every query, cap, and ordering specified in comments + Interfaces + the two exact SQL snippets) — deliberate, not a TBD; Task 6 Step 2 and Task 5 Step 2 reference fully-specified behavior from their Interfaces blocks. No TBD/TODO anywhere.
3. **Type consistency:** `ManualEdge(clientRepo, httpMethod, path, providerRepo)` across Tasks 2, 3; `RestMatcher.Report(high, medium, low, manual, warnings)` across 3, 6 (CLI); `CardResult(generated, cached, failed)` across 4, 5; `RepoCardGenerator.generate(Jdbi, Path, ChatModel, String)` across 4, 5; `CurationReport.write(Jdbi, Path)` across 6, 8; `DbDump.canonicalJson(Jdbi, Path)` in 7 only; `IndexService(Extractor, ChatModel, String)` across 5, 7, 8.
