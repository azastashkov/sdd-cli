package sdd.agent.tool;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import sdd.core.llm.ToolCall;
import sdd.core.llm.ToolSpec;

import java.util.List;

/**
 * Assembles the file and gradle tools into OpenAI tool schemas and a name-routed dispatch.
 * `done` is advertised so the model can call it, but the AgentLoop intercepts it — dispatching
 * `done` here is a programming error and surfaces as a malformed call.
 */
public final class Toolbox implements Tools {
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Every operation behind one declaration, for an endpoint that cannot carry six.
     *
     * <p>Measured against a GigaChat gateway: with an identical request repeated twenty times,
     * no declarations and one declaration both succeeded 20/20, six succeeded 13/20, and nine
     * failed 20/20. The threshold also MOVED during a single day — nine had been working that
     * morning — so the safe number is the smallest one, not the largest one that happens to
     * work today.
     *
     * <p>Off by default. Six declarations with their own schemas is the better interface: the
     * model is told what each operation takes, and a wrong argument is a schema error rather
     * than a runtime one.
     */
    private static final String MULTIPLEXED = "sdd";

    private final FileTools files;
    private final BuildTool build;
    private final sdd.agent.run.OutputCompactor compactor;   // null = raw (no compaction), 4A path
    private final boolean singleTool;
    private final java.util.function.Consumer<String> trace;   // null = say nothing, every pre-4E path

    public Toolbox(FileTools files, BuildTool build) {
        this(files, build, null);
    }

    public Toolbox(FileTools files, BuildTool build, sdd.agent.run.OutputCompactor compactor) {
        this(files, build, compactor, false);
    }

    public Toolbox(FileTools files, BuildTool build, sdd.agent.run.OutputCompactor compactor,
                   boolean singleTool) {
        this(files, build, compactor, singleTool, null);
    }

    /**
     * @param trace one line per tool call, or null. {@code sdd explore} has shown what the agent is
     *              doing since it existed and {@code sdd implement} has not: it ran a repo for
     *              minutes with nothing on screen but a spinner, and a slow verification build was
     *              indistinguishable from a hang. Same sink, same format, same reason.
     */
    public Toolbox(FileTools files, BuildTool build, sdd.agent.run.OutputCompactor compactor,
                   boolean singleTool, java.util.function.Consumer<String> trace) {
        this.files = files;
        this.build = build;
        this.compactor = compactor;
        this.singleTool = singleTool;
        this.trace = trace;
    }

    /** @see ExploreTools#route — same contract, same reason, and the id is preserved. */
    @Override
    public ToolCall route(ToolCall call) {
        if (!singleTool || !MULTIPLEXED.equals(call.name())) {
            return call;
        }
        JsonNode args = parse(call.name(), call.argumentsJson());
        JsonNode action = args.get("action");
        if (action == null || !action.isTextual()) {
            throw new MalformedCallException("missing required string argument: action");
        }
        com.fasterxml.jackson.databind.node.ObjectNode rest =
                ((com.fasterxml.jackson.databind.node.ObjectNode) args).deepCopy();
        rest.remove("action");
        return new ToolCall(call.id(), action.asText(), rest.toString());
    }

    private ToolSpec multiplexed() {
        return new ToolSpec(MULTIPLEXED,
                "Make one change to this repository. One action per call: read_file(path) | "
                        + "list_files(dir) | search(regex) | "
                        + "apply_edit(path,search,replace) | " + build.toolName() + "(task) | "
                        + "done(result,summary)",
                """
                {"type":"object","properties":{\
                "action":{"type":"string","enum":["read_file","list_files","search","apply_edit",\
                "%s","done"]},\
                "path":{"type":"string"},"dir":{"type":"string"},"regex":{"type":"string"},\
                "glob":{"type":"string"},\
                "search":{"type":"string"},"replace":{"type":"string"},"task":{"type":"string"},\
                "result":{"type":"string"},"summary":{"type":"string"}},"required":["action"]}"""
                        .formatted(build.toolName()));
    }

    @Override
    public String buildToolName() {
        return build.toolName();
    }

    @Override
    public List<ToolSpec> specs() {
        return singleTool ? List.of(multiplexed()) : List.of(
                new ToolSpec("read_file", "Read a file's contents (capped).",
                        obj("path", "string", "Repo-relative file path")),
                new ToolSpec("list_files", "List the entries of a directory.",
                        obj("dir", "string", "Repo-relative directory path")),
                new ToolSpec("search",
                        "Search file contents by regex, and/or list files by path glob. Give "
                                + "regex to search, glob to list matching paths (e.g. "
                                + "src/**/*.java), or both to search only within those paths.",
                        """
                        {"type":"object","properties":{\
                        "regex":{"type":"string","description":"A Java regular expression over \
                        file contents; omit to just list the files the glob matches"},\
                        "glob":{"type":"string","description":"Path glob over the repo-relative \
                        path, e.g. **/*.yml or src/main/java/**/*Test.java"}}}"""),
                new ToolSpec("apply_edit",
                        "Replace a search block with a replacement (empty search = create the file).",
                        editSchema()),
                new ToolSpec(build.toolName(), runDescription(),
                        obj("task", "string", build.taskDescription())),
                new ToolSpec("done", "Finish: result is 'success' or 'blocked'.",
                        doneSchema()));
    }

    private String runDescription() {
        return "Run one allowlisted "
                + ("run_npm".equals(build.toolName()) ? "npm script." : "Gradle task.");
    }

    @Override
    public String dispatch(String name, String argsJson) {
        // Announced BEFORE it runs, for the reason ExploreTools records: a gradle task makes no
        // model call while it builds, so a trace written only on return is silent for exactly as
        // long as the slow thing takes.
        if (trace != null) {
            trace.accept(name + subject(name, argsJson) + " ...");
        }
        long started = System.nanoTime();
        String result = run(name, argsJson);
        if (trace != null) {
            long ms = (System.nanoTime() - started) / 1_000_000;
            int lines = (int) result.lines().count();
            trace.accept("  → " + lines + (lines == 1 ? " line" : " lines") + ", " + ms + "ms");
        }
        return result;
    }

    /** What the call is ABOUT, in a few words — the path, the task, the pattern. */
    private String subject(String name, String argsJson) {
        JsonNode args;
        try {
            args = parse(name, argsJson);
        } catch (RuntimeException e) {
            return "";
        }
        if (name.equals(build.toolName())) {
            return " " + str(args, "task");
        }
        return switch (name) {
            case "read_file", "apply_edit" -> " " + optional(args, "path");
            case "list_files" -> " " + optional(args, "dir");
            case "search" -> " " + (args.hasNonNull("regex")
                    ? "/" + optional(args, "regex") + "/" : optional(args, "glob"));
            default -> "";
        };
    }

    private String run(String name, String argsJson) {
        JsonNode args = parse(name, argsJson);
        // Checked before the switch because the build tool's name varies by toolchain and a switch
        // label cannot: a repo advertises run_gradle or run_npm, never both.
        if (name.equals(build.toolName())) {
            String task = str(args, "task");
            return compactor == null ? build.run(task) : compactor.compact(build.runFull(task), task);
        }
        return switch (name) {
            case "read_file" -> files.readFile(str(args, "path"));
            case "list_files" -> files.listFiles(str(args, "dir"));
            case "search" -> files.search(optional(args, "regex"), optional(args, "glob"));
            case "apply_edit" -> files.applyEdit(str(args, "path"), str(args, "search"), str(args, "replace"));
            case "done" -> throw new MalformedCallException("done is handled by the loop, not dispatched");
            default -> throw new MalformedCallException("unknown tool: " + name);
        };
    }

    private static JsonNode parse(String name, String argsJson) {
        try {
            JsonNode node = JSON.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            if (!node.isObject()) {
                throw new MalformedCallException("arguments for " + name + " must be a JSON object");
            }
            return node;
        } catch (JacksonException e) {
            throw new MalformedCallException("unparseable arguments for " + name + ": " + e.getOriginalMessage());
        }
    }

    private static String str(JsonNode args, String field) {
        JsonNode value = args.get(field);
        if (value == null || !value.isTextual()) {
            throw new MalformedCallException("missing required string argument: " + field);
        }
        return value.asText();
    }

    /** A string argument that may legitimately be absent — {@code search} now takes two, either
     *  of which alone is a valid call, so a missing one is not a malformed call. */
    private static String optional(JsonNode args, String field) {
        JsonNode value = args.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }

    private static String obj(String field, String type, String desc) {
        return """
                {"type":"object","properties":{"%s":{"type":"%s","description":"%s"}},"required":["%s"]}"""
                .formatted(field, type, desc, field);
    }

    private static String editSchema() {
        return """
                {"type":"object","properties":{"path":{"type":"string"},"search":{"type":"string"},\
                "replace":{"type":"string"}},"required":["path","search","replace"]}""";
    }

    private static String doneSchema() {
        return """
                {"type":"object","properties":{"result":{"type":"string","enum":["success","blocked"]},\
                "summary":{"type":"string"}},"required":["result","summary"]}""";
    }
}
