package sdd.cli.implement;

import com.fasterxml.jackson.databind.ObjectMapper;
import sdd.cli.review.Decision;
import sdd.cli.review.DecisionRecord;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.InstantSource;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Persists a run under {@code <workspace>/.sdd/runs/<runId>/}: immutable plan.json, atomic state.json,
 *  append-only events.jsonl, and a lock file.
 *
 *  <p>Not {@code final} for one reason: a test overriding {@link #readDecisionsSnapshot} is the only
 *  way to land a conflicting write inside {@link #writeDecisions(Path, Map)}'s own read-then-write
 *  window deterministically — the same inject-between-read-and-write technique
 *  {@code ConflictInjectingRedo} uses one layer up. Production has exactly one implementation and
 *  nothing here is designed for behavioral subclassing. */
public class RunStore {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Makes {@link #publishAtomically}'s staging file unique among this JVM's threads; the PID
     *  alongside it makes it unique among processes. */
    private static final java.util.concurrent.atomic.AtomicLong TMP_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();

    private final InstantSource clock;

    public RunStore(InstantSource clock) {
        this.clock = clock;
    }

    public static RunStore system() {
        return new RunStore(InstantSource.system());
    }

    public Path create(Path workspace, String runId, String planJson) {
        return create(workspace, runId, planJson, "");
    }

    public Path create(Path workspace, String runId, String planJson, String specText) {
        Path runDir = workspace.resolve(".sdd/runs/" + runId);
        try {
            Files.createDirectories(runDir);
            acquireLock(runDir);
            Files.writeString(runDir.resolve("plan.json"), planJson);
            Files.writeString(runDir.resolve("spec.md"), specText);
            return runDir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void acquireLock(Path runDir) {
        Path lock = runDir.resolve("lock");
        String pid = Long.toString(ProcessHandle.current().pid());
        try {
            Files.writeString(lock, pid, StandardOpenOption.CREATE_NEW);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            if (lockIsStale(lock)) {
                try {
                    Files.deleteIfExists(lock);
                    Files.writeString(lock, pid, StandardOpenOption.CREATE_NEW);
                    return;
                } catch (IOException retry) {
                    throw new UncheckedIOException(retry);
                }
            }
            throw new IllegalStateException("run " + runDir.getFileName() + " is already in progress "
                    + "(lock held at " + lock + ownerSuffix(lock) + "); remove the lock to override");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Stale = the lock names a PID that is provably not alive. Empty/legacy/unreadable locks are
     *  treated as LIVE (safe default: refuse, let the human decide). */
    private static boolean lockIsStale(Path lock) {
        try {
            String text = Files.readString(lock).strip();
            if (text.isEmpty()) {
                return false;
            }
            long pid = Long.parseLong(text);
            return ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true);
        } catch (IOException | NumberFormatException e) {
            return false;
        }
    }

    private static String ownerSuffix(Path lock) {
        try {
            String text = Files.readString(lock).strip();
            return text.isEmpty() ? "" : " by pid " + text;
        } catch (IOException e) {
            return "";
        }
    }

    public RunState readState(Path runDir) {
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    JSON.readTree(Files.readString(runDir.resolve("state.json")));
            List<RepoRun> repos = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode node : root.path("repos")) {
                // Pre-5C-2 state.json has no "failure_code" key at all; path() returns a MissingNode
                // for that, which isNull() alone would NOT catch (isNull() is false for a merely
                // absent field) — same absent-key-tolerant idiom as pausedReason below.
                com.fasterxml.jackson.databind.JsonNode failureCode = node.path("failure_code");
                // Task 5: a pre-existing state.json has neither key at all — same absent-key-
                // tolerant idiom as failureCode above.
                com.fasterxml.jackson.databind.JsonNode prId = node.path("pr_id");
                com.fasterxml.jackson.databind.JsonNode prUrl = node.path("pr_url");
                repos.add(new RepoRun(node.path("repo").asText(),
                        RepoState.valueOf(node.path("state").asText()),
                        node.path("branch").isNull() ? null : node.path("branch").asText(),
                        node.path("checkpointSha").isNull() ? null : node.path("checkpointSha").asText(),
                        node.path("detail").asText(""),
                        failureCode.isMissingNode() || failureCode.isNull() ? null : failureCode.asText(),
                        prId.isMissingNode() || prId.isNull() ? null : prId.asInt(),
                        prUrl.isMissingNode() || prUrl.isNull() ? null : prUrl.asText()));
            }
            com.fasterxml.jackson.databind.JsonNode paused = root.path("pausedReason");
            return new RunState(root.path("runId").asText(), repos,
                    paused.isMissingNode() || paused.isNull() ? null : paused.asText(),
                    root.path("tokensSpent").asLong(0));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** True only when a lock file exists AND it is not stale — a bare existence check would
     *  permanently block review of the very run whose {@code sdd implement} crashed, which is
     *  exactly the run a human most needs to review. */
    public boolean isLockHeld(Path runDir) {
        Path lock = runDir.resolve("lock");
        return Files.exists(lock) && !lockIsStale(lock);
    }

    /** A lock file that exists but names a PID that is provably gone — the leftover of a crashed
     *  {@code sdd implement}. Read-mostly commands proceed anyway (that run is exactly the one a
     *  human needs to look at) but must be able to SAY so, which is what this is for. */
    public boolean isLockStale(Path runDir) {
        Path lock = runDir.resolve("lock");
        return Files.exists(lock) && lockIsStale(lock);
    }

    public void releaseLock(Path runDir) {
        try {
            Files.deleteIfExists(runDir.resolve("lock"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Publishes {@code json} at {@code target} by writing it to a fresh, uniquely-named temp file
     * in the same directory and then renaming it over the target.
     *
     * <p>The rename is what a reader benefits from: it either sees the previous file or the whole
     * new one, never a half-written one. The uniqueness of the temp name is what a concurrent
     * WRITER benefits from: with one shared fixed name (this used to be {@code state.json.tmp} /
     * {@code decisions.json.tmp}) two writers stage into the same path, so one can publish the
     * other's partial bytes and the other's own rename then fails {@code NoSuchFileException}
     * against a file the first has already consumed.
     *
     * <p>Neither property makes a read-modify-write of the whole document safe: two writers that
     * each read, edit and rewrite still lose one update, and nothing here prevents that. This
     * removes the corruption, not the lost update.
     *
     * <p>The name is built rather than taken from {@code Files.createTempFile} so the published
     * file keeps the umask-derived permissions a plain write gave it (a JDK temp file is 0600).
     * PID separates processes; the counter separates threads within one.
     */
    private static void publishAtomically(Path target, String json) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + "." + ProcessHandle.current().pid()
                + "-" + TMP_SEQUENCE.incrementAndGet() + ".tmp");
        try {
            Files.writeString(tmp, json);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public void writeState(Path runDir, RunState state) {
        record Snapshot(String runId, String pausedReason, long tokensSpent, List<RepoRun> repos) {
        }
        try {
            String json = JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(new Snapshot(state.runId(), state.pausedReason(),
                            state.tokensSpent(), state.repos()));
            publishAtomically(runDir.resolve("state.json"), json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void appendEvent(Path runDir, String repo, RepoState from, RepoState to, String detail) {
        appendTransition(runDir, repo, from, to, detail);
    }

    /** Gate-2 decision transitions (design line 71) share the events log with the run's own state
     *  transitions: same repo-scoped from/to shape, a different enum on the wire. */
    public void appendEvent(Path runDir, String repo, Decision from, Decision to, String detail) {
        appendTransition(runDir, repo, from, to, detail);
    }

    private void appendTransition(Path runDir, String repo, Enum<?> from, Enum<?> to, String detail) {
        String line = "{\"at\":\"" + clock.instant() + "\",\"repo\":" + jsonString(repo)
                + ",\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"detail\":" + jsonString(detail) + "}\n";
        try {
            Files.writeString(runDir.resolve("events.jsonl"), line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A run-scoped (not repo-scoped) event line — today only pauses use it. */
    public void appendRunEvent(Path runDir, String detail) {
        String line = "{\"at\":\"" + clock.instant() + "\",\"run\":\"pause\",\"detail\":"
                + jsonString(detail) + "}\n";
        try {
            Files.writeString(runDir.resolve("events.jsonl"), line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Persists the agent loop's notable events for one repo to {@code <repo>/agent-events.jsonl} — terse
     * breadcrumbs, not the full record. See {@link #writeTranscript} and {@link #writeEdits} for the
     * design's per-model-call transcript and applied-edit log.
     */
    public void writeAgentEvents(Path runDir, String repo, List<String> events) {
        try {
            Path repoDir = runDir.resolve(sanitize(repo));
            Files.createDirectories(repoDir);
            StringBuilder lines = new StringBuilder();
            for (String event : events) {
                lines.append(jsonString(event)).append('\n');
            }
            Files.writeString(repoDir.resolve("agent-events.jsonl"), lines.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Persists the per-model-call turn transcript (design line 60's {@code transcript.jsonl}) for one
     *  repo. Unlike {@link #writeAgentEvents}, each line is already a complete JSON object built by
     *  {@code AgentLoop}/{@code RepoStepRunner} — it is written as-is, not re-encoded as a JSON string. */
    public void writeTranscript(Path runDir, String repo, List<String> lines) {
        writeJsonlAsIs(runDir, repo, "transcript.jsonl", lines);
    }

    /** Persists the successfully-applied-edit log (design line 60's {@code edits.jsonl}) for one repo.
     *  Each line is already a complete JSON object; see {@link #writeTranscript}. */
    public void writeEdits(Path runDir, String repo, List<String> lines) {
        writeJsonlAsIs(runDir, repo, "edits.jsonl", lines);
    }

    private void writeJsonlAsIs(Path runDir, String repo, String fileName, List<String> lines) {
        try {
            Path repoDir = runDir.resolve(sanitize(repo));
            Files.createDirectories(repoDir);
            StringBuilder out = new StringBuilder();
            for (String line : lines) {
                out.append(line).append('\n');
            }
            Files.writeString(repoDir.resolve(fileName), out.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void writePropagation(Path runDir, Map<String, RepoPropagation> propagation) {
        record BumpDto(String group, String name, String oldVersion, String newVersion) {
        }
        record PropDto(List<BumpDto> bumps, String publishVersion) {
        }
        Map<String, PropDto> dto = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, RepoPropagation> entry : propagation.entrySet()) {
            List<BumpDto> bumps = entry.getValue().bumps().stream()
                    .map(b -> new BumpDto(b.group(), b.name(), b.oldVersion(), b.newVersion()))
                    .toList();
            String publishVersion = entry.getValue().publish() == null
                    ? null : entry.getValue().publish().version();
            dto.put(entry.getKey(), new PropDto(bumps, publishVersion));
        }
        try {
            Files.writeString(runDir.resolve("propagation.json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(dto));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The frozen propagation plan, or null for runs from before the snapshot existed. */
    public Map<String, RepoPropagation> readPropagation(Path runDir) {
        Path file = runDir.resolve("propagation.json");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = JSON.readTree(Files.readString(file));
            Map<String, RepoPropagation> result = new java.util.LinkedHashMap<>();
            root.properties().forEach(entry -> {
                List<RepoPropagation.BumpEdit> bumps = new java.util.ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode bump : entry.getValue().path("bumps")) {
                    bumps.add(new RepoPropagation.BumpEdit(bump.path("group").asText(),
                            bump.path("name").asText(), bump.path("oldVersion").asText(),
                            bump.path("newVersion").asText()));
                }
                com.fasterxml.jackson.databind.JsonNode version = entry.getValue().path("publishVersion");
                RepoPropagation.PublishSpec publish = version.isNull() || version.isMissingNode()
                        ? null : new RepoPropagation.PublishSpec(version.asText(), runDir.resolve("m2"));
                result.put(entry.getKey(), new RepoPropagation(bumps, publish));
            });
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void writeContract(Path runDir, String contractId, String body) {
        try {
            Path dir = Files.createDirectories(runDir.resolve("contracts"));
            Files.writeString(dir.resolve(sanitize(contractId) + ".md"), body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The actualized contract body, or null when the provider has not gone green yet. */
    public String readContract(Path runDir, String contractId) {
        Path file = runDir.resolve("contracts").resolve(sanitize(contractId) + ".md");
        try {
            return Files.exists(file) ? Files.readString(file) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Records what happened to a repo's compatibility gates.
     *
     * <p>Per repo rather than one estate-wide file, because repos run concurrently and a shared
     * map would need a lock this store has nowhere to put one.
     */
    public void writeCompatGates(Path runDir, String repo, List<CompatGate> gates) {
        try {
            Path repoDir = runDir.resolve(sanitize(repo));
            Files.createDirectories(repoDir);
            Files.writeString(repoDir.resolve("compat-gates.json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(gates));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @return the recorded gates, or an EMPTY list for a repo that declared no compat contract —
     *         and, deliberately, also for a run made before this file existed. An older run has no
     *         evidence either way, and inventing a SKIPPED for it would fail reviews of runs that
     *         were fine.
     */
    public List<CompatGate> readCompatGates(Path runDir, String repo) {
        Path file = runDir.resolve(sanitize(repo)).resolve("compat-gates.json");
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = JSON.readTree(Files.readString(file));
            List<CompatGate> gates = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode node : root) {
                gates.add(new CompatGate(node.path("compat").asText(),
                        CompatGate.Outcome.valueOf(node.path("outcome").asText()),
                        node.path("detail").asText("")));
            }
            return gates;
        } catch (IOException | IllegalArgumentException e) {
            // An unreadable record is not evidence that a gate was skipped, so it must not be
            // reported as one — same rule as an absent file.
            return List.of();
        }
    }

    public Path reviewDir(Path runDir) {
        return runDir.resolve("review");
    }

    /** Gate-2 artifacts (design line 67). File names are sanitized like per-repo directories. */
    public void writeReview(Path runDir, String fileName, String content) {
        try {
            Path dir = Files.createDirectories(reviewDir(runDir));
            Files.writeString(dir.resolve(sanitize(fileName)), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The Gate-2 decision file, keyed by repo. Empty (not null) when the run has no decisions
     *  yet — every repo not present defaults to PENDING via {@link sdd.cli.review.Decisions#of}. */
    public Map<String, DecisionRecord> readDecisions(Path runDir) {
        Path file = reviewDir(runDir).resolve("decisions.json");
        if (!Files.exists(file)) {
            return Map.of();
        }
        try {
            return parseDecisions(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@code decisions.json} plus a SHA-256 fingerprint of the exact bytes it was parsed from — the
     * pair {@link #writeDecisions(Path, Map, String)} needs to detect whether the file changed
     * underneath a caller between this read and that write. The fingerprint is {@code ""} when the
     * file does not exist yet, so a concurrent CREATE (not just a concurrent edit) is a conflict
     * too, never a silent overwrite of whoever wrote it first.
     *
     * <p>Both the map and the fingerprint are derived from ONE {@code readAllBytes} call, not two
     * separate reads — reading the bytes once and parsing them twice would let the file change
     * between those two reads and hand back a map and a fingerprint that describe different
     * generations of the file.
     */
    public DecisionsSnapshot readDecisionsSnapshot(Path runDir) {
        Path file = reviewDir(runDir).resolve("decisions.json");
        try {
            if (!Files.exists(file)) {
                return new DecisionsSnapshot(Map.of(), "");
            }
            byte[] bytes = Files.readAllBytes(file);
            return new DecisionsSnapshot(parseDecisions(bytes), fingerprint(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** See {@link #readDecisionsSnapshot}. */
    public record DecisionsSnapshot(Map<String, DecisionRecord> decisions, String fingerprint) {
    }

    private static Map<String, DecisionRecord> parseDecisions(byte[] bytes) throws IOException {
        com.fasterxml.jackson.databind.JsonNode root = JSON.readTree(bytes);
        Map<String, DecisionRecord> result = new java.util.LinkedHashMap<>();
        root.properties().forEach(entry -> result.put(entry.getKey(),
                new DecisionRecord(parseDecision(entry.getValue().path("decision").asText()),
                        entry.getValue().path("reason").asText(""))));
        return result;
    }

    /** SHA-256 is guaranteed present on every JDK (it's one of the JCA's standard algorithm names),
     *  so {@link NoSuchAlgorithmException} here would mean a broken JVM, not a caller error. */
    private static String fingerprint(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Raw {@code decision} tokens exactly as recorded in {@code decisions.json}, keyed by repo —
     *  unlike {@link #readDecisions}, an unrecognized token comes back AS WRITTEN rather than
     *  silently degraded to PENDING. {@code sdd clean} needs this distinction: {@link #readDecisions}
     *  degrading an unrecognized token to PENDING is the safe default for {@code review} (PENDING
     *  just means "ask the human again"), but the identical default is unsafe for {@code clean},
     *  where PENDING means "eligible for deletion" — a hand-edited or future-versioned file must
     *  never silently convert possibly-APPROVED work into garbage. Empty (not null) when the run has
     *  no decisions file yet. */
    public Map<String, String> readDecisionTokens(Path runDir) {
        Path file = reviewDir(runDir).resolve("decisions.json");
        if (!Files.exists(file)) {
            return Map.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = JSON.readTree(Files.readString(file));
            Map<String, String> result = new java.util.LinkedHashMap<>();
            root.properties().forEach(entry ->
                    result.put(entry.getKey(), entry.getValue().path("decision").asText("")));
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A hand-edited or future-versioned file must not crash the command with "No enum constant …" —
     *  an unrecognized token degrades to PENDING, the safe default (re-decide, don't misread). */
    private static Decision parseDecision(String token) {
        try {
            return Decision.valueOf(token);
        } catch (IllegalArgumentException e) {
            return Decision.PENDING;
        }
    }

    /**
     * Replaces {@code decisions.json} wholesale with {@code decisions}, through
     * {@link #publishAtomically}: a reader is guaranteed to see either the previous file or the
     * whole new one, and two writers cannot corrupt each other's staging file.
     *
     * <p>For callers with nothing to conflict against — a test writing its own fixture, or the very
     * first write of a run nobody else can be racing yet — this reads a fresh fingerprint
     * immediately beforehand and delegates to {@link #writeDecisions(Path, Map, String)}. This
     * caller has no transition to re-apply and no loop to retry with, so a refusal is not something
     * it can recover from — but it must not be swallowed either: silently doing nothing would
     * recreate the exact hazard this class exists to close (a lost verdict that looks identical to
     * "never decided"). So a refusal here throws {@link IllegalStateException} naming the run
     * instead of returning quietly. A caller that CAN recover — {@code DecisionCommand},
     * {@code InteractiveReview} — must use the fingerprinted form and its own retry loop instead;
     * see that method's javadoc for what it guarantees and what it does not.
     *
     * @throws IllegalStateException if {@code decisions.json} changed between the fingerprint read
     *                                immediately above and this write — this overload has no retry
     *                                loop to recover with, so it fails loudly rather than dropping
     *                                the caller's write
     */
    public void writeDecisions(Path runDir, Map<String, DecisionRecord> decisions) {
        if (!writeDecisions(runDir, decisions, readDecisionsSnapshot(runDir).fingerprint())) {
            throw new IllegalStateException("decisions.json changed concurrently under " + runDir
                    + " — this caller has no retry loop; use writeDecisions(runDir, decisions, "
                    + "fingerprint) and retry the one transition instead");
        }
    }

    /**
     * Publishes {@code decisions} at {@code decisions.json} exactly like
     * {@link #writeDecisions(Path, Map)}, but refuses — writing nothing, returning {@code false} —
     * when the file's current fingerprint no longer matches {@code expectedFingerprint}: someone
     * else has written since the caller last read. This is what turns the read-modify-write hazard
     * described on {@link #writeDecisions(Path, Map)} into something a caller can detect and act on:
     * read a {@link DecisionsSnapshot}, build a {@code Decisions} from it, apply exactly one
     * transition, and write back with the snapshot's fingerprint; on {@code false}, re-read and redo
     * the same transition against the fresh state. {@code RunStore} does not run that loop itself —
     * only the caller knows which single transition to re-apply, and a store-level retry could only
     * replay an opaque map, clobbering whatever the other writer just added.
     *
     * <p><b>What this does and does not guarantee.</b> Compare-then-publish is not atomic against a
     * determined racer: another writer's own {@link #publishAtomically} rename can still land in the
     * gap between this method's fingerprint check and its own rename, so two callers can both
     * observe a matching fingerprint and both believe they are about to win. That interleaving is
     * not serialized away here — it is caught on the NEXT read, because the file it lands on no
     * longer matches whatever fingerprint that write itself was compared against. The guarantee this
     * provides is narrower than "safe concurrent writes": every write either lands against state the
     * caller has actually seen, or is refused so the caller can retry against fresher state. A lost
     * update becomes a detected conflict — not a guarantee of serialization, and not less
     * concurrency-safety than that.
     */
    public boolean writeDecisions(Path runDir, Map<String, DecisionRecord> decisions,
                                  String expectedFingerprint) {
        record Dto(String decision, String reason) {
        }
        Path file = reviewDir(runDir).resolve("decisions.json");
        try {
            Files.createDirectories(reviewDir(runDir));
            String currentFingerprint = Files.exists(file) ? fingerprint(Files.readAllBytes(file)) : "";
            if (!currentFingerprint.equals(expectedFingerprint)) {
                return false;
            }
            Map<String, Dto> dto = new java.util.TreeMap<>();
            decisions.forEach((repo, record) -> dto.put(repo, new Dto(record.decision().name(), record.reason())));
            publishAtomically(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(dto));
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private static String jsonString(String value) {
        try {
            return JSON.writeValueAsString(value == null ? "" : value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "\"\"";
        }
    }
}
