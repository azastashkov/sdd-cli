package sdd.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    @Test
    void aCallIsAnnouncedBeforeItRunsAndTimedAfterwards() {
        List<String> traced = new java.util.ArrayList<>();
        Map<String, Path> roots = new LinkedHashMap<>();
        roots.put("payments-api", ws.resolve("payments-api"));
        ExploreTools tools = new ExploreTools(db.jdbi(), new EstateJail(roots), false, traced::add);

        tools.dispatch("search_code", "{\"regex\":\"tier\"}");

        // Two lines, in this order: a slow call has to be visible WHILE it is slow, not once it
        // finishes — that silence is what a hang looks like.
        assertThat(traced).hasSize(2);
        assertThat(traced.get(0)).isEqualTo("search_code tier ...");
        assertThat(traced.get(1)).startsWith("  → ").contains("ms");
    }

    // ---- single-tool mode: one declaration, nine operations ---------------------------

    private ExploreTools single() {
        Map<String, Path> roots = new LinkedHashMap<>();
        roots.put("payments-api", ws.resolve("payments-api"));
        roots.put("ops-tools", ws.resolve("ops-tools"));
        return new ExploreTools(db.jdbi(), new EstateJail(roots), true);
    }

    private void seedRefs() {
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO module(repo_id, gradle_path) VALUES(1, ':')");
            for (String fqcn : new String[] {"com.acme.Publisher", "com.acme.WiringConfig",
                    "com.acme.Listener"}) {
                h.execute("INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path) "
                        + "VALUES(1, ?, 'CLASS', 0, ?)", fqcn, fqcn.replace('.', '/') + ".java");
            }
            h.execute("INSERT INTO type_ref(from_type_id, to_fqcn, ref_kind, ref_count) VALUES("
                    + "(SELECT id FROM java_type WHERE fqcn='com.acme.WiringConfig'),"
                    + "'com.acme.Publisher','IMPORT',2)");
            h.execute("INSERT INTO type_ref(from_type_id, to_fqcn, ref_kind, ref_count) VALUES("
                    + "(SELECT id FROM java_type WHERE fqcn='com.acme.WiringConfig'),"
                    + "'com.acme.Listener','TYPE',1)");
            h.execute("INSERT INTO type_ref(from_type_id, to_fqcn, ref_kind, ref_count) VALUES("
                    + "(SELECT id FROM java_type WHERE fqcn='com.acme.Publisher'),"
                    + "'org.slf4j.Logger','IMPORT',1)");
        });
    }

    @Test
    void whoReferencesNamesTheReferringTypesTheirKindAndTheirCount() {
        seedRefs();

        String out = call("who_references", """
                {"fqcn":"com.acme.Publisher"}""");

        assertThat(out).contains("payments-api: com.acme.WiringConfig")
                .contains("[import x2]")
                .contains("com/acme/WiringConfig.java");
    }

    @Test
    void whoReferencesOutboundSkipsTargetsThatAreNotIndexed() {
        seedRefs();

        // Publisher imports org.slf4j.Logger, which has no java_type row. Reporting it would
        // invite the agent to go looking for a file that is not in the estate.
        assertThat(call("who_references", """
                {"fqcn":"com.acme.Publisher","direction":"out"}""")).doesNotContain("slf4j");
    }

    @Test
    void whoReferencesDistinguishesAnUnknownTypeFromOneWithNoReferences() {
        seedRefs();

        // Two very different facts. "Nothing references it" is an answer; "I have never heard of
        // this name" is a correction the agent has to act on, so they must not read the same.
        assertThat(call("who_references", """
                {"fqcn":"com.acme.Listener","direction":"out"}"""))
                .contains("no outbound references");
        assertThat(call("who_references", """
                {"fqcn":"com.acme.NoSuchType"}"""))
                .contains("no reference edges recorded")
                .contains("kb_resolve");
    }

    @Test
    void whoReferencesCannotGroundACitation() {
        seedRefs();
        call("who_references", """
                {"fqcn":"com.acme.Publisher"}""");

        // It surfaces names and paths but never file CONTENT, so it must not mark the file as
        // seen. record_finding still has to re-read the file itself to quote the line.
        assertThatThrownBy(() -> call("record_finding", """
                {"claim":"the key lives here","citation":"payments-api/src/main/java/com/acme/Publisher.java:3"}"""))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("this run has not read it");
    }

    @Test
    void whoReferencesRejectsADirectionItDoesNotUnderstand() {
        seedRefs();
        assertThatThrownBy(() -> call("who_references", """
                {"fqcn":"com.acme.Publisher","direction":"sideways"}"""))
                .isInstanceOf(MalformedCallException.class)
                .hasMessageContaining("'in' or 'out'");
    }

    @Test
    void aWhoReferencesCallIsRoutedThroughTheSingleToolPath() {
        // The mode has three places to register an operation -- the schema enum, dispatch and the
        // trace switch. Miss one and single_tool silently cannot reach the tool at all.
        sdd.core.llm.ToolCall routed = single().route(new sdd.core.llm.ToolCall(
                "call-9", "sdd",
                "{\"action\":\"who_references\",\"fqcn\":\"com.acme.Publisher\"}"));

        assertThat(routed.name()).isEqualTo("who_references");
        assertThat(routed.argumentsJson()).contains("com.acme.Publisher").doesNotContain("action");
    }

    @Test
    void aGlobWithNoRegexListsMatchingPaths() throws Exception {
        // The gap the probe found. With a mandatory regex the only way to ask "which files match
        // this pattern" was to invent one matching everything -- which does not work, because hits
        // are counted per LINE, so one file's first 20 lines exhaust the repo budget.
        Files.writeString(ws.resolve("payments-api/src/main/java/com/acme/Empty.java"), "");
        Files.createDirectories(ws.resolve("payments-api/src/test/java/com/acme"));
        Files.writeString(ws.resolve("payments-api/src/test/java/com/acme/PublisherTest.java"),
                "package com.acme;\nclass PublisherTest {}\n");

        String out = call("search_code", """
                {"glob":"src/main/**/*.java"}""");

        assertThat(out).contains("payments-api/src/main/java/com/acme/Publisher.java")
                // an EMPTY file is nameable this way and can never be found by a content search
                .contains("payments-api/src/main/java/com/acme/Empty.java")
                .doesNotContain("PublisherTest.java")
                .doesNotContain("README.md");
    }

    @Test
    void aGlobAndARegexTogetherSearchOnlyWithinThoseFiles() {
        // The four wasted turns in the recorded run were an attempt to express exactly this, by
        // putting the filename in the regex -- which can never match, since a filename does not
        // appear on the same line as the code it names.
        assertThat(call("search_code", """
                {"regex":"tier\\\\.lvc\\\\.map","glob":"**/Publisher.java"}"""))
                .contains("Publisher.java").contains("tier.lvc.map");
        assertThat(call("search_code", """
                {"regex":"tier\\\\.lvc\\\\.map","glob":"**/*.md"}"""))
                .contains("no matches in");
    }

    @Test
    void listingFilesDoesNotCountAsHavingReadThem() {
        // A listing surfaces paths, never content, so it must not satisfy the citation gate.
        call("search_code", """
                {"glob":"**/*.java"}""");

        assertThatThrownBy(() -> call("record_finding", """
                {"claim":"the key lives here","citation":"payments-api/src/main/java/com/acme/Publisher.java:3"}"""))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("this run has not read it");
    }

    @Test
    void neitherARegexNorAGlobIsAnError() {
        assertThatThrownBy(() -> call("search_code", "{}"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("give a regex");
    }

    @Test
    void aBadGlobIsReportedAsABadGlob() {
        assertThatThrownBy(() -> call("search_code", """
                {"glob":"src/**/{unclosed"}"""))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("bad path glob");
    }

    private ExploreTools withAsker(java.util.List<String> answers, int[] calls) {
        java.util.Iterator<String> it = answers.iterator();
        sdd.agent.tool.HumanAsk asker = (question, options) -> {
            calls[0]++;
            return it.hasNext() ? it.next() : null;
        };
        Map<String, Path> roots = new LinkedHashMap<>();
        roots.put("payments-api", ws.resolve("payments-api"));
        return new ExploreTools(db.jdbi(), new EstateJail(roots), false, null, asker, 10);
    }

    @Test
    void withNoHumanAttachedTheToolIsRefusedRatherThanCountedAsAStrike() {
        // THE CI assertion. ToolException RESETS the strike counter, so a run with nobody to ask
        // is not pushed toward MALFORMED by trying; and the message points the model at what it
        // should do instead.
        assertThatThrownBy(() -> call("ask_user_question", """
                {"question":"Which tenant?"}"""))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("no human is attached")
                .hasMessageContaining("done(blocked)");
        assertThat(tools.notebook().clarifications()).isEmpty();
    }

    @Test
    void theToolIsAdvertisedOnlyWhenAHumanIsAttached() {
        // The default non-interactive path keeps exactly the declarations it has today, and with
        // them the measured tool-call reliability on gateways that degrade as the count grows.
        assertThat(tools.specs()).extracting(sdd.core.llm.ToolSpec::name)
                .doesNotContain("ask_user_question");

        int[] calls = {0};
        assertThat(withAsker(List.of("yes"), calls).specs())
                .extracting(sdd.core.llm.ToolSpec::name).contains("ask_user_question");
    }

    @Test
    void singleToolModeCarriesItAtZeroDeclarationCost() {
        assertThat(single().specs()).singleElement().satisfies(spec ->
                assertThat(spec.parametersSchemaJson()).contains("ask_user_question"));
    }

    @Test
    void anAnswerSurvivesEvictionByLivingInTheNotebook() {
        // A tool result is evictable — the EXPLORE policy protects record_finding by name, and
        // evictAll stubs everything. An answer that lived only there would be re-asked.
        int[] calls = {0};
        ExploreTools t = withAsker(List.of("the EU tenant"), calls);

        t.dispatch("ask_user_question", "{\"question\":\"Which tenant?\"}");

        assertThat(t.digest()).contains("Which tenant?").contains("the EU tenant")
                .contains("do not ask these again");
    }

    @Test
    void aRepeatedQuestionIsServedFromTheNotebookWithoutTroublingTheHumanAgain() {
        int[] calls = {0};
        ExploreTools t = withAsker(List.of("the EU tenant"), calls);

        String first = t.dispatch("ask_user_question", "{\"question\":\"Which tenant?\"}");
        String second = t.dispatch("ask_user_question", "{\"question\":\"  WHICH   Tenant? \"}");

        assertThat(calls[0]).as("the human is asked once, not twice").isEqualTo(1);
        assertThat(first).startsWith("answered —");
        // Normalized comparison, so a cosmetically reworded repeat is caught too — which is what
        // replaces the wedge protection this tool opts out of.
        assertThat(second).startsWith("already answered —").contains("the EU tenant");
    }

    @Test
    void loopingOnAnAnsweredQuestionIsEventuallyRefusedWithTheAnswer() {
        int[] calls = {0};
        ExploreTools t = withAsker(List.of("the EU tenant"), calls);
        for (int i = 0; i < 3; i++) {
            t.dispatch("ask_user_question", "{\"question\":\"Which tenant?\"}");
        }

        assertThatThrownBy(() -> t.dispatch("ask_user_question",
                "{\"question\":\"Which tenant?\"}"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("already asked this")
                .hasMessageContaining("the EU tenant");
    }

    @Test
    void anUnansweredQuestionLatchesSoNoLaterAskTouchesStdin() {
        int[] calls = {0};
        ExploreTools t = withAsker(List.of(), calls);   // asker always returns null

        assertThatThrownBy(() -> t.dispatch("ask_user_question", "{\"question\":\"A?\"}"))
                .isInstanceOf(ToolException.class).hasMessageContaining("nobody answered");
        assertThat(t.notebook().clarifications()).isEmpty();
    }

    @Test
    void theQuestionCapRefusesAnInterrogation() {
        Map<String, Path> roots = new LinkedHashMap<>();
        roots.put("payments-api", ws.resolve("payments-api"));
        ExploreTools t = new ExploreTools(db.jdbi(), new EstateJail(roots), false, null,
                (q, o) -> "yes", 1);

        t.dispatch("ask_user_question", "{\"question\":\"First?\"}");

        assertThatThrownBy(() -> t.dispatch("ask_user_question", "{\"question\":\"Second?\"}"))
                .isInstanceOf(ToolException.class).hasMessageContaining("question limit (1)");
    }

    @Test
    void askingIsRepeatableAndReportsTheTimeItWaited() {
        int[] calls = {0};
        ExploreTools t = withAsker(List.of("yes"), calls);

        assertThat(t.repeatable("ask_user_question")).isTrue();
        assertThat(t.repeatable("read_file")).isFalse();
        t.dispatch("ask_user_question", "{\"question\":\"A?\"}");
        assertThat(t.blockedOnHuman()).isGreaterThanOrEqualTo(java.time.Duration.ZERO);
    }

    @Test
    void askingWritesNothingToTheEstate() {
        int[] calls = {0};
        ExploreTools t = withAsker(List.of("yes"), calls);
        t.dispatch("ask_user_question", "{\"question\":\"A?\"}");

        assertThat(t.specs()).extracting(sdd.core.llm.ToolSpec::name)
                .doesNotContain("apply_edit", "run_gradle", "run_npm");
        assertThat(t.buildToolName()).isNull();
    }

    @Test
    void singleToolModeAdvertisesExactlyOneDeclaration() {
        assertThat(single().specs()).singleElement().satisfies(spec -> {
            assertThat(spec.name()).isEqualTo("sdd");
            // Every operation must still be reachable, or the mode silently removes capability.
            assertThat(spec.parametersSchemaJson())
                    .contains("list_repos").contains("read_file").contains("search_code")
                    .contains("propose_touchpoint").contains("record_finding").contains("done")
                    .contains("who_references");
        });
    }

    @Test
    void aMultiplexedCallIsRoutedToItsOperationKeepingTheCallId() {
        sdd.core.llm.ToolCall routed = single().route(new sdd.core.llm.ToolCall(
                "call-7", "sdd", "{\"action\":\"read_file\",\"path\":\"ops-tools/README.md\"}"));

        assertThat(routed.name()).isEqualTo("read_file");
        // The id is what pairs the tool_result back; losing it breaks the conversation, not
        // just the call.
        assertThat(routed.id()).isEqualTo("call-7");
        assertThat(routed.argumentsJson()).contains("ops-tools/README.md").doesNotContain("action");
    }

    @Test
    void doneStillReachesTheLoopThroughTheMultiplexer() {
        // AgentLoop intercepts `done` by NAME. If the multiplexer did not translate it, the
        // agent could never finish — it would dispatch done, which is a programming error.
        sdd.core.llm.ToolCall routed = single().route(new sdd.core.llm.ToolCall(
                "c1", "sdd", "{\"action\":\"done\",\"result\":\"success\",\"summary\":\"ok\"}"));

        assertThat(routed.name()).isEqualTo("done");
        assertThat(routed.argumentsJson()).contains("success").contains("ok");
    }

    @Test
    void anOperationCalledDirectlyStillWorksInSingleToolMode() {
        // Failing closed here would turn a cosmetic difference — a model recalling the
        // nine-tool shape from an earlier turn — into a dead turn.
        ExploreTools tools = single();
        sdd.core.llm.ToolCall direct =
                new sdd.core.llm.ToolCall("c2", "list_repos", "{}");

        assertThat(tools.route(direct)).isEqualTo(direct);
        assertThat(tools.dispatch("list_repos", "{}")).contains("payments-api");
    }

    @Test
    void aMultiplexedCallWithNoActionIsMalformedRatherThanSilentlyIgnored() {
        assertThatThrownBy(() -> single().route(new sdd.core.llm.ToolCall("c3", "sdd", "{}")))
                .isInstanceOf(MalformedCallException.class)
                .hasMessageContaining("action");
    }

    @Test
    void bothGatesStillApplyThroughTheMultiplexer() {
        ExploreTools tools = single();
        var call = new sdd.core.llm.ToolCall("c4", "sdd",
                "{\"action\":\"record_finding\",\"claim\":\"x\","
                        + "\"citation\":\"payments-api/src/main/java/com/acme/Publisher.java:3\"}");
        var routed = tools.route(call);

        // The citation gate is not weakened by the wire shape: the file was never read.
        assertThatThrownBy(() -> tools.dispatch(routed.name(), routed.argumentsJson()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("this run has not read it");
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        db.close();
    }

    // Measured on a real run: search_code located a method at line 363 of a file whose import
    // block alone exhausts the 400-line cap, so every read returned the same package declaration.
    // The model re-searched to confirm the line, read again, got imports again, and the wedge
    // detector killed it after it had done exactly the right thing three times.
    @Test
    void readFileReturnsTheWindowAroundALineDeepInABigFile() throws Exception {
        StringBuilder big = new StringBuilder("package com.acme;\n");
        for (int i = 2; i <= 500; i++) {
            big.append("// filler line ").append(i).append('\n');
        }
        big.append("public void toApp(Message message) {}\n");   // line 501
        Files.writeString(ws.resolve("payments-api/Big.java"), big.toString());

        String out = tools.dispatch("read_file",
                "{\"path\":\"payments-api/Big.java\",\"offset\":501,\"limit\":3}");

        assertThat(out).contains("501: public void toApp(Message message) {}");
        // Says which window it returned, so the model can tell a short read from a wrong one.
        assertThat(out).contains("payments-api/Big.java:501-501 of 501 lines");
        assertThat(out).doesNotContain("package com.acme;");
    }

    // A gateway that cannot structure tool calls returns them as markup where every value is text,
    // so offset arrives as "501" there and as 501 elsewhere. Both must work, or read_file works on
    // some endpoints and not others.
    @Test
    void aNumericArgumentIsAcceptedAsAStringOrANumber() throws Exception {
        Files.writeString(ws.resolve("payments-api/Small.java"), "a\nb\nc\nd\n");

        String asString = tools.dispatch("read_file",
                "{\"path\":\"payments-api/Small.java\",\"offset\":\"3\",\"limit\":\"1\"}");
        String asNumber = tools.dispatch("read_file",
                "{\"path\":\"payments-api/Small.java\",\"offset\":3,\"limit\":1}");

        assertThat(asString).isEqualTo(asNumber).contains("3: c");
    }

    @Test
    void anOmittedOffsetReadsFromTheTop() throws Exception {
        Files.writeString(ws.resolve("payments-api/Small.java"), "a\nb\n");

        assertThat(tools.dispatch("read_file", "{\"path\":\"payments-api/Small.java\"}"))
                .contains("1: a").contains("2: b");
    }

    // "There is no line 900" and "here is line 1 instead" are different answers, and returning the
    // second is what sent a real run into a loop.
    @Test
    void anOffsetPastTheEndSaysSoInsteadOfReturningTheTop() throws Exception {
        Files.writeString(ws.resolve("payments-api/Small.java"), "a\nb\n");

        assertThat(tools.dispatch("read_file",
                "{\"path\":\"payments-api/Small.java\",\"offset\":900}"))
                .contains("has 2 lines; there is no line 900")
                .doesNotContain("1: a");
    }

    // The model asked to read a file; the useful answer is the file, not a lecture about the
    // argument it fumbled.
    @Test
    void anUnparseableOffsetFallsBackToTheTopRatherThanFailing() throws Exception {
        Files.writeString(ws.resolve("payments-api/Small.java"), "a\nb\n");

        assertThat(tools.dispatch("read_file",
                "{\"path\":\"payments-api/Small.java\",\"offset\":\"line 363\"}"))
                .contains("1: a");
    }

    // The second live wedge, verbatim: startLine/endLine were ignored, the read returned line 1,
    // and the model re-searched and re-read the same imports until the wedge detector stopped it.
    @Test
    void startLineAndEndLineAreUnderstoodAsAWindow() throws Exception {
        StringBuilder big = new StringBuilder();
        for (int i = 1; i <= 557; i++) {
            big.append(i == 400 ? "public void toApp(Message m) {}\n" : "// line " + i + "\n");
        }
        Files.writeString(ws.resolve("payments-api/Big.java"), big.toString());

        String out = tools.dispatch("read_file", """
                {"path":"payments-api/Big.java","repo":"payments-api",\
                "startLine":"339","endLine":"557"}""");

        assertThat(out).contains("payments-api/Big.java:339-557 of 557 lines");
        assertThat(out).contains("400: public void toApp(Message m) {}");
        assertThat(out).doesNotContain("1: // line 1\n");
    }

    // An end LINE is not a count: 339 plus 557 more would overshoot the file and look like it
    // worked.
    @Test
    void anEndLineIsNotTreatedAsACount() throws Exception {
        Files.writeString(ws.resolve("payments-api/Ten.java"),
                "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n");

        assertThat(tools.dispatch("read_file",
                "{\"path\":\"payments-api/Ten.java\",\"startLine\":3,\"endLine\":5}"))
                .contains(":3-5 of 10 lines").contains("5: 5").doesNotContain("6: 6");
    }

    // A wrong window is not recoverable, because nothing tells the model it got one. A malformed
    // call is: on a live run the model was told search_code takes regex rather than query and
    // corrected itself on the next turn.
    @Test
    void anUnknownArgumentIsRefusedByNameRatherThanSilentlyReadingLineOne() throws Exception {
        Files.writeString(ws.resolve("payments-api/Ten.java"), "1\n2\n3\n");

        assertThatThrownBy(() -> tools.dispatch("read_file",
                "{\"path\":\"payments-api/Ten.java\",\"beginningAt\":\"3\"}"))
                .isInstanceOf(MalformedCallException.class)
                .hasMessageContaining("beginningAt")
                .hasMessageContaining("offset")
                .hasMessageContaining("startLine");
    }

    // repo is accepted and ignored: search_code takes one, a model reasonably reuses it, and the
    // repo is already the path's first segment. Costing a turn to teach that would be pedantry.
    @Test
    void aRedundantRepoArgumentIsAccepted() throws Exception {
        Files.writeString(ws.resolve("payments-api/Ten.java"), "1\n2\n3\n");

        assertThat(tools.dispatch("read_file",
                "{\"path\":\"payments-api/Ten.java\",\"repo\":\"payments-api\"}"))
                .contains("1: 1");
    }
}
