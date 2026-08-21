package sdd.agent.run;

import org.jdbi.v3.core.Jdbi;
import sdd.agent.loop.AgentBudget;
import sdd.agent.loop.AgentLoop;
import sdd.agent.loop.AgentOutcome;
import sdd.agent.loop.ContextWindow;
import sdd.agent.tool.EstateJail;
import sdd.agent.tool.ExploreTools;
import sdd.agent.tool.Notebook;
import sdd.core.llm.ChatModel;

import sdd.core.llm.ModelException;

import java.nio.file.Path;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one read-only exploration of the whole estate against a spec's free text.
 *
 * <p>The loop, budgets, wedge detector and transcript are the same machine {@code sdd implement}
 * uses — only the tools and the retention policy differ. What comes back is a {@link Notebook} the
 * caller merges into the spec for a human to review; nothing here writes anything anywhere.
 */
public final class Explorer {

    /**
     * @param outcome  why the loop stopped — reported, never used to discard the notebook
     * @param notebook what was established, however the run ended
     */
    public record Exploration(AgentOutcome outcome, Notebook notebook, List<String> transcript,
                              String transportError) {
        public Exploration {
            transcript = List.copyOf(transcript);
        }

        /** A run that ended normally: the outcome carries its own transcript. */
        public Exploration(AgentOutcome outcome, Notebook notebook) {
            this(outcome, notebook, outcome.transcript(), null);
        }

        /** True when the endpoint failed rather than the survey finishing. */
        public boolean failed() {
            return transportError != null;
        }
    }

    public static final String SYSTEM_PROMPT = """
            You are a senior engineer surveying a multi-repository estate to work out what a task \
            actually touches. You have read-only access: you cannot edit anything and there is no \
            build to run. Your output is a proposal a human will review before any planning happens.

            Work like this:
            1. list_repos first, so you know what exists.
            2. For each distinctive term in the task — a class, a config key, a database table, a \
            queue or channel name, a dashboard, an endpoint — find where it actually lives. \
            search_code searches the real text of every repo and is the tool that finds things the \
            index has no concept of. search_symbols only knows indexed type and member names.
            3. Read the files around each hit. A grep hit is a lead, not an answer.
            4. propose_touchpoint for anything the knowledge base can resolve — that is what drives \
            impact analysis. It will refuse a value the KB does not know; that refusal means your \
            guess was wrong, not that the tool is broken.
            5. record_finding for everything else: one checkable sentence plus the <repo>/<path>:<line> \
            you read. Record the thing a planner could not otherwise know — which repo owns a key, \
            what writes a channel, which service reads a table.

            When the task is a regression — something worked at one revision and not at another — \
            git_history is what the working tree cannot tell you. Start with op=refs if you do not \
            know what the other revision is called, then op=log over the range to see what landed, \
            op=diff to see which files it touched, and op=diff with path= for the actual patch of \
            one file. op=blame says which commit last wrote a given line.

            Rules that matter:
            - Never state a fact you have not read. Every finding is re-read from disk before it is \
            accepted, so a citation you did not actually open will be rejected.
            - A diff is not a citation. Line numbers from an old commit do not point at the same text \
            today, so to record something you found in history, read_file the CURRENT file, cite the \
            line you actually read, and put the commit sha in the sentence.
            - A term you cannot place is worth recording as a finding saying exactly that, with the \
            searches you tried. An honest gap is useful; a guess dressed as a fact is not.
            - Breadth before depth. A repo you never searched is a repo you have no opinion about.
            - When you have covered every term in the task, call done(success) with a short summary. \
            If the task cannot be placed at all, call done(blocked) and say what is missing.""";

    /**
     * Appended to the system prompt on a wire that carries one tool call per turn.
     *
     * <p>Not general advice: on a wire that can express several, making them in one turn is faster
     * and correct. See {@code WireFormat.oneCallPerTurn} for the measurement that forced this.
     */
    public static final String ONE_CALL_PER_TURN =
            sdd.core.llm.WireFormat.ONE_CALL_PER_TURN_GUIDANCE;

    private final Jdbi jdbi;

    public Explorer(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /** Repo name → checkout root, from the KB. The same idiom {@code ImplementCommand} uses. */
    public static Map<String, Path> repoRoots(Jdbi jdbi, Path workspace) {
        Map<String, Path> roots = new LinkedHashMap<>();
        jdbi.useHandle(h -> h.createQuery("SELECT name, path FROM repo ORDER BY name")
                .map((rs, ctx) -> Map.entry(rs.getString("name"), rs.getString("path")))
                .forEach(e -> roots.put(e.getKey(), workspace.resolve(e.getValue()))));
        return roots;
    }

    public Exploration explore(Map<String, Path> repoRoots, String task, ChatModel model,
                               String modelName, AgentBudget budget, int contextSoftCap,
                               int maxTokensPerCall, InstantSource clock) {
        return explore(repoRoots, task, model, modelName, budget, contextSoftCap, maxTokensPerCall,
                clock, false);
    }

    /** @param singleTool advertise one multiplexed tool instead of nine — see {@code ExploreTools} */
    public Exploration explore(Map<String, Path> repoRoots, String task, ChatModel model,
                               String modelName, AgentBudget budget, int contextSoftCap,
                               int maxTokensPerCall, InstantSource clock, boolean singleTool) {
        return explore(repoRoots, task, model, modelName, budget, contextSoftCap, maxTokensPerCall,
                clock, singleTool, null);
    }

    /** @param trace one line per tool call as it happens, or null for silence */
    public Exploration explore(Map<String, Path> repoRoots, String task, ChatModel model,
                               String modelName, AgentBudget budget, int contextSoftCap,
                               int maxTokensPerCall, InstantSource clock, boolean singleTool,
                               java.util.function.Consumer<String> trace) {
        return explore(repoRoots, task, model, modelName, budget, contextSoftCap, maxTokensPerCall,
                clock, singleTool, trace, null, ExploreTools.MAX_QUESTIONS);
    }

    /**
     * @param asker        where a question reaches a human, or null when nobody is attached — in
     *                     which case the tool is not advertised at all. Threaded exactly like
     *                     {@code trace}, and for the same reason: this module must never see a
     *                     writer, a {@code Progress} or {@code System.in}
     * @param maxQuestions how many questions this run may ask
     */
    public Exploration explore(Map<String, Path> repoRoots, String task, ChatModel model,
                               String modelName, AgentBudget budget, int contextSoftCap,
                               int maxTokensPerCall, InstantSource clock, boolean singleTool,
                               java.util.function.Consumer<String> trace,
                               sdd.agent.tool.HumanAsk asker, int maxQuestions) {
        return explore(repoRoots, task, model, modelName, budget, contextSoftCap, maxTokensPerCall,
                clock, singleTool, trace, asker, maxQuestions, Map.of(), null);
    }

    /**
     * @param ranges    repo -> the revision range under investigation, from {@code --since}. Empty
     *                  leaves {@code git_history} unadvertised, so a survey that was not asked to
     *                  compare two points in history keeps today's declaration count exactly
     * @param changeLog what those ranges resolved to, rendered, and appended to the task. Computed
     *                  deterministically by the caller rather than discovered by the model — the
     *                  same division {@code sdd plan --since} already draws, and it means the
     *                  survey starts from the changed files instead of hunting for them. Null or
     *                  blank appends nothing
     */
    public Exploration explore(Map<String, Path> repoRoots, String task, ChatModel model,
                               String modelName, AgentBudget budget, int contextSoftCap,
                               int maxTokensPerCall, InstantSource clock, boolean singleTool,
                               java.util.function.Consumer<String> trace,
                               sdd.agent.tool.HumanAsk asker, int maxQuestions,
                               Map<String, String> ranges, String changeLog) {
        return explore(repoRoots, task, model, modelName, budget, contextSoftCap, maxTokensPerCall,
                clock, singleTool, trace, asker, maxQuestions, ranges, changeLog, false);
    }

    /**
     * @param oneCallPerTurn append {@link #ONE_CALL_PER_TURN} to the system prompt, for a wire
     *                       that cannot carry a second tool call in one turn
     */
    public Exploration explore(Map<String, Path> repoRoots, String task, ChatModel model,
                               String modelName, AgentBudget budget, int contextSoftCap,
                               int maxTokensPerCall, InstantSource clock, boolean singleTool,
                               java.util.function.Consumer<String> trace,
                               sdd.agent.tool.HumanAsk asker, int maxQuestions,
                               Map<String, String> ranges, String changeLog,
                               boolean oneCallPerTurn) {
        ExploreTools tools = new ExploreTools(jdbi, new EstateJail(repoRoots), singleTool, trace,
                asker, maxQuestions, ranges);
        String prompt = changeLog == null || changeLog.isBlank() ? task : task + "\n\n" + changeLog;
        List<String> turns = new ArrayList<>();
        AgentLoop loop = new AgentLoop(model, tools, budget, contextSoftCap, clock,
                ContextWindow.Retention.EXPLORE, turns::add);
        try {
            String system = oneCallPerTurn ? SYSTEM_PROMPT + ONE_CALL_PER_TURN : SYSTEM_PROMPT;
            return new Exploration(loop.run(system, prompt, modelName, maxTokensPerCall),
                    tools.notebook());
        } catch (ModelException e) {
            // A transport failure is NOT a survey result, and the caller must be able to tell the
            // difference — hence transportError rather than a synthesized outcome that would read
            // like the run simply stopped. But it must not destroy the run either: everything found
            // so far, and the turns that led up to the failure, are exactly what a reader needs to
            // understand it. Letting the exception through discarded all of it.
            return new Exploration(null, tools.notebook(), turns,
                    e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }
}
