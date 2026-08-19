package sdd.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two gates that make an explorer's output checkable: a touchpoint the KB cannot resolve is
 * refused, and a citation is verified against the file rather than taken from the model.
 */
class ExploreToolsTest {
    @TempDir Path ws;
    private Database db;
    private ExploreTools tools;

    @BeforeEach
    void setUp() throws Exception {
        Path repo = Files.createDirectories(ws.resolve("payments-api/src/main/java/com/acme"));
        Files.writeString(repo.resolve("Publisher.java"), """
                package com.acme;
                public class Publisher {
                    static final String KEY = "tier.lvc.map";
                }
                """);
        Files.createDirectories(ws.resolve("ops-tools"));
        Files.writeString(ws.resolve("ops-tools/README.md"), "nothing to see\n");

        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path) VALUES(?, ?)", "payments-api",
                    ws.resolve("payments-api").toString());
            h.execute("INSERT INTO repo(name, path) VALUES(?, ?)", "ops-tools",
                    ws.resolve("ops-tools").toString());
        });

        Map<String, Path> roots = new LinkedHashMap<>();
        roots.put("payments-api", ws.resolve("payments-api"));
        roots.put("ops-tools", ws.resolve("ops-tools"));
        tools = new ExploreTools(db.jdbi(), new EstateJail(roots));
    }

    private String call(String name, String args) {
        return tools.dispatch(name, args);
    }

    @Test
    void aCitationForAFileThisRunNeverOpenedIsRefused() {
        assertThatThrownBy(() -> call("record_finding",
                """
                {"claim":"the key lives here","citation":"payments-api/src/main/java/com/acme/Publisher.java:3"}"""))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("this run has not read it");

        assertThat(tools.notebook().findings()).isEmpty();
    }

    @Test
    void theQuotedLineComesFromTheFileNotFromTheModel() {
        call("read_file", "{\"path\":\"payments-api/src/main/java/com/acme/Publisher.java\"}");

        String result = call("record_finding", """
                {"claim":"tier.lvc.map is written by Publisher",\
                "citation":"payments-api/src/main/java/com/acme/Publisher.java:3"}""");

        assertThat(result).contains("static final String KEY = \"tier.lvc.map\";");
        assertThat(tools.notebook().findings()).singleElement().satisfies(f -> {
            assertThat(f.citedLine()).isEqualTo("static final String KEY = \"tier.lvc.map\";");
            assertThat(f.citation()).isEqualTo("payments-api/src/main/java/com/acme/Publisher.java:3");
        });
    }

    @Test
    void aSearchHitCountsAsHavingReadTheFile() {
        // search_code is how an explorer finds anything it did not already know to open, so
        // requiring a redundant read_file first would make the gate a tax rather than a check.
        call("search_code", "{\"regex\":\"tier\\\\.lvc\\\\.map\"}");

        assertThat(call("record_finding", """
                {"claim":"the key is here","citation":"payments-api/src/main/java/com/acme/Publisher.java:3"}"""))
                .contains("recorded");
    }

    @Test
    void aLineNumberPastTheEndOfTheFileIsRefused() {
        call("read_file", "{\"path\":\"ops-tools/README.md\"}");

        assertThatThrownBy(() -> call("record_finding",
                "{\"claim\":\"x\",\"citation\":\"ops-tools/README.md:99\"}"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("has 1 lines");
    }

    @Test
    void aTouchpointTheKbCannotResolveIsRefusedRatherThanProposed() {
        // Left in, it would become a blocking problem at plan time — the explorer's guess turned
        // into the human's obstacle.
        assertThatThrownBy(() -> call("propose_touchpoint",
                "{\"kind\":\"repo\",\"value\":\"no-such-repo\"}"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("no such repo");

        assertThat(tools.notebook().proposals()).isEmpty();
    }

    @Test
    void aResolvableTouchpointIsProposedOnceAndNamesWhereItResolves() {
        assertThat(call("propose_touchpoint", "{\"kind\":\"repo\",\"value\":\"payments-api\"}"))
                .contains("resolves in payments-api");
        assertThat(call("propose_touchpoint", "{\"kind\":\"repo\",\"value\":\"payments-api\"}"))
                .startsWith("already proposed");

        assertThat(tools.notebook().proposals()).singleElement()
                .satisfies(p -> assertThat(p.value()).isEqualTo("payments-api"));
    }

    @Test
    void anEmptySymbolSearchSaysWhatItDoesNotKnowAbout() {
        // fts_symbol holds type and member NAMES only, so "no match" here is not evidence of
        // absence — a model told only "no results" would conclude the opposite.
        assertThat(call("search_symbols", "{\"query\":\"tier.lvc.map\"}"))
                .contains("type and member names only")
                .contains("search_code");
    }

    @Test
    void theDigestCarriesTheFindingsThroughEviction() {
        assertThat(tools.digest()).isNull();   // nothing to pin yet
        call("read_file", "{\"path\":\"payments-api/src/main/java/com/acme/Publisher.java\"}");
        call("record_finding", """
                {"claim":"tier.lvc.map is written by Publisher",\
                "citation":"payments-api/src/main/java/com/acme/Publisher.java:3"}""");
        call("propose_touchpoint", "{\"kind\":\"repo\",\"value\":\"payments-api\"}");

        assertThat(tools.digest())
                .contains("tier.lvc.map is written by Publisher")
                .contains("payments-api/src/main/java/com/acme/Publisher.java:3")
                .contains("repo: payments-api");
    }

    @Test
    void thereIsNoWayToWriteAnything() {
        // Read-only is structural: no apply_edit, and no build tool whose task could write for it.
        assertThat(tools.specs()).extracting(sdd.core.llm.ToolSpec::name)
                .doesNotContain("apply_edit", "run_gradle", "run_npm");
        assertThat(tools.buildToolName()).isNull();
        assertThatThrownBy(() -> call("apply_edit", "{}"))
                .isInstanceOf(MalformedCallException.class);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        db.close();
    }
}
