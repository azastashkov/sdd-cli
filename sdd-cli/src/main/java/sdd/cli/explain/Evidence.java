package sdd.cli.explain;

import sdd.core.kb.Provenance;

import java.util.List;

/**
 * The deterministic-fetch output: everything a narrator (Task 7) may draw on and everything a
 * human reader sees under {@code ## Evidence}. This is one object serving both roles by
 * construction — {@code EvidenceRenderer.render(Evidence)} (Task 6) is the single string that is
 * both the call-2 prompt and the printed audit trail, so the answer can never be grounded in a
 * fact the reader cannot also see.
 */
public record Evidence(Provenance provenance, RetrievalRequest request, List<Section> sections,
                        List<String> caveats) {
    public Evidence {
        sections = List.copyOf(sections);
        caveats = List.copyOf(caveats);
    }

    /** True when every section is empty — the signal to skip narration entirely (Task 8). */
    public boolean isEmpty() {
        return sections.stream().allMatch(s -> s.facts().isEmpty());
    }
}
