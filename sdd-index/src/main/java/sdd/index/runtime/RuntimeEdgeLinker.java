package sdd.index.runtime;

import org.jdbi.v3.core.Jdbi;
import sdd.core.config.RuntimeEdge;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the {@code runtime_edges} a human declared in {@code sdd.yml} into graph edges.
 *
 * <p>A remote counts as mapped once ANY declaration names it, not only one whose host is the repo
 * holding the manifest. The two are routinely different: an ingress serves the static fallback
 * manifest from the backend repo while the application that loads the bundles lives elsewhere, and
 * keying the check on the file's location would report a remote as unmapped after it had been
 * mapped.
 *
 * <p>Requiring the declaration is the whole design. A host's manifest says it loads a bundle from
 * {@code /mfe/a/trading-mfe-a.js}; which repo BUILDS that bundle is decided by a bundler config the
 * indexer does not read, so matching it to a repo by filename resemblance would be a guess recorded
 * as a fact. Every remote left unmapped is reported by the curation report instead, which is a
 * question a human can answer once.
 */
public final class RuntimeEdgeLinker {

    /** @param unmapped remotes with no declared owning repo, for the curation report */
    public record Report(int linked, List<String> unmapped, List<String> warnings) {
        public Report {
            unmapped = List.copyOf(unmapped);
            warnings = List.copyOf(warnings);
        }
    }

    private RuntimeEdgeLinker() {
    }

    public static Report link(Jdbi jdbi, List<RuntimeEdge> declared) {
        List<String> warnings = new ArrayList<>();
        int linked = jdbi.inTransaction(h -> {
            h.execute("DELETE FROM runtime_edge");
            int count = 0;
            for (RuntimeEdge edge : declared) {
                Long host = repoId(h, edge.hostRepo());
                Long module = repoId(h, edge.moduleRepo());
                if (host == null || module == null) {
                    warnings.add("runtime_edges names " + (host == null ? edge.hostRepo() : edge.moduleRepo())
                            + ", which is not a repo in the knowledge base");
                    continue;
                }
                h.createUpdate("""
                                INSERT INTO runtime_edge(host_repo_id, module_repo_id, remote_name, resolution)
                                VALUES (:h, :m, :n, 'CONFIGURED')
                                ON CONFLICT(host_repo_id, module_repo_id, remote_name) DO NOTHING""")
                        .bind("h", host).bind("m", module).bind("n", edge.remote()).execute();
                count++;
            }
            return count;
        });
        List<String> unmapped = jdbi.withHandle(h -> h.createQuery("""
                        SELECT r.name || ' declares remote ''' || rr.name || ''' -> ' || rr.url
                               || ' (' || rr.source_file || ')'
                        FROM runtime_remote rr JOIN repo r ON r.id = rr.repo_id
                        WHERE NOT EXISTS(SELECT 1 FROM runtime_edge re WHERE re.remote_name = rr.name)
                        ORDER BY r.name, rr.name""")
                .mapTo(String.class).list());
        return new Report(linked, unmapped, warnings);
    }

    private static Long repoId(org.jdbi.v3.core.Handle h, String repo) {
        return h.createQuery("SELECT id FROM repo WHERE name = :n")
                .bind("n", repo).mapTo(Long.class).findOne().orElse(null);
    }
}
