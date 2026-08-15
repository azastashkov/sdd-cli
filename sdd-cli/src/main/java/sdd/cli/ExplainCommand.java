package sdd.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import sdd.cli.explain.Answer;
import sdd.cli.explain.AnswerAudit;
import sdd.cli.explain.AnswerNarrator;
import sdd.cli.explain.Evidence;
import sdd.cli.explain.EvidenceCollector;
import sdd.cli.explain.EvidenceRenderer;
import sdd.cli.explain.ExplainReport;
import sdd.cli.explain.QuestionInterpreter;
import sdd.cli.explain.RetrievalRequest;
import sdd.core.config.ConfigException;
import sdd.core.config.ConfigLoader;
import sdd.core.config.ModelEndpoint;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.core.llm.ChatModel;
import sdd.core.llm.HttpChatModel;
import sdd.core.retrieve.FtsRetriever;
import sdd.core.retrieve.Retriever;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code sdd explain <question>}: interpret -&gt; deterministic fetch -&gt; narrate (Tasks 1-7),
 * wired into a command. Read-only like {@code graph}/{@code plan}'s validate path -- it never
 * writes anything to the workspace unless {@code --out} is given, and the
 * {@code Files.exists(".sdd/index.db")} guard runs before {@link Database#open}, since opening the
 * database is itself a side effect that would otherwise create the KB while this command reports
 * that none exists ({@link GraphCommand}'s idiom, copied verbatim).
 *
 * <p>Every degradation rung short of "no usable input" still exits 0 with the full
 * {@code ## Evidence} section printed -- a human is never left stack-traced when a credential is
 * missing or a model call fails. {@link #buildModel} is the single place that turns a failure to
 * construct a usable {@link ChatModel} (missing {@code sdd.yml}, missing {@code models.planner},
 * or {@code HttpChatModel}'s constructor rejecting a deferred {@code api_key} failure) into a
 * fallback reason string, so every caller downstream of it degrades the same way regardless of
 * which of those three things went wrong. Call 1 ({@link QuestionInterpreter#interpret}) and call 2
 * ({@link AnswerNarrator#narrate}) each independently catch their own model failures internally
 * (a bad response, {@code finish_reason=length}, a thrown {@code ModelException}) and return a
 * fallback/unavailable result rather than throwing -- this class only has to handle the one
 * failure mode neither of them can: never having had a model to call in the first place.
 */
@Command(name = "explain", description = "Answer a question about the estate from the knowledge base")
public final class ExplainCommand implements Callable<Integer> {
    @Option(names = "--workspace", description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

    @Option(names = "--out", description = "Write the explanation to a file instead of stdout")
    Path out;

    @Parameters(index = "0", arity = "0..*", description = "The question to answer")
    List<String> question;

    @Spec CommandSpec spec;

    ChatModel plannerForTest;   // test seam — mirrors PlanCommand's injectable ChatModel

    private static final String MODEL_KEY = "planner";

    @Override
    public Integer call() {
        PrintWriter outWriter = spec.commandLine().getOut();
        PrintWriter errWriter = spec.commandLine().getErr();

        String questionText = question == null ? "" : String.join(" ", question).strip();
        if (questionText.isEmpty()) {
            errWriter.println("error: missing required parameter: <question>");
            return 1;
        }

        if (!Files.exists(workspace.resolve(".sdd/index.db"))) {
            errWriter.println("error: knowledge base is empty — run sdd index first");
            return 1;
        }
        try (Database db = Database.open(workspace)) {
            Integer repoCount = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT count(*) FROM repo").mapTo(Integer.class).one());
            if (repoCount == 0) {
                errWriter.println("error: knowledge base is empty — run sdd index first");
                return 1;
            }

            ModelSetup setup = buildModel();

            // Built once and shared: call 1's candidate-symbol vocabulary (QuestionInterpreter)
            // and the deterministic fetch (EvidenceCollector) both search the same KB via the
            // same Retriever -- a second instance would just be a redundant FtsRetriever(db.jdbi()).
            Retriever retriever = new FtsRetriever(db.jdbi());

            RetrievalRequest request = setup.model() != null
                    ? QuestionInterpreter.interpret(db.jdbi(), retriever, questionText, setup.model(),
                            setup.endpoint().model(), setup.endpoint().maxTokens())
                    : QuestionInterpreter.fallback(db.jdbi(), questionText, setup.failureReason());

            Evidence evidence = EvidenceCollector.collect(db.jdbi(), retriever, request);

            Optional<Answer> answer = Optional.empty();
            List<String> auditNotes = List.of();
            // Zero facts: call 2 is never made at all -- a narrator handed an empty evidence
            // bundle is precisely where invention happens, so this is not "call it and discard".
            if (!evidence.isEmpty()) {
                if (setup.model() != null) {
                    Answer narrated = AnswerNarrator.narrate(evidence, setup.model(),
                            setup.endpoint().model(), setup.endpoint().maxTokens());
                    answer = Optional.of(narrated);
                    if (!narrated.unavailable()) {
                        auditNotes = AnswerAudit.check(narrated.prose(), EvidenceRenderer.render(evidence), db.jdbi());
                    }
                } else {
                    // Same "no model was ever available" cause as call 1's fallback above, but
                    // there is no call-2-specific fallback to run -- report it the same way
                    // AnswerNarrator.unavailable(reason) would.
                    answer = Optional.of(new Answer("",
                            List.of("answer unavailable: " + setup.failureReason()), true));
                }
            }

            String report = ExplainReport.render(evidence, answer, auditNotes);
            if (out != null) {
                try {
                    Files.writeString(out, report);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                outWriter.println("explanation written: " + out);
            } else {
                outWriter.print(report);
                outWriter.flush();
            }
            return 0;
        } catch (RuntimeException e) {
            errWriter.println("error: " + e.getMessage());
            return 1;
        }
    }

    private record ModelSetup(ChatModel model, ModelEndpoint endpoint, String failureReason) {
    }

    /**
     * The single place a missing/unusable model collapses into a fallback reason string, so every
     * caller (call 1's fallback, call 2's synthesized "unavailable" answer) degrades identically
     * regardless of which of three distinct failures produced it: {@code sdd.yml} missing or
     * unparseable ({@link ConfigLoader#load} throws {@link ConfigException}), {@code models.planner}
     * absent from it, or the endpoint's {@code api_key} referencing an unset environment variable
     * ({@link HttpChatModel}'s constructor throws {@link ConfigException} for the deferred
     * {@code apiKeyError} -- see {@link ModelEndpoint}'s Javadoc on why that check is deferred this
     * far downstream rather than raised eagerly at config-load time).
     */
    private ModelSetup buildModel() {
        try {
            SddConfig config = ConfigLoader.load(workspace);
            ModelEndpoint endpoint = config.models().get(MODEL_KEY);
            if (endpoint == null) {
                throw new ConfigException("models." + MODEL_KEY + " is required");
            }
            ChatModel model = plannerForTest != null ? plannerForTest : new HttpChatModel(endpoint);
            return new ModelSetup(model, endpoint, null);
        } catch (RuntimeException e) {
            return new ModelSetup(null, null, e.getMessage());
        }
    }
}
