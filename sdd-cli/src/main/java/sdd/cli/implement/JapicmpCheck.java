package sdd.cli.implement;

import japicmp.cmp.JApiCmpArchive;
import japicmp.cmp.JarArchiveComparator;
import japicmp.cmp.JarArchiveComparatorOptions;
import japicmp.model.JApiClass;

import java.nio.file.Path;
import java.util.List;

/** Binary-compatibility gate for compat: binary-compatible contracts (design line 62). */
public final class JapicmpCheck {
    public record Verdict(boolean binaryCompatible, String report) {
    }

    private JapicmpCheck() {
    }

    public static Verdict compare(Path baselineJar, Path candidateJar) {
        JarArchiveComparatorOptions options = new JarArchiveComparatorOptions();
        JarArchiveComparator comparator = new JarArchiveComparator(options);
        List<JApiClass> classes = comparator.compare(
                new JApiCmpArchive(baselineJar.toFile(), "baseline"),
                new JApiCmpArchive(candidateJar.toFile(), "candidate"));
        StringBuilder report = new StringBuilder();
        boolean compatible = true;
        for (JApiClass jApiClass : classes) {
            if (!jApiClass.isBinaryCompatible()) {
                compatible = false;
                report.append(jApiClass.getFullyQualifiedName()).append(": ");
                // getCompatibilityChanges() returns wrapper objects; map to the change type for a
                // readable report.
                jApiClass.getCompatibilityChanges().forEach(change ->
                        report.append(change.getType()).append(' '));
                report.append('\n');
            }
        }
        return new Verdict(compatible, report.toString());
    }
}
