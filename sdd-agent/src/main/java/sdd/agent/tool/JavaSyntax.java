package sdd.agent.tool;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;

import java.util.Optional;

/** Parses a Java source string for syntax errors — the gate apply_edit runs before keeping a .java edit. */
public final class JavaSyntax {

    private JavaSyntax() {
    }

    public static Optional<String> firstError(String source) {
        try {
            StaticJavaParser.parse(source);
            return Optional.empty();
        } catch (ParseProblemException e) {
            return Optional.of(e.getProblems().isEmpty()
                    ? "syntax error" : e.getProblems().get(0).getMessage());
        }
    }
}
