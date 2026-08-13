package sdd.agent.run;

import org.jdbi.v3.core.Jdbi;
import sdd.agent.loop.AgentLoop;
import sdd.agent.loop.AgentOutcome;
import sdd.agent.loop.AgentResult;
import sdd.agent.tool.FileTools;
import sdd.agent.tool.GradleTool;
import sdd.agent.tool.PathJail;
import sdd.agent.tool.Toolbox;
import sdd.core.llm.ChatModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives ONE attempt for one repo step (design Component 3): build a lean work order, run the
 * agent loop, verify independently on done, retry a verify-fail (≤2 cycles), and restart once on
 * context exhaustion with a machine digest. Multi-attempt escalation + git are Phase 4C's (they
 * need run state and git); this returns a typed StepOutcome for 4C to act on.
 */
public final class RepoStepRunner {
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
        OutputCompactor compactor = new OutputCompactor(step.repoRoot());
        GradleTool gradle = new GradleTool(step.repoRoot(), settings.javaHome(), settings.gradleTimeout(), settings.gradleExtraArgs());
        Toolbox toolbox = new Toolbox(new FileTools(new PathJail(step.repoRoot())), gradle, compactor);
        VerificationRunner verifier = new VerificationRunner(gradle, compactor);
        AgentLoop loop = new AgentLoop(model, toolbox, settings.budget(), settings.contextSoftCap(),
                settings.clock());

        String workOrder = WorkOrder.build(jdbi, step) + (priorDigest.isBlank() ? "" : priorDigest);
        List<String> events = new ArrayList<>();
        int verifyCycles = 0;
        long tokens = 0;
        boolean restarted = false;
        String lastVerification = "";

        while (true) {
            AgentOutcome outcome = loop.run(settings.systemPrompt(), workOrder, modelName,
                    settings.maxTokensPerCall());
            events.addAll(outcome.events());
            tokens += outcome.tokens();

            switch (outcome.result()) {
                case DONE -> {
                    VerificationRunner.Verdict verdict = verifyOnce(verifier, settings.verificationTask());
                    if (!verdict.passed() && verdict.infra()) {
                        events.add("verify: infra-classified failure — retrying once");
                        verdict = verifyOnce(verifier, settings.verificationTask());
                        if (!verdict.passed() && verdict.infra()) {
                            lastVerification = verdict.output();
                            return outcome(StepResult.INFRA, "infrastructure failure at the verify gate",
                                    events, lastVerification, tokens);
                        }
                    }
                    lastVerification = verdict.output();
                    if (verdict.passed()) {
                        return outcome(StepResult.SUCCESS, outcome.summary(), events, lastVerification, tokens);
                    }
                    if (++verifyCycles >= MAX_VERIFY_CYCLES) {
                        return outcome(StepResult.VERIFY_FAILED, "verification failed", events, lastVerification, tokens);
                    }
                    workOrder = WorkOrder.build(jdbi, step)
                            + "\n\n## Verification failed — fix and finish again\n" + verdict.output();
                }
                case BLOCKED -> {
                    return outcome(StepResult.BLOCKED, outcome.summary(), events, lastVerification, tokens);
                }
                case CONTEXT_EXHAUSTED -> {
                    if (restarted) {
                        return outcome(StepResult.EXHAUSTED, "context exhausted", events, lastVerification, tokens);
                    }
                    restarted = true;
                    workOrder = WorkOrder.build(jdbi, step) + digest(outcome, lastVerification);
                }
                case BUDGET_TURNS, BUDGET_TIME, BUDGET_TOKENS -> {
                    return outcome(StepResult.BUDGET, outcome.summary(), events, lastVerification, tokens);
                }
                case MALFORMED -> {
                    return outcome(StepResult.MALFORMED, outcome.summary(), events, lastVerification, tokens);
                }
                case WEDGED -> {
                    return outcome(StepResult.WEDGED, outcome.summary(), events, lastVerification, tokens);
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

    private static String digest(AgentOutcome outcome, String lastVerification) {
        return "\n\n## Previous attempt ran out of context after " + outcome.turns() + " turns\n"
                + "Your edits persist on disk. Re-read the files named in the sub-spec and continue.\n"
                + "Last build:\n" + (lastVerification.isEmpty() ? "none" : lastVerification);
    }

    private static StepOutcome outcome(StepResult result, String summary, List<String> events,
                                       String verification, long tokens) {
        return new StepOutcome(result, summary, events, verification, tokens);
    }
}
