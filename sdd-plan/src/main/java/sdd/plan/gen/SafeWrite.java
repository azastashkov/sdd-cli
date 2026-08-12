package sdd.plan.gen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Overwrite guard for human-edited gate artifacts (Phase 3C-2 entry checklist #2): an
 * existing target is preserved as <name>.bak before the new content lands, so an accidental
 * regeneration never silently destroys review edits.
 */
public final class SafeWrite {

    private SafeWrite() {
    }

    public static Path writeWithBackup(Path target, String content) {
        try {
            Path backup = null;
            if (Files.exists(target)) {
                backup = target.resolveSibling(target.getFileName() + ".bak");
                Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(target, content);
            return backup;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
