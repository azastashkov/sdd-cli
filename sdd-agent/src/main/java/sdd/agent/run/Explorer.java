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

            Rules that matter:
            - Never state a fact you have not read. Every finding is re-read from disk before it is \
            accepted, so a citation you did not actually open will be rejected.
            - A term you cannot place is worth recording as a finding saying exactly that, with the \
            searches you tried. An honest gap is useful; a guess dressed as a fact is not.
            - Breadth before depth. A repo you never searched is a repo you have no opinion about.
            - When you have covered every term in the task, call done(success) with a short summary. \
            If the task cannot be placed at all, call done(blocked) and say what is missing.""";

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
        ExploreTools tools = new ExploreTools(jdbi, new EstateJail(repoRoots), singleTool, trace);
        List<String> turns = new ArrayList<>();
        AgentLoop loop = new AgentLoop(model, tools, budget, contextSoftCap, clock,
                ContextWindow.Retention.EXPLORE, turns::add);
        try {
            return new Exploration(loop.run(SYSTEM_PROMPT, task, modelName, maxTokensPerCall),
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
