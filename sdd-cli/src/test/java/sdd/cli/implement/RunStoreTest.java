package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.cli.review.Decision;
import sdd.cli.review.DecisionRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunStoreTest {
    @TempDir Path ws;
    private final RunStore store = new RunStore(InstantSource.fixed(Instant.parse("2026-08-12T00:00:00Z")));

    @Test
    void createsRunDirWithImmutablePlanAndLock() throws Exception {
        Path runDir = store.create(ws, "SPEC-101-v1", "{\"plan\":true}");

        assertThat(runDir).isEqualTo(ws.resolve(".sdd/runs/SPEC-101-v1"));
        assertThat(Files.readString(runDir.resolve("plan.json"))).isEqualTo("{\"plan\":true}");
        assertThat(runDir.resolve("lock")).exists();
        assertThatThrownBy(() -> store.create(ws, "SPEC-101-v1", "x"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("in progress");
    }

    @Test
    void writesAtomicStateAndAppendsEvents() throws Exception {
        Path runDir = store.create(ws, "R", "{}");
        RunState state = new RunState("R", List.of("lib", "svc"));
        state.set("lib", RepoState.SUCCEEDED, "sdd/R/lib", "abc123", "done");
        store.writeState(runDir, state);
        store.appendEvent(runDir, "lib", RepoState.IN_PROGRESS, RepoState.SUCCEEDED, "done");

        String stateJson = Files.readString(runDir.resolve("state.json"));
        assertThat(stateJson).contains("\"runId\" : \"R\"").contains("SUCCEEDED").contains("abc123");
        String events = Files.readString(runDir.resolve("events.jsonl"));
        assertThat(events).contains("\"repo\":\"lib\"").contains("\"to\":\"SUCCEEDED\"")
                .contains("2026-08-12T00:00:00Z").endsWith("\n");
        assertThat(Files.exists(runDir.resolve("state.json.tmp"))).isFalse();
    }

    @Test
    void runStateSeedsPendingAndTracksTransitions() {
        RunState state = new RunState("R", List.of("lib", "svc"));
        assertThat(state.stateOf("lib")).isEqualTo(RepoState.PENDING);
        state.set("lib", RepoState.FAILED, "b", null, "boom");
        assertThat(state.stateOf("lib")).isEqualTo(RepoState.FAILED);
        assertThat(state.repos()).extracting(RepoRun::repo).containsExactly("lib", "svc");
    }

    @Test
    void writesPerRepoAgentEvents() throws Exception {
        Path runDir = store.create(ws, "R", "{}");
        store.writeAgentEvents(runDir, "lib", List.of("no tool call", "wedged"));
        assertThat(Files.readString(runDir.resolve("lib/agent-events.jsonl")))
                .contains("no tool call").contains("wedged");
        store.writeAgentEvents(runDir, "grp/lib", List.of("x"));
        assertThat(Files.exists(runDir.resolve("grp-lib/agent-events.jsonl"))).isTrue();
    }

    @Test
    void writesPerRepoTranscript() throws Exception {
        Path runDir = store.create(ws, "R", "{}");
        store.writeTranscript(runDir, "lib", List.of("{\"turn\":1}", "{\"turn\":2}"));
        assertThat(Files.readString(runDir.resolve("lib/transcript.jsonl")))
                .contains("{\"turn\":1}").contains("{\"turn\":2}");
        store.writeTranscript(runDir, "grp/lib", List.of("{\"turn\":1}"));
        assertThat(Files.exists(runDir.resolve("grp-lib/transcript.jsonl"))).isTrue();
    }

    @Test
    void writesPerRepoEdits() throws Exception {
        Path runDir = store.create(ws, "R", "{}");
        store.writeEdits(runDir, "lib", List.of("{\"path\":\"A.java\",\"action\":\"edit\"}"));
        assertThat(Files.readString(runDir.resolve("lib/edits.jsonl")))
                .contains("\"path\":\"A.java\"").contains("\"action\":\"edit\"");
        store.writeEdits(runDir, "grp/lib", List.of("{\"path\":\"x\"}"));
        assertThat(Files.exists(runDir.resolve("grp-lib/edits.jsonl"))).isTrue();
    }

    @Test
    void readStateRoundTripsThePauseFields() {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "spec body");
        RunState state = new RunState("S-v1", List.of("lib"));
        state.set("lib", RepoState.PAUSED_ENDPOINT, "sdd/S-v1/lib", null, "outage");
        state.pause("model endpoint unavailable: x");
        state.addTokens(42L);
        store.writeState(runDir, state);

        RunState read = store.readState(runDir);

        assertThat(read.runId()).isEqualTo("S-v1");
        assertThat(read.stateOf("lib")).isEqualTo(RepoState.PAUSED_ENDPOINT);
        assertThat(read.pausedReason()).isEqualTo("model endpoint unavailable: x");
        assertThat(read.tokensSpent()).isEqualTo(42L);
        assertThat(ws.resolve(".sdd/runs/S-v1/spec.md")).hasContent("spec body");
    }

    @Test
    void readStateRoundTripsAFailureCode() {
        Path runDir = store.create(ws, "R", "{}");
        RunState state = new RunState("R", List.of("lib"));
        state.set("lib", RepoState.FAILED, "b", null, "boom", "VERIFY_FAILED");
        store.writeState(runDir, state);

        RunState read = store.readState(runDir);

        assertThat(read.repos().get(0).failureCode()).isEqualTo("VERIFY_FAILED");
    }

    @Test
    void readStateToleratesAPreExistingStateJsonWithNoFailureCodeKey() throws Exception {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}");
        // A literal pre-5C-2 state.json — no "failure_code" key at all. NOT round-tripped through
        // today's writer: that would only prove today's code reads its own output, not that a real
        // frozen estate run (SPEC-101-v1/v2) from before this key existed still loads.
        Files.writeString(runDir.resolve("state.json"), """
                {
                  "runId" : "S-v1",
                  "pausedReason" : null,
                  "tokensSpent" : 0,
                  "repos" : [ {
                    "repo" : "lib",
                    "state" : "SUCCEEDED",
                    "branch" : "sdd/S-v1/lib",
                    "checkpointSha" : "abc1234",
                    "detail" : "done"
                  } ]
                }
                """);

        RunState read = store.readState(runDir);

        RepoRun lib = read.repos().get(0);
        assertThat(lib.state()).isEqualTo(RepoState.SUCCEEDED);
        assertThat(lib.checkpointSha()).isEqualTo("abc1234");
        assertThat(lib.failureCode()).isNull();
    }

    @Test
    void appendRunEventWritesARunScopedLine() throws Exception {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");

        store.appendRunEvent(runDir, "run token budget exhausted (42 tokens)");

        assertThat(Files.readString(runDir.resolve("events.jsonl")))
                .contains("\"run\":\"pause\"")
                .contains("run token budget exhausted");
    }

    @Test
    void aStaleLockFromADeadProcessIsReclaimed() throws Exception {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = Files.createDirectories(ws.resolve(".sdd/runs/S-v1"));
        Process dead = new ProcessBuilder("true").start();
        dead.waitFor();
        Files.writeString(runDir.resolve("lock"), Long.toString(dead.pid()));

        store.acquireLock(runDir);   // must NOT throw: owner is dead

        assertThat(Files.readString(runDir.resolve("lock")))
                .isEqualTo(Long.toString(ProcessHandle.current().pid()));
    }

    @Test
    void aLiveLockStillRefuses() throws Exception {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = Files.createDirectories(ws.resolve(".sdd/runs/S-v2"));
        Files.writeString(runDir.resolve("lock"), Long.toString(ProcessHandle.current().pid()));

        assertThatThrownBy(() -> store.acquireLock(runDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already in progress");
    }

    @Test
    void propagationSnapshotRoundTrips() {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v3", "{}", "");
        Map<String, RepoPropagation> map = Map.of(
                "svc", new RepoPropagation(
                        List.of(new RepoPropagation.BumpEdit("com.acme", "lib", "1.2.3", "1.3.0")), null),
                "lib", new RepoPropagation(List.of(),
                        new RepoPropagation.PublishSpec("1.3.0", runDir.resolve("m2"))));

        store.writePropagation(runDir, map);
        Map<String, RepoPropagation> read = store.readPropagation(runDir);

        assertThat(read).isEqualTo(map);
        assertThat(store.readPropagation(ws.resolve("nowhere"))).isNull();
    }

    @Test
    void contractFilesRoundTripUnderTheContractsDir() {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");

        store.writeContract(runDir, "c1/api", "actual body");

        assertThat(runDir.resolve("contracts/c1-api.md")).exists();
        assertThat(store.readContract(runDir, "c1/api")).isEqualTo("actual body");
        assertThat(store.readContract(runDir, "ghost")).isNull();
    }

    @Test
    void reviewArtifactsLandUnderTheReviewDir() {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");

        store.writeReview(runDir, "report.md", "# Report\n");
        store.writeReview(runDir, "grp/lib.diff", "diff --git\n");

        assertThat(store.reviewDir(runDir)).isEqualTo(runDir.resolve("review"));
        assertThat(runDir.resolve("review/report.md")).hasContent("# Report\n");
        assertThat(runDir.resolve("review/grp-lib.diff")).exists();   // sanitized
    }

    @Test
    void decisionsRoundTripUnderTheReviewDir() {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");

        assertThat(store.readDecisions(runDir)).isEmpty();
        store.writeDecisions(runDir, Map.of(
                "lib", new DecisionRecord(Decision.APPROVED, ""),
                "svc", new DecisionRecord(Decision.REJECTED, "flaky test")));

        assertThat(runDir.resolve("review/decisions.json")).exists();
        assertThat(store.readDecisions(runDir).get("svc").reason()).isEqualTo("flaky test");
        assertThat(store.readDecisions(runDir).get("lib").decision()).isEqualTo(Decision.APPROVED);
    }

    @Test
    void anUnknownDecisionTokenDegradesToPendingRatherThanCrashing() throws Exception {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        Files.createDirectories(store.reviewDir(runDir));
        Files.writeString(store.reviewDir(runDir).resolve("decisions.json"),
                "{\"lib\":{\"decision\":\"BLESSED\",\"reason\":\"\"}}");

        assertThat(store.readDecisions(runDir).get("lib").decision()).isEqualTo(Decision.PENDING);
    }

    /**
     * The temp file each writer publishes through must be unique to that writer. With one shared
     * fixed name ({@code decisions.json.tmp} / {@code state.json.tmp}) two concurrent writers
     * clobber each other's staging file: A can publish B's half-written bytes, and B's own
     * {@code Files.move} then fails {@code NoSuchFileException} — surfacing as exit 4 on a
     * decision a human already gave. The atomic rename never protected against that; it only ever
     * guaranteed a reader sees no half-written file, which is what the second assertion pins.
     *
     * <p>This does NOT make concurrent deciding safe — {@code DecisionCommand} still does a
     * read-modify-write of the whole map across two commands' worth of code, so two humans
     * deciding the same run concurrently can still lose a verdict. It removes the corruption, not
     * the lost update.
     */
    private static void hammer(int writers, int rounds, java.util.function.IntConsumer round)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int w = 0; w < writers; w++) {
                int id = w;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < rounds; i++) {
                        round.accept(id);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();   // rethrows whatever any writer threw
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * {@code writeDecisions(Path, Map)} is documented for callers with nothing to conflict
     * against — it now throws rather than silently drops a write once something else has changed
     * the file underneath (see {@link RunStore#writeDecisions(Path, Map)}'s javadoc). Hammering it
     * from six REAL concurrent writers is exactly that scenario, deliberately, on every round: this
     * test is not exercising the single-writer contract, it is exercising {@code publishAtomically}
     * underneath it (unique temp names, no torn reads, no leftover staging files) under real
     * contention, so a conflict throw here is expected and is caught rather than treated as a
     * regression. What this test does NOT prove: that every round's write survived, or that the
     * single-key map asserted after a SUCCESSFUL round is that thread's OWN payload rather than a
     * sibling's that landed in between — {@code writeDecisions(Path, Map, String)} plus a caller-
     * owned retry loop (as {@code DecisionCommand.applyWithRetry} does) is what a genuinely
     * concurrent writer must use to avoid losing its own update; that is covered separately by
     * {@code RunStoreTest#aWriteWhoseFingerprintIsStaleIsRefused} and
     * {@code ReviewDecisionsCommandTest#aDecisionWrittenUnderneathIsNotLostWhenThisCommandRetries}.
     */
    @Test
    void concurrentDecisionWritersNeitherCorruptTheFileNorFailOnASharedTempName() throws Exception {
        Path runDir = store.create(ws, "S-v1", "{}", "");

        hammer(6, 60, id -> {
            try {
                store.writeDecisions(runDir, Map.of("repo" + id,
                        new DecisionRecord(Decision.APPROVED, "reason " + id)));
            } catch (IllegalStateException conflict) {
                return;   // another writer won this round — expected under real six-way contention
            }
            // A reader must never see a half-written file: readDecisions parses the whole
            // document, so a torn read fails here rather than degrading silently.
            assertThat(store.readDecisions(runDir)).hasSize(1);
        });

        assertThat(store.readDecisions(runDir)).hasSize(1);
        assertThat(Files.list(store.reviewDir(runDir)).map(p -> p.getFileName().toString()).toList())
                .containsExactly("decisions.json");   // every staging file cleaned up
    }

    @Test
    void concurrentStateWritersNeitherCorruptTheFileNorFailOnASharedTempName() throws Exception {
        Path runDir = store.create(ws, "S-v1", "{}", "");

        hammer(6, 60, id -> {
            RunState state = new RunState("S-v1", List.of("lib"));
            state.set("lib", RepoState.SUCCEEDED, "sdd/S-v1/lib", "sha" + id, "done");
            store.writeState(runDir, state);
            assertThat(store.readState(runDir).stateOf("lib")).isEqualTo(RepoState.SUCCEEDED);
        });

        assertThat(store.readState(runDir).runId()).isEqualTo("S-v1");
        assertThat(Files.list(runDir).map(p -> p.getFileName().toString()))
                .noneMatch(name -> name.endsWith(".tmp"));
    }

    /**
     * The optimistic-retry contract {@link RunStore#writeDecisions(Path, Map, String)} adds on top
     * of {@link RunStore#writeDecisions(Path, Map)}: a write whose caller read the file BEFORE
     * someone else wrote to it must be refused rather than silently clobbering that other write — a
     * lost update must become a detected conflict, not stay a lost update.
     */
    @Test
    void aWriteWhoseFingerprintIsStaleIsRefused() {
        Path runDir = store.create(ws, "S-v1", "{}", "");
        store.writeDecisions(runDir, Map.of("lib", new DecisionRecord(Decision.APPROVED, "")));
        RunStore.DecisionsSnapshot stale = store.readDecisionsSnapshot(runDir);

        store.writeDecisions(runDir, Map.of("svc", new DecisionRecord(Decision.REJECTED, "no")));   // someone else

        assertThat(store.writeDecisions(runDir, Map.of("lib", new DecisionRecord(Decision.REDO, "")),
                stale.fingerprint())).isFalse();
        assertThat(store.readDecisions(runDir)).containsKey("svc");   // the other writer's verdict survived
        // Our own refused write changed nothing — the file is exactly what the other writer left it,
        // not our REDO for lib and not the APPROVED it held when we read the stale snapshot.
        assertThat(store.readDecisions(runDir)).doesNotContainKey("lib");
    }

    /**
     * {@code writeDecisions(Path, Map)} has no transition to re-apply and no retry loop, so it must
     * not do what the fingerprinted overload's caller gets to choose NOT to do: silently drop a
     * write whose fingerprint has gone stale. A caller with nothing to conflict against that DOES
     * get raced has a bug elsewhere (this method's contract assumes single-writer use); the loud
     * failure here is what surfaces that bug instead of quietly losing the write, which is the exact
     * hazard this whole class of fix exists to close.
     *
     * <p>The 2-arg overload reads its OWN fingerprint fresh, immediately before it writes — so a
     * write that merely happens BEFORE this method is called is not stale to it at all, it is just
     * the current state the fresh read picks up. The only way to make its internal write actually
     * lose the race is a real writer landing in the gap between that internal read and the internal
     * write a few method calls later, which needs genuine concurrency to trigger — the same
     * technique {@code concurrentDecisionWritersNeitherCorruptTheFileNorFailOnASharedTempName} uses,
     * which reliably reproduces this on effectively every run once six real writers contend.
     */
    @Test
    void aStaleTwoArgWriteThrowsRatherThanSilentlyDroppingTheWrite() throws Exception {
        Path runDir = store.create(ws, "S-v1", "{}", "");
        java.util.concurrent.atomic.AtomicInteger conflicts = new java.util.concurrent.atomic.AtomicInteger();

        hammer(6, 60, id -> {
            try {
                store.writeDecisions(runDir, Map.of("repo" + id,
                        new DecisionRecord(Decision.APPROVED, "reason " + id)));
            } catch (IllegalStateException conflict) {
                assertThat(conflict.getMessage()).contains(runDir.toString());
                conflicts.incrementAndGet();
            }
        });

        // Six writers racing 360 rounds of the same file WILL lose that race at least once; this
        // is what proves the throw actually fires under real contention, not just that the code
        // compiles a throw statement nothing ever reaches.
        assertThat(conflicts.get()).isGreaterThan(0);
    }

    @Test
    void aWriteWithACurrentFingerprintSucceeds() {
        Path runDir = store.create(ws, "S-v1", "{}", "");
        store.writeDecisions(runDir, Map.of("lib", new DecisionRecord(Decision.APPROVED, "")));
        RunStore.DecisionsSnapshot current = store.readDecisionsSnapshot(runDir);

        boolean applied = store.writeDecisions(runDir,
                Map.of("lib", new DecisionRecord(Decision.REDO, "again")), current.fingerprint());

        assertThat(applied).isTrue();
        assertThat(store.readDecisions(runDir).get("lib").decision()).isEqualTo(Decision.REDO);
        assertThat(store.readDecisions(runDir).get("lib").reason()).isEqualTo("again");
    }

    @Test
    void anAbsentFileHasAnEmptyFingerprintAndAConcurrentCreateIsAConflict() {
        Path runDir = store.create(ws, "S-v1", "{}", "");
        RunStore.DecisionsSnapshot before = store.readDecisionsSnapshot(runDir);
        assertThat(before.fingerprint()).isEmpty();
        assertThat(before.decisions()).isEmpty();

        store.writeDecisions(runDir, Map.of("lib", new DecisionRecord(Decision.APPROVED, "")));

        assertThat(store.writeDecisions(runDir, Map.of("svc", new DecisionRecord(Decision.REDO, "")),
                before.fingerprint())).isFalse();
        assertThat(store.readDecisions(runDir)).containsKey("lib").doesNotContainKey("svc");
    }

    @Test
    void aStaleLockIsNotReportedAsHeld() throws Exception {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");

        assertThat(store.isLockHeld(runDir)).isTrue();           // our own live PID
        Files.writeString(runDir.resolve("lock"), "999999999\n"); // a PID that cannot be alive
        assertThat(store.isLockHeld(runDir)).isFalse();

        store.releaseLock(runDir);
        assertThat(store.isLockHeld(runDir)).isFalse();
    }
}
