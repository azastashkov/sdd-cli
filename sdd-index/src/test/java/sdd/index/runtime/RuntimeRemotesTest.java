package sdd.index.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.config.RuntimeEdge;
import sdd.core.db.Database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeRemotesTest {
    @TempDir Path ws;

    private Path manifest(String rel, String json) throws Exception {
        Path file = ws.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
        return file;
    }

    /** The exact shape a real ingress serves. */
    private static final String REMOTES = """
            {"remotes":[
              {"name":"mfe_a","url":"/mfe/a/trading-mfe-a.js","enabled":true},
              {"name":"mfe_b","url":"/mfe/b/trading-mfe-b.js","enabled":false}
            ]}
            """;

    @Test
    void aManifestIsReadVerbatimWithNoInferenceAboutWhoBuildsIt() throws Exception {
        manifest("docker/ingress/remotes.json", REMOTES);

        List<RuntimeRemotes.Remote> remotes = RuntimeRemotes.read(ws);

        assertThat(remotes).extracting(RuntimeRemotes.Remote::name).containsExactly("mfe_a", "mfe_b");
        assertThat(remotes).extracting(RuntimeRemotes.Remote::url)
                .containsExactly("/mfe/a/trading-mfe-a.js", "/mfe/b/trading-mfe-b.js");
        assertThat(remotes).extracting(RuntimeRemotes.Remote::enabled).containsExactly(true, false);
        // The file is named so a report can point at it rather than at the repo in general.
        assertThat(remotes).allSatisfy(r ->
                assertThat(r.sourceFile()).isEqualTo("docker/ingress/remotes.json"));
    }

    @Test
    void aFileNamedRemotesJsonThatIsNotAManifestIsIgnored() throws Exception {
        manifest("remotes.json", "{\"something\":\"else\"}");
        manifest("other/remotes.json", "not json at all");

        assertThat(RuntimeRemotes.read(ws)).isEmpty();
    }

    @Test
    void vendoredCopiesAreNeverRead() throws Exception {
        manifest("node_modules/pkg/remotes.json", REMOTES);
        manifest("dist/remotes.json", REMOTES);

        assertThat(RuntimeRemotes.read(ws)).isEmpty();
    }

    @Test
    void aRemoteWithNoDeclaredOwnerIsReportedRatherThanGuessedFromItsFilename() {
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(id,name,path,kind) VALUES (1,'host','/w/host','SERVICE')");
                h.execute("INSERT INTO repo(id,name,path,kind) VALUES (2,'trading-mfe-a','/w/a','SERVICE')");
                h.execute("INSERT INTO runtime_remote(repo_id,name,url,enabled,source_file) "
                        + "VALUES (1,'mfe_a','/mfe/a/trading-mfe-a.js',1,'docker/remotes.json')");
            });

            RuntimeEdgeLinker.Report report = RuntimeEdgeLinker.link(db.jdbi(), List.of());

            // The URL contains the repo's name. Matching on that resemblance would put a guess in
            // the knowledge base wearing the same clothes as a parsed fact, so it is refused and
            // surfaced as a question instead.
            assertThat(report.linked()).isZero();
            assertThat(report.unmapped()).singleElement().satisfies(u ->
                    assertThat(u).contains("host").contains("mfe_a").contains("trading-mfe-a.js"));
        }
    }

    @Test
    void aDeclaredMappingBecomesAnEdgeAndClearsTheReport() {
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(id,name,path,kind) VALUES (1,'host','/w/host','SERVICE')");
                h.execute("INSERT INTO repo(id,name,path,kind) VALUES (2,'trading-mfe-a','/w/a','SERVICE')");
                h.execute("INSERT INTO runtime_remote(repo_id,name,url,enabled,source_file) "
                        + "VALUES (1,'mfe_a','/mfe/a/trading-mfe-a.js',1,'docker/remotes.json')");
            });

            RuntimeEdgeLinker.Report report = RuntimeEdgeLinker.link(db.jdbi(),
                    List.of(new RuntimeEdge("host", "mfe_a", "trading-mfe-a")));

            assertThat(report.linked()).isEqualTo(1);
            assertThat(report.unmapped()).isEmpty();
            String resolution = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT resolution FROM runtime_edge").mapTo(String.class).one());
            // Recorded as configured, so a reader can tell this came from a human and not a parser.
            assertThat(resolution).isEqualTo("CONFIGURED");
        }
    }

    @Test
    void aMappingNamingAnUnknownRepoIsWarnedAboutRatherThanSilentlyDropped() {
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(id,name,path,kind) VALUES (1,'host','/w/host','SERVICE')"));

            RuntimeEdgeLinker.Report report = RuntimeEdgeLinker.link(db.jdbi(),
                    List.of(new RuntimeEdge("host", "mfe_a", "no-such-repo")));

            assertThat(report.linked()).isZero();
            assertThat(report.warnings()).anySatisfy(w -> assertThat(w).contains("no-such-repo"));
        }
    }
}
