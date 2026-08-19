package sdd.agent.tool;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import sdd.core.llm.ToolSpec;

import java.util.List;

/**
 * Assembles the file and gradle tools into OpenAI tool schemas and a name-routed dispatch.
 * `done` is advertised so the model can call it, but the AgentLoop intercepts it — dispatching
 * `done` here is a programming error and surfaces as a malformed call.
 */
public final class Toolbox implements Tools {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FileTools files;
    private final BuildTool build;
    private final sdd.agent.run.OutputCompactor compactor;   // null = raw (no compaction), 4A path

    public Toolbox(FileTools files, BuildTool build) {
        this(files, build, null);
    }

    public Toolbox(FileTools files, BuildTool build, sdd.agent.run.OutputCompactor compactor) {
        this.files = files;
        this.build = build;
        this.compactor = compactor;
    }

    @Override
    public String buildToolName() {
        return build.toolName();
    }

    @Override
    public List<ToolSpec> specs() {
        return List.of(
                new ToolSpec("read_file", "Read a file's contents (capped).",
                        obj("path", "string", "Repo-relative file path")),
                new ToolSpec("list_files", "List the entries of a directory.",
                        obj("dir", "string", "Repo-relative directory path")),
                new ToolSpec("search", "Regex-search the repo's text files.",
                        obj("regex", "string", "A Java regular expression")),
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
            case "search" -> files.search(str(args, "regex"));
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
