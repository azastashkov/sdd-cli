package sdd.cli.implement;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.ts.TsSidecar;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The type-compatibility gate against the real compiler.
 *
 * <p>The cases that matter are the ones a textual diff of the two {@code .d.ts} files would get
 * WRONG: widening is legal and must pass, narrowing is breaking and must fail. Both directions are
 * pinned, because a gate that only ever says "different" is a gate that fails correct work.
 */
@Tag("node-it")
class DtsCompatCheckIT {
    @TempDir Path repo;
    @TempDir Path out;

    @BeforeEach
    void requireNode() {
        Assumptions.assumeTrue(TsSidecar.create(null).isPresent(), "node not available");
    }

    private void write(String rel, String content) throws Exception {
        Path file = repo.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void manifest() throws Exception {
        write("package.json", """
                {"name":"@acme/lib","version":"1.0.0","types":"./dist/index.d.ts"}
                """);
        write("tsconfig.json", "{\"compilerOptions\":{\"outDir\":\"dist\",\"rootDir\":\"src\"}}");
    }

    /** Emits the baseline, rewrites the source, emits the candidate, compares — the exact shape
     *  the orchestrator uses, where both sides come from ONE working tree at two moments. */
    private DtsCompatCheck.Verdict compare(String before, String after) throws Exception {
        manifest();
        write("src/index.ts", before);
        DtsBuilder builder = new DtsBuilder(null);
        DtsBuilder.Result baseline = builder.build(repo, out.resolve("baseline"));
        assertThat(baseline.ok()).as("baseline emit: %s", baseline.log()).isTrue();

        write("src/index.ts", after);
        DtsBuilder.Result candidate = builder.build(repo, out.resolve("candidate"));
        assertThat(candidate.ok()).as("candidate emit: %s", candidate.log()).isTrue();

        return DtsCompatCheck.compare(null, baseline, candidate);
    }

    private static final String BASE = """
            export interface Tick { price: number; symbol: string; }
            export function load(id: string): Promise<Tick> {
              return Promise.resolve({ price: 1, symbol: id });
            }
            """;

    @Test
    void anUnchangedSurfaceIsCompatibleAndSaysHowMuchItLookedAt() throws Exception {
        DtsCompatCheck.Verdict verdict = compare(BASE, BASE);

        assertThat(verdict.typeCompatible()).as("%s", verdict.report()).isTrue();
        // "No breaks found" and "nothing was looked at" must be distinguishable, or an emit that
        // silently produced nothing reads exactly like a clean bill of health.
        assertThat(verdict.probed()).isGreaterThan(0);
    }

    @Test
    void addingAnOptionalMemberIsNotABreak() throws Exception {
        DtsCompatCheck.Verdict verdict = compare(BASE, """
                export interface Tick { price: number; symbol: string; venue?: string; }
                export function load(id: string): Promise<Tick> {
                  return Promise.resolve({ price: 1, symbol: id });
                }
                """);

        // A textual diff of the two .d.ts files reports this as changed, and Orchestrator turns
        // compat drift into a FAILED repo — so a diff-based gate fails correct work here.
        assertThat(verdict.typeCompatible()).as("%s", verdict.report()).isTrue();
    }

    @Test
    void wideningAParameterIsNotABreak() throws Exception {
        DtsCompatCheck.Verdict verdict = compare(BASE, """
                export interface Tick { price: number; symbol: string; }
                export function load(id: string | number): Promise<Tick> {
                  return Promise.resolve({ price: 1, symbol: String(id) });
                }
                """);

        assertThat(verdict.typeCompatible()).as("%s", verdict.report()).isTrue();
    }

    @Test
    void removingAMemberIsABreak() throws Exception {
        DtsCompatCheck.Verdict verdict = compare(BASE, """
                export interface Tick { price: number; }
                export function load(id: string): Promise<Tick> {
                  return Promise.resolve({ price: 1 });
                }
                """);

        assertThat(verdict.typeCompatible()).isFalse();
        assertThat(verdict.report()).contains("Tick");
    }

    @Test
    void removingAnExportEntirelyIsABreak() throws Exception {
        DtsCompatCheck.Verdict verdict = compare(BASE, """
                export interface Tick { price: number; symbol: string; }
                """);

        // Absence needs no separate rule: the probe references an export the candidate no longer
        // has, and the compiler says so.
        assertThat(verdict.typeCompatible()).isFalse();
        assertThat(verdict.report()).contains("load");
    }

    @Test
    void narrowingAParameterIsABreak() throws Exception {
        DtsCompatCheck.Verdict verdict = compare("""
                export interface Tick { price: number; }
                export function load(id: string | number): Promise<Tick> {
                  return Promise.resolve({ price: 1 });
                }
                """, """
                export interface Tick { price: number; }
                export function load(id: string): Promise<Tick> {
                  return Promise.resolve({ price: 1 });
                }
                """);

        assertThat(verdict.typeCompatible()).isFalse();
        assertThat(verdict.report()).contains("load");
    }

    @Test
    void aTypeFromTheStandardLibraryIsResolvedRatherThanReportedBroken() throws Exception {
        // Every other sidecar mode runs noLib because it reads written type text. This one cannot:
        // without lib.es5, Promise and Date are unknown and EVERY member compares as broken.
        DtsCompatCheck.Verdict verdict = compare("""
                export interface Bar { at: Date; values: Map<string, number>; }
                export function all(): Promise<Bar[]> { return Promise.resolve([]); }
                """, """
                export interface Bar { at: Date; values: Map<string, number>; }
                export function all(): Promise<Bar[]> { return Promise.resolve([]); }
                """);

        assertThat(verdict.typeCompatible()).as("%s", verdict.report()).isTrue();
        assertThat(verdict.probed()).isGreaterThan(0);
    }

    @Test
    void aRepoWithNoPackageIsReportedAsNotBuiltRatherThanAsCompatible() throws Exception {
        write("src/main/java/A.java", "public class A {}");

        DtsBuilder.Result result = new DtsBuilder(null).build(repo, out.resolve("baseline"));

        assertThat(result.ok()).isFalse();
        assertThat(result.log()).contains("no package.json");
    }
}
