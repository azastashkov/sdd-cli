package sdd.cli.implement;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;

/** Persists a run under {@code <workspace>/.sdd/runs/<runId>/}: immutable plan.json, atomic state.json,
 *  append-only events.jsonl, and a lock file. */
public final class RunStore {
    private static final ObjectMapper JSON = new ObjectMapper();

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
                repos.add(new RepoRun(node.path("repo").asText(),
                        RepoState.valueOf(node.path("state").asText()),
                        node.path("branch").isNull() ? null : node.path("branch").asText(),
                        node.path("checkpointSha").isNull() ? null : node.path("checkpointSha").asText(),
                        node.path("detail").asText("")));
            }
            com.fasterxml.jackson.databind.JsonNode paused = root.path("pausedReason");
            return new RunState(root.path("runId").asText(), repos,
                    paused.isMissingNode() || paused.isNull() ? null : paused.asText(),
                    root.path("tokensSpent").asLong(0));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void releaseLock(Path runDir) {
        try {
            Files.deleteIfExists(runDir.resolve("lock"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void writeState(Path runDir, RunState state) {
        record Snapshot(String runId, String pausedReason, long tokensSpent, List<RepoRun> repos) {
        }
        try {
            String json = JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(new Snapshot(state.runId(), state.pausedReason(),
                            state.tokensSpent(), state.repos()));
            Path tmp = runDir.resolve("state.json.tmp");
            Files.writeString(tmp, json);
            try {
                try {
                    Files.move(tmp, runDir.resolve("state.json"),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, runDir.resolve("state.json"), StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void appendEvent(Path runDir, String repo, RepoState from, RepoState to, String detail) {
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
