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
        // Real jars reference classes outside themselves (superclasses/interfaces from third-party
        // libraries, e.g. groovy.lang.Closure) that aren't in the baseline or candidate archive and
        // aren't on this process's classpath either. Without this, japicmp throws JApiCmpException
        // ("Could not load ... try the option '--ignore-missing-classes'") instead of comparing — this
        // is the programmatic equivalent of that CLI flag, and is what a live real-estate run hit.
        options.getIgnoreMissingClasses().setIgnoreAllMissingClasses(true);
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
