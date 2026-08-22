package sdd.plan.approve;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * One SHA-256 over a whole directory, for a gate artifact that is no longer one file.
 *
 * <p>{@code Hashes.sha256} pins {@code spec.md} and {@code plan.md} by digesting each file's text.
 * A change directory has five or more, so the pin becomes a hash of a canonical listing —
 * {@code <relative path>\0<sha256 of bytes>\n} per file, sorted by path. That keeps the property
 * the pin exists for, which is detecting a human edit after approval, and generalises to any number
 * of files including ones added later.
 *
 * <p>Sorted by path rather than by directory-walk order, because a filesystem's enumeration order
 * is not a property of the change. Byte-determinism is the whole point: the same directory must
 * hash the same on every machine, exactly as the export it sits beside must render the same bytes
 * on every JVM.
 *
 * <p>{@code estate.yaml} is excluded by every caller, and has to be: the hash is written INTO it,
 * so including it would be a hash of a file containing its own hash.
 */
public final class ManifestHash {

    private ManifestHash() {
    }

    public static String of(Path directory, String... excludedFileNames) throws IOException {
        List<String> excluded = List.of(excludedFileNames);
        List<String> lines = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String relative = directory.relativize(file).toString().replace('\\', '/');
                if (excluded.contains(relative)) {
                    continue;
                }
                lines.add(relative + "\0" + Hashes.sha256(
                        new String(Files.readAllBytes(file), StandardCharsets.UTF_8)) + "\n");
            }
        }
        lines.sort(String::compareTo);
        return Hashes.sha256(String.join("", lines));
    }
}
