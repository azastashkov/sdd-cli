package sdd.plan.spec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Passthrough adapter: the ref is a canonical spec markdown file. */
public final class MarkdownSpecSource implements SpecSource {
    @Override
    public NormalizedSpec load(String ref) {
        try {
            return SpecParser.parse(Files.readString(Path.of(ref)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
