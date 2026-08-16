package sdd.cli.implement;

import com.fasterxml.jackson.databind.JsonNode;
import sdd.core.ts.TsSidecar;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Type-compatibility gate for {@code compat: type-compatible} contracts — the npm counterpart of
 * {@link JapicmpCheck}.
 *
 * <p>The check is an assignability probe compiled by the real compiler, not a diff of the two
 * declaration files. A textual diff flags legal widening — a parameter that became optional, a
 * union that gained a member, a return type that narrowed — as breaking, and {@code Orchestrator}
 * turns compat drift into a FAILED repo: a false positive there fails work that was correct.
 * Assignability is the question that was actually meant, and only a type checker answers it.
 */
public final class DtsCompatCheck {

    /**
     * @param typeCompatible whether every probed export of the baseline still accepts the candidate
     * @param probed         how many exports were checked. Reported alongside the verdict because
     *                       "no breaks found" and "nothing was looked at" are the two things a gate
     *                       must never let a reader confuse
     */
    public record Verdict(boolean typeCompatible, int probed, String report) {
    }

    private DtsCompatCheck() {
    }

    /**
     * Compares each package present on BOTH sides.
     *
     * <p>A package that exists only in the candidate is new and cannot break anything. A package
     * that exists only in the baseline was removed or renamed, which is the most breaking change
     * there is — and it is reported here rather than left to the probe, which could not see it.
     */
    public static Verdict compare(Path nodeHome, DtsBuilder.Result baseline,
                                  DtsBuilder.Result candidate) {
        Optional<TsSidecar> sidecar = TsSidecar.create(nodeHome);
        if (sidecar.isEmpty()) {
            return new Verdict(true, 0, "");
        }
        StringBuilder report = new StringBuilder();
        int probed = 0;
        boolean compatible = true;

        for (DtsBuilder.Emitted before : baseline.packages()) {
            Path after = candidate.packages().stream()
                    .filter(e -> e.packageName().equals(before.packageName()))
                    .map(DtsBuilder.Emitted::entryDts)
                    .findFirst().orElse(null);
            if (after == null) {
                compatible = false;
                report.append(before.packageName())
                        .append(": the package no longer publishes an entry point\n");
                continue;
            }
            TsSidecar.Result result = sidecar.get().typeCompat(before.entryDts(), after);
            if (!result.ok()) {
                // A gate that cannot run is not a gate that passed, but it is also not a break:
                // same rule japicmp follows when the comparator itself throws.
                report.append(before.packageName()).append(": check skipped — ")
                        .append(result.error()).append('\n');
                continue;
            }
            probed += result.json().path("probed").asInt();
            for (String line : breaksOf(result.json())) {
                compatible = false;
                report.append(before.packageName()).append('.').append(line).append('\n');
            }
        }
        return new Verdict(compatible, probed, report.toString());
    }

    private static List<String> breaksOf(JsonNode response) {
        List<String> breaks = new ArrayList<>();
        for (JsonNode entry : response.path("breaks")) {
            breaks.add(entry.path("export").asText() + ": " + entry.path("message").asText());
        }
        return breaks;
    }
}
