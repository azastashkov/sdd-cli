package sdd.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EstateSearchTest {
    @TempDir Path tmp;
    private EstateSearch search;

    private Path write(String repo, String rel, String body) throws Exception {
        Path file = tmp.resolve(repo).resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
        return file;
    }

    @BeforeEach
    void setUp() throws Exception {
        Map<String, Path> roots = new LinkedHashMap<>();
        // "aaa-noisy" sorts first and, under one global budget, would swallow it whole.
        StringBuilder noise = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            noise.append("tier.lvc.map line ").append(i).append('\n');
        }
        write("aaa-noisy", "src/Noise.java", noise.toString());
        write("zzz-quiet", "src/Quiet.java", "String key = \"tier.lvc.map\";\n");
        write("zzz-quiet", "db/schema.sql", "CREATE TABLE tier_lvc (id int);\n");
        write("zzz-quiet", "build/generated/Gen.java", "tier.lvc.map generated\n");
        for (String repo : new String[] {"aaa-noisy", "zzz-quiet"}) {
            roots.put(repo, tmp.resolve(repo));
        }
        search = new EstateSearch(new EstateJail(roots));
    }

    @Test
    void aNoisyRepoCannotStarveTheRest() {
        String out = search.search("tier\\.lvc\\.map", null, null);

        // The quiet repo's one hit survives 60 hits in the alphabetically-first repo...
        assertThat(out).contains("zzz-quiet/src/Quiet.java:1:");
        // ...and the noisy repo is capped rather than allowed to spend the whole budget.
        assertThat(out.lines().filter(l -> l.startsWith("aaa-noisy/")).count())
                .isEqualTo(EstateSearch.MAX_HITS_PER_REPO);
    }

    @Test
    void truncationIsNamedWithItsCountRatherThanImplied() {
        String out = search.search("tier\\.lvc\\.map", null, null);

        // "truncated in aaa-noisy" and "absent from aaa-noisy" must never render the same.
        assertThat(out).contains("(aaa-noisy: showing " + EstateSearch.MAX_HITS_PER_REPO
                + " of 60 matches — pass repo=aaa-noisy to see more)");
    }

    @Test
    void theRepoFilterNarrowsTheWalk() {
        String out = search.search("tier\\.lvc\\.map", "zzz-quiet", null);

        assertThat(out).contains("zzz-quiet/src/Quiet.java:1:").doesNotContain("aaa-noisy");
    }

    @Test
    void aPathGlobReachesFileTypesTheIndexHasNoConceptOf() {
        String out = search.search("tier_lvc", null, "**/*.sql");

        assertThat(out).contains("zzz-quiet/db/schema.sql:1:");
    }

    @Test
    void buildOutputIsNotSearched() {
        assertThat(search.search("tier\\.lvc\\.map", "zzz-quiet", null))
                .doesNotContain("build/generated");
    }

    @Test
    void anEmptyResultNamesTheScopeItSearched() {
        assertThat(search.search("nowhere-at-all", null, null))
                .isEqualTo("no matches in: aaa-noisy, zzz-quiet\n");
    }

    @Test
    void anUnknownRepoFilterIsRefusedRatherThanSilentlySearchingEverything() {
        assertThatThrownBy(() -> search.search("x", "typo-repo", null))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("unknown repo 'typo-repo'");
    }

    @Test
    void aSearchThatRunsOutOfTimeSaysSoRatherThanRunningForever() throws Exception {
        // A zero deadline expires before the first file, which is the same code path a real
        // long search takes. The point is that the caller is TOLD the answer is partial: a
        // silent partial result is a wrong answer, and an unbounded one is a hang.
        Map<String, Path> roots = new LinkedHashMap<>();
        roots.put("aaa-noisy", tmp.resolve("aaa-noisy"));
        roots.put("zzz-quiet", tmp.resolve("zzz-quiet"));
        EstateSearch bounded = new EstateSearch(new EstateJail(roots), java.time.Duration.ZERO);

        String out = bounded.search("tier\\.lvc\\.map", null, null);

        assertThat(out).contains("search stopped after 0s").contains("results are partial")
                .contains("narrow the regex");
    }

    @Test
    void aBadRegexSaysSoInsteadOfReturningNothing() {
        assertThatThrownBy(() -> search.search("[unclosed", null, null))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("bad regex");
    }
}
