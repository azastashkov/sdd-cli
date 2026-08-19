package sdd.agent.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jdbi.v3.core.Jdbi;
import sdd.agent.loop.AgentLoop;
import sdd.agent.loop.AgentOutcome;
import sdd.agent.loop.AgentResult;
import sdd.agent.tool.FileTools;
import sdd.agent.tool.BuildTool;
import sdd.agent.tool.GradleTool;
import sdd.agent.tool.NpmTool;
import sdd.agent.tool.PathJail;
import sdd.agent.tool.Toolbox;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ModelException;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives ONE attempt for one repo step (design Component 3): build a lean work order, run the
 * agent loop, verify independently on done, retry a verify-fail (≤2 cycles), and restart once on
 * context exhaustion with a machine digest. Multi-attempt escalation + git are Phase 4C's (they
 * need run state and git); this returns a typed StepOutcome for 4C to act on.
 */
public final class RepoStepRunner {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_VERIFY_CYCLES = 2;

    private final Jdbi jdbi;

    public RepoStepRunner(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public StepOutcome run(RepoStep step, ChatModel model, String modelName, RunnerSettings settings) {
        return run(step, model, modelName, settings, "");
    }

    public StepOutcome run(RepoStep step, ChatModel model, String modelName, RunnerSettings settings,
                           String priorDigest) {
        // The settings say which toolchain this repo uses; the filesystem is consulted only when
        // they do not, so a repo that has never been indexed still gets the right tool.
        sdd.core.toolchain.Toolchain toolchain = settings.toolchain() == sdd.core.toolchain.Toolchain.UNKNOWN
                ? sdd.core.toolchain.Toolchain.detect(step.repoRoot())
                : settings.toolchain();
        // Stamped before anything runs: an npm test report is only believed when it was written
        // after this moment, since nothing stops a repo checking one in.
        OutputCompactor compactor =
                new OutputCompactor(step.repoRoot(), toolchain, settings.clock().instant());
        BuildTool build = toolchain == sdd.core.toolchain.Toolchain.NPM
                ? new NpmTool(step.repoRoot(), settings.nodeHome(), settings.gradleTimeout(),
                        settings.gradlePermits())
                : new GradleTool(step.repoRoot(), settings.javaHome(), settings.gradleTimeout(),
                        settings.gradleExtraArgs(), settings.gradlePermits(), settings.gradleHome());
        // Hoisted (rather than inlined into the Toolbox constructor) so appliedEdits() can be read
        // after the loop finishes — this one instance is reused across every cycle below, so its
        // edits accumulate over the whole attempt regardless of how many times loop.run() is called.
        // The TypeScript gate is available only where it makes sense and only if node is present;
        // FileTools fails open when it is absent, and records why.
        FileTools fileTools = new FileTools(new PathJail(step.repoRoot()),
                toolchain == sdd.core.toolchain.Toolchain.NPM
                        ? sdd.core.ts.TsSidecar.create(settings.nodeHome(), java.time.Duration.ofSeconds(30))
                        : java.util.Optional.empty());
        Toolbox toolbox = new Toolbox(fileTools, build, compactor, settings.singleTool());
        VerificationRunner verifier = new VerificationRunner(build, compactor, toolchain);
        AgentLoop loop = new AgentLoop(model, toolbox, settings.budget(), settings.contextSoftCap(),
                settings.clock());

        String workOrder = WorkOrder.build(jdbi, step) + (priorDigest.isBlank() ? "" : priorDigest);
        List<String> events = new ArrayList<>();
        List<String> transcript = new ArrayList<>();
        int verifyCycles = 0;
        long tokens = 0;
        boolean restarted = false;
        String lastVerification = "";

        while (true) {
            AgentOutcome outcome;
            try {
                outcome = loop.run(settings.systemPrompt(), workOrder, modelName,
                        settings.maxTokensPerCall());
            } catch (ModelException e) {
                throw e.withTokens(tokens + e.tokensSoFar());
            }
            events.addAll(outcome.events());
            transcript.addAll(outcome.transcript());
            tokens += outcome.tokens();

            switch (outcome.result()) {
                case DONE -> {
                    if (settings.verificationTasks().isEmpty()) {
                        events.add("verify: skipped — not locally verified (all verification tasks excluded)");
                        return outcome(StepResult.SUCCESS, outcome.summary(), events,
                                "not locally verified", tokens, transcript, fileTools);
                    }
                    VerificationRunner.Verdict verdict = verifyAll(verifier, settings.verificationTasks());
                    if (!verdict.passed() && verdict.infra()) {
                        events.add("verify: infra-classified failure — retrying once");
                        verdict = verifyAll(verifier, settings.verificationTasks());
                        if (!verdict.passed() && verdict.infra()) {
                            lastVerification = verdict.output();
                            return outcome(StepResult.INFRA, "infrastructure failure at the verify gate",
                                    events, lastVerification, tokens, transcript, fileTools);
                        }
                    }
                    lastVerification = verdict.output();
                    if (verdict.passed()) {
                        return outcome(StepResult.SUCCESS, outcome.summary(), events, lastVerification, tokens,
                                transcript, fileTools);
                    }
                    if (++verifyCycles >= MAX_VERIFY_CYCLES) {
                        return outcome(StepResult.VERIFY_FAILED, "verification failed", events, lastVerification,
                                tokens, transcript, fileTools);
                    }
                    workOrder = WorkOrder.build(jdbi, step)
                            + "\n\n## Verification failed — fix and finish again\n" + verdict.output();
                }
                case BLOCKED -> {
                    return outcome(StepResult.BLOCKED, outcome.summary(), events, lastVerification, tokens,
                            transcript, fileTools);
                }
                case CONTEXT_EXHAUSTED -> {
                    if (restarted) {
                        return outcome(StepResult.EXHAUSTED, "context exhausted", events, lastVerification, tokens,
                                transcript, fileTools);
                    }
                    restarted = true;
                    workOrder = WorkOrder.build(jdbi, step) + digest(outcome, lastVerification);
                }
                case BUDGET_TURNS, BUDGET_TIME, BUDGET_TOKENS -> {
                    return outcome(StepResult.BUDGET, outcome.summary(), events, lastVerification, tokens,
                            transcript, fileTools);
                }
                case MALFORMED -> {
                    return outcome(StepResult.MALFORMED, outcome.summary(), events, lastVerification, tokens,
                            transcript, fileTools);
                }
                case WEDGED -> {
                    return outcome(StepResult.WEDGED, outcome.summary(), events, lastVerification, tokens,
                            transcript, fileTools);
                }
                default -> throw new IllegalStateException("unhandled AgentResult: " + outcome.result());
            }
        }
    }

    private static VerificationRunner.Verdict verifyOnce(VerificationRunner verifier, String task) {
        try {
            return verifier.verify(task);
        } catch (RuntimeException e) {
            return new VerificationRunner.Verdict(false, "verification error: " + e.getMessage(), false);
        }
    }

    private static VerificationRunner.Verdict verifyAll(VerificationRunner verifier, List<String> tasks) {
        VerificationRunner.Verdict last = null;
        for (String task : tasks) {
            last = verifyOnce(verifier, task);
            if (!last.passed()) {
                return last;
            }
        }
        return last;
    }

    private static String digest(AgentOutcome outcome, String lastVerification) {
        return "\n\n## Previous attempt ran out of context after " + outcome.turns() + " turns\n"
                + "Your edits persist on disk. Re-read the files named in the sub-spec and continue.\n"
                + "Last build:\n" + (lastVerification.isEmpty() ? "none" : lastVerification);
    }

    private static StepOutcome outcome(StepResult result, String summary, List<String> events,
                                       String verification, long tokens, List<String> transcript,
                                       FileTools fileTools) {
        List<String> edits = fileTools.appliedEdits().stream().map(RepoStepRunner::editLine).toList();
        return new StepOutcome(result, summary, events, verification, tokens, transcript, edits);
    }

    private static String editLine(FileTools.AppliedEdit edit) {
        ObjectNode node = JSON.createObjectNode();
        node.put("path", edit.path());
        node.put("action", edit.action());
        node.put("searchLines", edit.searchLines());
        node.put("replaceLines", edit.replaceLines());
        try {
            return JSON.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // A tree built purely from primitives/strings never actually fails to serialize; this is
            // defense-in-depth so an edit line is never silently dropped.
            return "{}";
        }
    }
}
