# Phase 3C-3 — sdd graph Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `sdd graph [--workspace <ws>] [--out <file>]` renders the knowledge base's estate graph as deterministic Mermaid — repo nodes styled by kind, Gradle edges labeled by consumption mode, REST and Kafka edges as visually distinct link types — to stdout or a file.

**Architecture:** One read-only, model-free renderer `sdd.index.report.MermaidGraph` beside `CurationReport` (the KB-view precedent), plus a config-free `GraphCommand` in sdd-cli (like `ApproveCommand`, it needs no sdd.yml — only the KB). Design authority: the 2026-08-12 "sdd graph command" amendment at the bottom of `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md`.

**Tech Stack:** Java 21, Jdbi 3, picocli. NO new dependencies.

## Global Constraints

- Java 21; no new dependencies; read-only over the KB; ZERO model calls; no config-file requirement.
- Determinism law (amendment): same KB → byte-identical output. Every query carries a full ORDER BY; the renderer contains no timestamps or randomness.
- Mermaid dialect exactly:
  - header `graph LR` then three classDefs (`service`, `library`, `unknown`);
  - one node line per repo (ORDER BY name): `<id>["<name>"]:::<class>` where `<class>` is the lowercased kind for SERVICE/LIBRARY and `unknown` otherwise;
  - node ids = repo name with every non-alphanumeric char replaced by `_`; on a sanitized-id collision the later name (alphabetical order) gets `_2`, `_3`, … appended;
  - Gradle edges (v_repo_dep_edge, ORDER BY consumer, provider, mode): `<consumer> -->|<MODE>| <provider>`;
  - REST edges (DISTINCT client-repo/provider-repo/confidence via rest_call_edge, cross-repo only, ORDER BY client, provider, confidence): `<client> -.->|REST <confidence>| <provider>`;
  - Kafka edges (DISTINCT producer-repo/consumer-repo/topic, cross-repo only, ORDER BY producer, consumer, topic): `<producer> ==>|<topic>| <consumer>`;
  - two-space indentation for every line after `graph LR`; edge-label text sanitized of `|` (→ `/`).
- CLI taxonomy unchanged: missing KB (`.sdd/index.db` absent, no-create) or zero repos → `error: knowledge base is empty — run sdd index first` stderr exit 1; stdout output by default; `--out <file>` writes the file (plain write — a generated view, not a gate artifact) and prints `graph written: <path>`; `Locale.ROOT`.
- Never read or print `.env`. Never push.

---

## File Structure

**Task 1:** `sdd-index/src/main/java/sdd/index/report/MermaidGraph.java` + `sdd-index/src/test/java/sdd/index/report/MermaidGraphTest.java`
**Task 2:** `sdd-cli/src/main/java/sdd/cli/GraphCommand.java`; `sdd-cli/src/main/java/sdd/cli/SddCli.java` (register) + `sdd-cli/src/test/java/sdd/cli/GraphCommandTest.java`

---

### Task 1: MermaidGraph renderer

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/report/MermaidGraph.java`
- Test: `sdd-index/src/test/java/sdd/index/report/MermaidGraphTest.java`

**Interfaces:**
- Produces: `public final class MermaidGraph { public static String render(Jdbi jdbi) }` — Task 2 consumes it.

- [ ] **Step 1: Write the failing test:**

```java
package sdd.index.report;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MermaidGraphTest {
    @TempDir Path ws;
    private Database db;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/1','LIBRARY')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-orders','/w/2','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('tools.misc','/w/3','UNKNOWN')");
            for (int i = 1; i <= 3; i++) {
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (" + i + ",':','UNKNOWN')");
            }
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (2,'com.acme','lib-core','compileClasspath','1.0','DIRECT','PINNED',1,1)");
            h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                    + "VALUES (2,'OrdersController','get','GET','/orders/{id}','/orders/{}')");
            h.execute("INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site, http_method, uri_template, norm_path, target_hint, resolution, raw_expr) "
                    + "VALUES (3,'FEIGN','OrdersClient','site','GET','/orders/{id}','/orders/{}','orders','LITERAL','raw')");
            h.execute("INSERT INTO rest_call_edge(client_id, endpoint_id, confidence, matched_by) VALUES (1,1,'HIGH','FEIGN_NAME_PATH')");
            h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('orders.events','LITERAL')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (2,1,'PRODUCER')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (1,1,'CONSUMER')");
        });
    }

    @Test
    void rendersNodesByKindAndAllThreeEdgeTypesDeterministically() {
        String first = MermaidGraph.render(db.jdbi());
        String second = MermaidGraph.render(db.jdbi());

        assertThat(first).isEqualTo(second);
        assertThat(first).startsWith("graph LR\n");
        assertThat(first)
                .contains("  classDef service ")
                .contains("  lib_core[\"lib-core\"]:::library\n")
                .contains("  svc_orders[\"svc-orders\"]:::service\n")
                .contains("  tools_misc[\"tools.misc\"]:::unknown\n")
                .contains("  svc_orders -->|PINNED| lib_core\n")
                .contains("  tools_misc -.->|REST HIGH| svc_orders\n")
                .contains("  svc_orders ==>|orders.events| lib_core\n");
        // ordering: all nodes before all edges; gradle before rest before kafka
        assertThat(first.indexOf("tools_misc[\"")).isLessThan(first.indexOf(" -->|"));
        assertThat(first.indexOf(" -->|")).isLessThan(first.indexOf(" -.->|"));
        assertThat(first.indexOf(" -.->|")).isLessThan(first.indexOf(" ==>|"));
    }

    @Test
    void sanitizedIdCollisionsGetNumericSuffixes() {
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('tools-misc','/w/4','LIBRARY')");
        });

        String md = MermaidGraph.render(db.jdbi());

        // 'tools-misc' < 'tools.misc' alphabetically: tools-misc keeps tools_misc,
        // tools.misc gets tools_misc_2
        assertThat(md).contains("  tools_misc[\"tools-misc\"]:::library\n")
                .contains("  tools_misc_2[\"tools.misc\"]:::unknown\n")
                .contains("  tools_misc_2 -.->|REST HIGH| svc_orders\n");
    }

    @Test
    void pipeInEdgeLabelsIsSanitized() {
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('weird|topic','LITERAL')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (2,2,'PRODUCER')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (1,2,'CONSUMER')");
        });

        String md = MermaidGraph.render(db.jdbi());

        assertThat(md).contains("  svc_orders ==>|weird/topic| lib_core\n")
                .doesNotContain("weird|topic");
    }

    @Test
    void emptyKbRendersHeaderAndClassDefsOnly() {
        try (Database empty = Database.open(ws.resolve("empty-ws"))) {
            String md = MermaidGraph.render(empty.jdbi());

            assertThat(md).startsWith("graph LR\n");
            assertThat(md.lines().filter(l -> l.contains("[\""))).isEmpty();
            assertThat(md).doesNotContain("-->").doesNotContain("-.->").doesNotContain("==>");
        }
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.**
Run: `./gradlew :sdd-index:test --tests 'sdd.index.report.MermaidGraphTest'`

- [ ] **Step 3: Implement** `MermaidGraph.java`:

```java
package sdd.index.report;

import org.jdbi.v3.core.Jdbi;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Renders the knowledge base's estate graph as Mermaid (design amendment 2026-08-12).
 * Read-only, model-free, deterministic: same KB => byte-identical output. Repo nodes are
 * styled by kind; Gradle edges carry the consumption mode; REST and Kafka links use
 * distinct arrow styles so the three relationship families read apart at a glance.
 */
public final class MermaidGraph {

    private MermaidGraph() {
    }

    public static String render(Jdbi jdbi) {
        StringBuilder md = new StringBuilder("graph LR\n");
        md.append("  classDef service fill:#1f6feb,color:#ffffff\n");
        md.append("  classDef library fill:#2da44e,color:#ffffff\n");
        md.append("  classDef unknown fill:#6e7781,color:#ffffff\n");

        Map<String, String> idOf = new LinkedHashMap<>();
        jdbi.useHandle(h -> {
            for (Map<String, Object> row : h.createQuery(
                            "SELECT name, kind FROM repo ORDER BY name")
                    .mapToMap().list()) {
                String name = String.valueOf(row.get("name"));
                String kind = String.valueOf(row.get("kind"));
                String styleClass = switch (kind) {
                    case "SERVICE" -> "service";
                    case "LIBRARY" -> "library";
                    default -> "unknown";
                };
                String id = uniqueId(idOf, name);
                idOf.put(name, id);
                md.append("  ").append(id).append("[\"").append(name).append("\"]:::")
                        .append(styleClass).append('\n');
            }
            for (Map<String, Object> row : h.createQuery("""
                            SELECT DISTINCT rf.name AS consumer, rt.name AS provider, v.mode AS mode
                            FROM v_repo_dep_edge v
                            JOIN repo rf ON rf.id = v.from_repo_id
                            JOIN repo rt ON rt.id = v.to_repo_id
                            ORDER BY rf.name, rt.name, v.mode""")
                    .mapToMap().list()) {
                md.append("  ").append(idOf.get(String.valueOf(row.get("consumer"))))
                        .append(" -->|").append(label(String.valueOf(row.get("mode"))))
                        .append("| ").append(idOf.get(String.valueOf(row.get("provider"))))
                        .append('\n');
            }
            for (Map<String, Object> row : h.createQuery("""
                            SELECT DISTINCT rc.name AS client, rp.name AS provider, ce.confidence AS confidence
                            FROM rest_call_edge ce
                            JOIN rest_client c ON c.id = ce.client_id
                            JOIN module mc ON mc.id = c.module_id
                            JOIN repo rc ON rc.id = mc.repo_id
                            JOIN rest_endpoint e ON e.id = ce.endpoint_id
                            JOIN module mp ON mp.id = e.module_id
                            JOIN repo rp ON rp.id = mp.repo_id
                            WHERE rc.name <> rp.name
                            ORDER BY rc.name, rp.name, ce.confidence""")
                    .mapToMap().list()) {
                md.append("  ").append(idOf.get(String.valueOf(row.get("client"))))
                        .append(" -.->|REST ").append(label(String.valueOf(row.get("confidence"))))
                        .append("| ").append(idOf.get(String.valueOf(row.get("provider"))))
                        .append('\n');
            }
            for (Map<String, Object> row : h.createQuery("""
                            SELECT DISTINCT rp.name AS producer, rc.name AS consumer, t.name AS topic
                            FROM kafka_role prod
                            JOIN kafka_topic t ON t.id = prod.topic_id
                            JOIN module mp ON mp.id = prod.module_id
                            JOIN repo rp ON rp.id = mp.repo_id
                            JOIN kafka_role cons ON cons.topic_id = prod.topic_id AND cons.role = 'CONSUMER'
                            JOIN module mc ON mc.id = cons.module_id
                            JOIN repo rc ON rc.id = mc.repo_id
                            WHERE prod.role = 'PRODUCER' AND rp.name <> rc.name
                            ORDER BY rp.name, rc.name, t.name""")
                    .mapToMap().list()) {
                md.append("  ").append(idOf.get(String.valueOf(row.get("producer"))))
                        .append(" ==>|").append(label(String.valueOf(row.get("topic"))))
                        .append("| ").append(idOf.get(String.valueOf(row.get("consumer"))))
                        .append('\n');
            }
        });
        return md.toString();
    }

    private static String uniqueId(Map<String, String> idOf, String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
        String candidate = base;
        int suffix = 2;
        while (idOf.containsValue(candidate)) {
            candidate = base + "_" + suffix++;
        }
        return candidate;
    }

    private static String label(String value) {
        return value.replace("|", "/");
    }
}
```

- [ ] **Step 4: Run — expect PASS, then commit**

```bash
./gradlew :sdd-index:test
git add sdd-index/src
git commit -m "feat: deterministic mermaid renderer over the knowledge base"
```

---

### Task 2: GraphCommand CLI + e2e

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/GraphCommand.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/SddCli.java` (subcommands gains `GraphCommand.class`)
- Test: `sdd-cli/src/test/java/sdd/cli/GraphCommandTest.java`

**Interfaces:**
- Consumes: `MermaidGraph.render(Jdbi)` (Task 1), `Database.open`, the no-create KB check pattern (`Files.exists(<ws>/.sdd/index.db)` then in-db `repoCount == 0` — both → the exact `error: knowledge base is empty — run sdd index first`).
- Produces: `sdd graph [--workspace <ws>] [--out <file>]` — stdout by default; `--out` writes the file plainly and prints `graph written: <path>`; exit 0; no config file loaded.

- [ ] **Step 1: Write the failing tests:**

```java
package sdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.core.db.Database;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GraphCommandTest {
    @TempDir Path ws;

    private record Run(int exitCode, String out) {}

    private Run graph(String... args) {
        StringWriter sw = new StringWriter();
        CommandLine cl = new CommandLine(new GraphCommand());
        cl.setOut(new PrintWriter(sw, true));
        cl.setErr(new PrintWriter(sw, true));
        return new Run(cl.execute(args), sw.toString());
    }

    private void seedKb() {
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/1','LIBRARY')");
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-a','/w/2','SERVICE')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2,':','SERVICE')");
                h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                        + "VALUES (2,'com.acme','lib-core','compileClasspath','1.0','DIRECT','PINNED',1,1)");
            });
        }
    }

    @Test
    void printsMermaidToStdoutByDefault() {
        seedKb();

        Run run = graph("--workspace", ws.toString());

        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("graph LR")
                .contains("svc_a[\"svc-a\"]:::service")
                .contains("svc_a -->|PINNED| lib_core");
    }

    @Test
    void outOptionWritesTheFileInsteadOfPrintingTheGraph() throws Exception {
        seedKb();
        Path target = ws.resolve("estate.mmd");

        Run run = graph("--workspace", ws.toString(), "--out", target.toString());

        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("graph written: " + target)
                .doesNotContain("classDef");
        assertThat(Files.readString(target)).startsWith("graph LR\n").contains("-->|PINNED|");
    }

    @Test
    void missingKnowledgeBaseFailsWithoutCreatingIt() {
        Run run = graph("--workspace", ws.toString());

        assertThat(run.out()).contains("error: knowledge base is empty — run sdd index first");
        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(Files.exists(ws.resolve(".sdd/index.db"))).isFalse();
    }

    @Test
    void graphIsRegisteredOnTheRootCommand() {
        seedKb();
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(new SddCli());
        cmd.setOut(new PrintWriter(sw, true));
        cmd.setErr(new PrintWriter(sw, true));

        int code = cmd.execute("graph", "--workspace", ws.toString());

        assertThat(sw.toString()).contains("graph LR");
        assertThat(code).isZero();
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement `GraphCommand.java`:

```java
package sdd.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import sdd.core.db.Database;
import sdd.index.report.MermaidGraph;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "graph", description = "Render the knowledge base's estate graph as Mermaid")
public final class GraphCommand implements Callable<Integer> {
    @Option(names = "--workspace", description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

    @Option(names = "--out", description = "Write the graph to a file instead of stdout")
    Path out;

    @Spec CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter outWriter = spec.commandLine().getOut();
        PrintWriter errWriter = spec.commandLine().getErr();
        try {
            if (!Files.exists(workspace.resolve(".sdd/index.db"))) {
                errWriter.println("error: knowledge base is empty — run sdd index first");
                return 1;
            }
            try (Database db = Database.open(workspace)) {
                Integer repoCount = db.jdbi().withHandle(h ->
                        h.createQuery("SELECT count(*) FROM repo").mapTo(Integer.class).one());
                if (repoCount == 0) {
                    errWriter.println("error: knowledge base is empty — run sdd index first");
                    return 1;
                }
                String mermaid = MermaidGraph.render(db.jdbi());
                if (out != null) {
                    try {
                        Files.writeString(out, mermaid);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    outWriter.println("graph written: " + out);
                } else {
                    outWriter.print(mermaid);
                    outWriter.flush();
                }
                return 0;
            }
        } catch (RuntimeException e) {
            errWriter.println("error: " + e.getMessage());
            return 1;
        }
    }
}
```

In `SddCli.java`, extend the subcommand list: `subcommands = {DoctorCommand.class, IndexCommand.class, PlanCommand.class, GraphCommand.class}`.

- [ ] **Step 3: Run — expect PASS.**
Run: `./gradlew :sdd-cli:test`

- [ ] **Step 4: Full build, then commit**

```bash
./gradlew build
git add sdd-cli/src
git commit -m "feat: sdd graph renders the estate as mermaid"
```

---

## Verification

1. `./gradlew build` — all modules green.
2. Determinism pinned by the double-render assertion; all three edge families + kind styling + collision suffixes + label sanitization pinned by unit tests; CLI stdout/--out/no-KB/root-dispatch pinned by e2e.
3. Real-estate smoke (pre-merge): `sdd graph --workspace trading-estate` — expect 6 styled nodes and 5 `-->|SNAPSHOT|` edges into trading-platform-libs (this estate has no REST/Kafka edges in the KB — correct extraction, see the 3B smoke notes); run twice and diff for byte-identity.

## Self-Review (completed at write time)

1. **Spec coverage (amendment):** repo nodes styled by kind ✓ (T1 classDefs+switch); Gradle edges labeled with mode ✓; REST edges by confidence, distinct style ✓ (`-.->`); Kafka edges by topic, distinct style ✓ (`==>`); stdout default + `--out` ✓ (T2); deterministic ordering ✓ (ORDER BY everywhere + double-render pin); empty-KB mirrors `sdd plan` ✓ (same message, no-create). Module-level drill-down and affected-subgraph filtering explicitly deferred by the amendment ("decided when the phase is planned" — deferred to a future need; recorded).
2. **Placeholder scan:** none.
3. **Type consistency:** `MermaidGraph.render(Jdbi)` in T1/T2; `GraphCommand` follows ApproveCommand's config-free pattern and IndexCommand's writer/taxonomy conventions; node-id map (`idOf`) built before edge emission so all three edge loops resolve ids consistently.
