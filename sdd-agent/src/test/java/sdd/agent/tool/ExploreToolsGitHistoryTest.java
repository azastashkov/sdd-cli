package sdd.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.llm.ToolCall;
import sdd.core.llm.ToolSpec;
import sdd.core.testing.FixtureRepo;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code git_history} — the read the working tree cannot answer.
 *
 * <p>The load-bearing assertions here are the two that are easy to get wrong: it is not advertised
 * to a survey that was not asked to compare revisions, and its output cannot ground a citation.
 */
class ExploreToolsGitHistoryTest {
    @TempDir Path ws;
    private Database db;
    private FixtureRepo fixture;
    private String base;
    private String head;
    private Map<String, Path> roots;

    @BeforeEach
    void setUp() {
        fixture = FixtureRepo.in(ws, "payments-api")
                .file("src/Publisher.java", "package com.acme;\nclass Publisher {}\n")
                .commit("initial import", Instant.parse("2026-01-01T00:00:00Z"));
        base = fixture.headSha();
        fixture.branch("release-7").checkout("main");
        fixture.file("src/Publisher.java",
                        "package com.acme;\nclass Publisher { String key = \"tier.lvc\"; }\n")
                .file("src/Consumer.java", "package com.acme;\nclass Consumer {}\n")
                .commit("wire the tier key through", Instant.parse("2026-01-05T00:00:00Z"));
        head = fixture.headSha();

        db = Database.open(ws);
        db.jdbi().useHandle(h -> h.execute("INSERT INTO repo(name, path) VALUES(?, ?)",
                "payments-api", ws.resolve("payments-api").toString()));

        roots = new LinkedHashMap<>();
        roots.put("payments-api", ws.resolve("payments-api"));
    }

    /** With a range under investigation — what --since produces. */
    private ExploreTools investigating() {
        return investigating(false, null);
    }

    private ExploreTools investigating(boolean singleTool, java.util.function.Consumer<String> trace) {
        return new ExploreTools(db.jdbi(), new EstateJail(roots), singleTool, trace, null,
                ExploreTools.MAX_QUESTIONS, Map.of("payments-api", base + ".." + head));
    }

    /** No range — an ordinary survey. */
    private ExploreTools plain() {
        return new ExploreTools(db.jdbi(), new EstateJail(roots));
    }

    private String call(String args) {
        return investigating().dispatch("git_history", args);
    }

    // ---------------------------------------------------------------- advertising

    @Test
    void itIsAdvertisedOnlyToASurveyInvestigatingARange() {
        // A run that was not asked to compare two points in history keeps exactly today's
        // declaration count, and with it the measured reliability on a degrading gateway.
        assertThat(plain().specs()).extracting(ToolSpec::name).doesNotContain("git_history");
        assertThat(investigating().specs()).extracting(ToolSpec::name).contains("git_history");
    }

    /** The flag keeps bytes off the wire; it is not a capability gate. */
    @Test
    void aCallThatArrivesAnywayIsServedRatherThanRefused() {
        assertThat(plain().dispatch("git_history", "{\"repo\":\"payments-api\",\"op\":\"log\"}"))
                .contains("wire the tier key through").contains("initial import");
    }

    @Test
    void singleToolModeCarriesItAtZeroDeclarationCost() {
        assertThat(investigating(true, null).specs()).singleElement().satisfies(spec -> {
            assertThat(spec.parametersSchemaJson()).contains("git_history").contains("\"op\"")
                    .contains("\"rev\"");
            assertThat(spec.description()).contains("git_history(repo,op");
        });
    }

    @Test
    void aGitHistoryCallIsRoutedThroughTheSingleToolPath() {
        // The mode has three places to register an operation -- the schema enum, dispatch and the
        // trace switch. Miss one and single_tool silently cannot reach the tool at all.
        ToolCall routed = investigating(true, null).route(new ToolCall("call-7", "sdd",
                "{\"action\":\"git_history\",\"repo\":\"payments-api\",\"op\":\"refs\"}"));

        assertThat(routed.id()).isEqualTo("call-7");
        assertThat(routed.name()).isEqualTo("git_history");
        assertThat(routed.argumentsJson()).contains("payments-api").doesNotContain("action");
        assertThat(investigating(true, null).dispatch(routed.name(), routed.argumentsJson()))
                .contains("release-7");
    }

    @Test
    void theTraceSaysWhatTheCallWasAbout() {
        List<String> lines = new ArrayList<>();
        investigating(false, lines::add)
                .dispatch("git_history", "{\"repo\":\"payments-api\",\"op\":\"log\"}");
        assertThat(lines).first().asString()
                .isEqualTo("git_history payments-api log ...");
    }

    // ---------------------------------------------------------------- the operations

    @Test
    void logListsCommitsNewestFirstAndEchoesTheResolvedShas() {
        String out = call("{\"repo\":\"payments-api\",\"op\":\"log\"}");

        assertThat(out).contains("payments-api log " + base + ".." + head)
                .contains("(resolved " + base.substring(0, 12) + ".." + head.substring(0, 12) + ")")
                .contains(head.substring(0, 12))
                .contains("2026-01-05")
                .contains("wire the tier key through");
        // base itself is the far side of the range, so it is NOT one of the commits it adds.
        assertThat(out).doesNotContain("initial import");
    }

    @Test
    void revDefaultsToTheRangeUnderInvestigation() {
        // The point of --since seeding: the model does not have to discover the coordinates.
        assertThat(call("{\"repo\":\"payments-api\",\"op\":\"log\"}"))
                .isEqualTo(call("{\"repo\":\"payments-api\",\"op\":\"log\",\"rev\":\""
                        + base + ".." + head + "\"}"));
    }

    @Test
    void logTakesAnExplicitRevisionAndAPathFilter() {
        assertThat(call("{\"repo\":\"payments-api\",\"op\":\"log\",\"rev\":\"HEAD\"}"))
                .contains("initial import").contains("wire the tier key through");
        assertThat(call("{\"repo\":\"payments-api\",\"op\":\"log\",\"rev\":\"HEAD\","
                + "\"path\":\"src/Consumer.java\"}"))
                .contains("wire the tier key through").doesNotContain("initial import");
    }

    @Test
    void logHonoursTheLimitAndSaysWhenItTruncated() {
        String out = call("{\"repo\":\"payments-api\",\"op\":\"log\",\"rev\":\"HEAD\",\"limit\":1}");
        assertThat(out).contains("wire the tier key through").doesNotContain("initial import")
                .contains("pass limit= for more");
    }

    /** The cap that matters: a whole-branch patch is what takes the context window out. */
    @Test
    void diffDefaultsToAStatRatherThanThePatch() {
        String out = call("{\"repo\":\"payments-api\",\"op\":\"diff\"}");

        assertThat(out).contains("payments-api diff " + base.substring(0, 12) + ".."
                        + head.substring(0, 12))
                .contains("M src/Publisher.java")
                .contains("A src/Consumer.java")
                .contains("(2 files, +")
                .contains("pass path= for the patch");
        assertThat(out).doesNotContain("+class Publisher").doesNotContain("@@");
    }

    @Test
    void diffWithAPathReturnsTheActualPatch() {
        String out = call("{\"repo\":\"payments-api\",\"op\":\"diff\",\"path\":\"src/Publisher.java\"}");
        assertThat(out).contains("diff --git").contains("tier.lvc");
    }

    @Test
    void aPathMayCarryTheEstateWideRepoPrefixTheOtherToolsUse() {
        assertThat(call("{\"repo\":\"payments-api\",\"op\":\"diff\","
                + "\"path\":\"payments-api/src/Publisher.java\"}")).contains("tier.lvc");
    }

    @Test
    void identicalTreesSaySoRatherThanReturningNothing() {
        assertThat(call("{\"repo\":\"payments-api\",\"op\":\"diff\",\"rev\":\"HEAD..HEAD\"}"))
                .contains("the two trees are identical");
    }

    @Test
    void showDescribesOneCommitAsAStat() {
        String out = call("{\"repo\":\"payments-api\",\"op\":\"show\",\"rev\":\"" + head + "\"}");
        assertThat(out).contains("payments-api show " + head)
                .contains("2026-01-05").contains("wire the tier key through")
                .contains("A src/Consumer.java")
                .contains("pass op=diff with path=");
    }

    @Test
    void showOfARootCommitWorksAndSaysWhyItLooksLikeAnAddOfEverything() {
        assertThat(call("{\"repo\":\"payments-api\",\"op\":\"show\",\"rev\":\"" + base + "\"}"))
                .contains("root commit").contains("A src/Publisher.java");
    }

    @Test
    void aRangeGivenToAOneRevisionOperationUsesItsFarEnd() {
        assertThat(call("{\"repo\":\"payments-api\",\"op\":\"show\"}")).contains("show " + head);
    }

    @Test
    void blameAttributesEachLineToTheCommitThatWroteIt() {
        String out = call("{\"repo\":\"payments-api\",\"op\":\"blame\","
                + "\"path\":\"src/Publisher.java\",\"rev\":\"HEAD\"}");
        assertThat(out).contains("payments-api blame src/Publisher.java@" + head.substring(0, 12))
                .contains(base.substring(0, 12) + " 2026-01-01")   // line 1 is untouched since base
                .contains(head.substring(0, 12) + " 2026-01-05")
                .contains("2: class Publisher");
    }

    @Test
    void blameWithoutAPathIsRefusedRatherThanGuessed() {
        assertThatThrownBy(() -> call("{\"repo\":\"payments-api\",\"op\":\"blame\"}"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("needs path=");
    }

    @Test
    void aBlameWindowPastTheEndSaysSoRatherThanReturningTheTop() {
        // The same failure read_file's offset handling exists to prevent.
        assertThat(call("{\"repo\":\"payments-api\",\"op\":\"blame\","
                + "\"path\":\"src/Publisher.java\",\"offset\":900}"))
                .contains("has 2 lines at that revision").contains("line 900 does not exist");
    }

    @Test
    void refsListsBranchesSoTheOtherRevisionCanBeNamedAtAll() {
        assertThat(call("{\"repo\":\"payments-api\",\"op\":\"refs\"}"))
                .contains("main").contains("release-7");
    }

    // ---------------------------------------------------------------- failure classes

    @Test
    void anUnknownOperationIsMalformedAndNamesTheRealOnes() {
        assertThatThrownBy(() -> call("{\"repo\":\"payments-api\",\"op\":\"bisect\"}"))
                .isInstanceOf(MalformedCallException.class)
                .hasMessageContaining("log, show, diff, blame, refs");
    }

    /**
     * ToolException, not MalformedCallException — the distinction is load-bearing: it RESETS the
     * strike counter, so a wrong branch name costs a turn and buys a retry.
     */
    @Test
    void anUnknownRevisionIsRecoverableRatherThanAStrike() {
        assertThatThrownBy(() -> call("{\"repo\":\"payments-api\",\"op\":\"log\",\"rev\":\"v7\"}"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("no such revision").hasMessageContaining("v7");
    }

    @Test
    void anUnknownRepoPointsAtListRepos() {
        assertThatThrownBy(() -> call("{\"repo\":\"nope\",\"op\":\"refs\"}"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("unknown repo").hasMessageContaining("list_repos");
    }

    @Test
    void aRepoThatIsNotAGitCheckoutFailsAsAToolErrorNotACrash() {
        Map<String, Path> withPlainDir = new LinkedHashMap<>(roots);
        withPlainDir.put("ops-tools", ws.resolve("ops-tools"));
        ExploreTools t = new ExploreTools(db.jdbi(), new EstateJail(withPlainDir), false, null,
                null, ExploreTools.MAX_QUESTIONS, Map.of("payments-api", base + ".." + head));
        assertThatThrownBy(() -> t.dispatch("git_history", "{\"repo\":\"ops-tools\",\"op\":\"refs\"}"))
                .isInstanceOf(ToolException.class);
    }

    // ---------------------------------------------------------------- the citation gate

    /**
     * The gate must not weaken. {@code record_finding} verifies by re-reading the WORKING TREE, so
     * a line number lifted from a commit does not mean what it says. History therefore never joins
     * the provenance set, exactly as who_references and search_symbols do not.
     */
    @Test
    void historyDoesNotGroundACitationTheWayReadingTheFileDoes() {
        ExploreTools t = investigating();
        t.dispatch("git_history", "{\"repo\":\"payments-api\",\"op\":\"diff\","
                + "\"path\":\"src/Publisher.java\"}");

        assertThatThrownBy(() -> t.dispatch("record_finding",
                "{\"claim\":\"the tier key landed in " + head.substring(0, 12) + "\","
                        + "\"citation\":\"payments-api/src/Publisher.java:2\"}"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("has not read it")
                .hasMessageContaining("read_file");

        // ...and the documented route out of it still works: read the CURRENT file, cite that.
        t.dispatch("read_file", "{\"path\":\"payments-api/src/Publisher.java\"}");
        assertThat(t.dispatch("record_finding",
                "{\"claim\":\"the tier key landed in " + head.substring(0, 12) + "\","
                        + "\"citation\":\"payments-api/src/Publisher.java:2\"}"))
                .contains("recorded").contains("tier.lvc");
    }

    // ---------------------------------------------------------------- the output budget

    @Test
    void anOversizedPatchIsCappedWithAMarkerThatNamesTheNarrowingArgument() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            big.append("// generated line ").append(i).append('\n');
        }
        fixture.file("src/Big.java", big.toString())
                .commit("add a big file", Instant.parse("2026-01-06T00:00:00Z"));

        String out = investigating().dispatch("git_history",
                "{\"repo\":\"payments-api\",\"op\":\"diff\",\"rev\":\"" + head + "..HEAD\","
                        + "\"path\":\"src/Big.java\"}");

        assertThat(out).hasSizeLessThan(34000)
                .contains("truncated").contains("pass path= to narrow");
    }
}
