package sdd.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Replays a recorded run's {@code apply_edit} calls against the tree they ran on, through the REAL
 * {@link FileTools#applyEdit}, and reports which outcomes changed.
 *
 * <p>Deterministic where a re-run is not. A live re-run samples a different model conversation
 * every time, so turn counts and edit counts move for reasons unrelated to any fix; this replays
 * the exact edits the recorded agent produced and asks only whether the gate's verdict on each one
 * changed.
 *
 * <p><b>Baseline-faithful.</b> An edit the recording ACCEPTED is applied, advancing the tree exactly
 * as it advanced then. An edit the recording REJECTED is attempted and then rolled back whatever
 * the new verdict is — so every later edit still meets the file state it originally met, and the
 * replay cannot drift away from the run it is measuring.
 *
 * <p>Run:
 * {@code SDD_REPLAY_TRANSCRIPT=<transcript.jsonl> SDD_REPLAY_TREE=<dir> ./gradlew :sdd-agent:test
 * --tests '*EditReplayHarness' --rerun-tasks}
 */
@Tag("measure")
@EnabledIfEnvironmentVariable(named = "SDD_REPLAY_TRANSCRIPT", matches = ".+")
class EditReplayHarness {

    private static final ObjectMapper JSON = new ObjectMapper();

    private record Edit(int turn, String path, String search, String replace, String recorded) {
        boolean wasRejected() {
            return recorded.stripLeading().startsWith("error:");
        }

        boolean wasSyntaxRejection() {
            return wasRejected() && recorded.contains("syntax error");
        }
    }

    @Test
    void replay() throws Exception {
        Path transcript = Path.of(System.getenv("SDD_REPLAY_TRANSCRIPT"));
        Path tree = Path.of(System.getenv("SDD_REPLAY_TREE"));
        FileTools tools = new FileTools(new PathJail(tree));

        List<Edit> edits = new ArrayList<>();
        int unreplayable = 0;
        for (String line : Files.readAllLines(transcript, StandardCharsets.UTF_8)) {
            JsonNode t = JSON.readTree(line);
            JsonNode calls = t.path("tool_calls");
            JsonNode results = t.path("tool_results");
            for (int i = 0; i < calls.size(); i++) {
                if (!"apply_edit".equals(calls.get(i).path("name").asText())) {
                    continue;
                }
                JsonNode args;
                try {
                    args = JSON.readTree(calls.get(i).path("args").asText("{}"));
                } catch (Exception truncated) {
                    // AgentLoop caps a recorded field at MAX_TRANSCRIPT_FIELD_CHARS (2000), so a
                    // large edit's arguments are cut mid-string and cannot be replayed. Counted
                    // rather than dropped: a replay that silently covered less than the run it
                    // claims to measure would be the same failure this project keeps finding.
                    unreplayable++;
                    continue;
                }
                edits.add(new Edit(t.path("turn").asInt(),
                        args.path("path").asText(""), args.path("search").asText(""),
                        args.path("replace").asText(""),
                        i < results.size() ? results.get(i).path("result").asText("") : ""));
            }
        }

        int accepted = 0;
        int stillRejected = 0;
        int nowAccepted = 0;
        int nowRejected = 0;
        Map<String, Integer> remainingCauses = new LinkedHashMap<>();
        List<String> flips = new ArrayList<>();

        for (Edit e : edits) {
            Path target = tree.resolve(e.path());
            String before = Files.exists(target)
                    ? Files.readString(target, StandardCharsets.UTF_8) : null;

            Optional<String> error;
            try {
                tools.applyEdit(e.path(), e.search(), e.replace());
                error = Optional.empty();
            } catch (RuntimeException ex) {
                error = Optional.of(String.valueOf(ex.getMessage()));
            }

            if (e.wasRejected()) {
                // Roll back so later edits meet the file state they originally met.
                if (error.isEmpty()) {
                    if (before == null) {
                        Files.deleteIfExists(target);
                    } else {
                        Files.writeString(target, before, StandardCharsets.UTF_8);
                    }
                    nowAccepted++;
                    flips.add("turn " + e.turn() + " " + e.path()
                            + "  WAS: " + firstLine(e.recorded()) + "  NOW: accepted");
                } else {
                    stillRejected++;
                    String cause = error.get().contains("syntax error:")
                            ? error.get().split("syntax error:")[1].strip()
                            : error.get();
                    remainingCauses.merge(cause.substring(0, Math.min(60, cause.length())), 1,
                            Integer::sum);
                }
            } else {
                if (error.isEmpty()) {
                    accepted++;
                } else {
                    nowRejected++;
                    flips.add("turn " + e.turn() + " " + e.path()
                            + "  WAS: accepted  NOW: " + firstLine(error.get()));
                }
            }
        }

        StringBuilder r = new StringBuilder();
        r.append("transcript: ").append(transcript).append('\n');
        r.append("tree:       ").append(tree).append("\n\n");
        long recordedRejects = edits.stream().filter(Edit::wasRejected).count();
        long recordedSyntax = edits.stream().filter(Edit::wasSyntaxRejection).count();
        r.append("unreplayable (args truncated in the transcript at 2000 chars): ")
                .append(unreplayable).append('\n');
        r.append("recorded: ").append(edits.size()).append(" apply_edit calls, ")
                .append(recordedRejects).append(" rejected (")
                .append(recordedSyntax).append(" of them syntax)\n");
        r.append("replayed: ").append(accepted).append(" still accepted, ")
                .append(nowAccepted).append(" NOW ACCEPTED (were rejected), ")
                .append(stillRejected).append(" still rejected, ")
                .append(nowRejected).append(" newly rejected\n\n");
        if (!remainingCauses.isEmpty()) {
            r.append("remaining rejection causes:\n");
            remainingCauses.forEach((c, n) -> r.append("  x").append(n).append(' ').append(c)
                    .append('\n'));
        }
        r.append("\nflips:\n");
        flips.forEach(f -> r.append("  ").append(f).append('\n'));
        System.out.println(r);
        Files.writeString(Path.of(System.getenv().getOrDefault("SDD_REPLAY_OUT", "replay.txt")),
                r.toString(), StandardCharsets.UTF_8);
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return (nl < 0 ? s : s.substring(0, nl)).strip();
    }
}
