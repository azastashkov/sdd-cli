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
        Path runDir = workspace.resolve(".sdd/runs/" + runId);
        try {
            Files.createDirectories(runDir);
            Path lock = runDir.resolve("lock");
            try {
                Files.createFile(lock);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                throw new IllegalStateException("run " + runId + " is already in progress (lock held at "
                        + lock + "); remove the lock to override");
            }
            Files.writeString(runDir.resolve("plan.json"), planJson);
            return runDir;
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

    /**
     * Persists the agent loop's notable events for one repo to {@code <repo>/agent-events.jsonl}. NOTE:
     * this is what 4B's {@code StepOutcome.events()} exposes — NOT the design's full model-turn
     * {@code transcript.jsonl} or structured {@code edits.jsonl}, which need a 4B change to surface the
     * ContextWindow turns / applied edits and are deferred to a later phase.
     */
    public void writeAgentEvents(Path runDir, String repo, List<String> events) {
        try {
            Path repoDir = runDir.resolve(repo.replaceAll("[^A-Za-z0-9._-]", "-"));
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

    private static String jsonString(String value) {
        try {
            return JSON.writeValueAsString(value == null ? "" : value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "\"\"";
        }
    }
}
