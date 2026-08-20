package sdd.agent.tool;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;

import java.util.Optional;

/**
 * Parses a Java source string for syntax errors — the gate {@code apply_edit} runs before keeping a
 * {@code .java} edit.
 *
 * <p><b>The language level is not a detail.</b> This used to call {@code StaticJavaParser.parse}
 * with no configuration, which parses at JavaParser's default level, and the estate is Java 21. So
 * the gate rejected valid modern source as a syntax error and reverted the edit. Measured on a real
 * run (estate11 SPEC-203-v1, trading-core): {@code apply_edit} was rejected <b>45 times out of 108
 * calls</b>, 37 of them "Record Declarations are not supported", the rest text blocks and
 * {@code instanceof} patterns — <b>42 of that run's 301 turns</b> spent writing a correct edit,
 * having it reverted, and writing it again. The indexer had this right from the start
 * ({@code SourceParser} sets {@code BLEEDING_EDGE}); this gate was the only
 * {@code StaticJavaParser} caller in the codebase and the only one never configured.
 *
 * <p>{@code BLEEDING_EDGE} rather than a pinned version, matching {@code SourceParser}: the gate's
 * job is "can this be parsed at all", and being stricter than the compiler the repo actually uses
 * fails in the one direction that costs an agent its budget. A construct the repo's real
 * {@code --release} rejects is caught by the build, which is the gate immediately after this one.
 *
 * <p>A fresh {@link JavaParser} per call, deliberately. {@code JavaParser} is not thread-safe and
 * {@code Orchestrator} runs repos concurrently on virtual threads, so a shared instance — or
 * {@code StaticJavaParser}'s mutable static configuration — would be a data race for the sake of
 * an allocation that costs nothing next to the parse itself.
 */
public final class JavaSyntax {

    private JavaSyntax() {
    }

    public static Optional<String> firstError(String source) {
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);
        ParseResult<CompilationUnit> result = new JavaParser(config).parse(source);
        if (result.isSuccessful()) {
            return Optional.empty();
        }
        return Optional.of(result.getProblems().isEmpty()
                ? "syntax error" : result.getProblems().get(0).getMessage());
    }
}
