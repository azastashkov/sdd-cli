package sdd.core.kb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The estate here reproduces the shape measured on the real one (see
 * {@code docs/measurements/2026-08-20-graph-evidence/}): a shared constant referenced by two
 * configuration classes, each of which wires a listener that does NOT reference the constant
 * itself. That shape is why the walk is undirected — an inbound-only traversal sees the wiring and
 * never the components.
 */
class KbRefGraphTest {
    @TempDir Path ws;
    private Database db;

    private static final String CHANNELS = "com.acme.messaging.Channels";
    private static final String AUTH_CONFIG = "com.acme.auth.AuthWebConfig";
    private static final String AUTH_LISTENER = "com.acme.auth.TierInvalidationListener";
    private static final String CANDLES_CONFIG = "com.acme.candles.CandlesConfig";
    private static final String CANDLES_LISTENER = "com.acme.candles.TierInvalidationListener";
    private static final String FAR = "com.acme.web.EntitlementService";
    private static final String LOGGER = "org.slf4j.Logger";

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-auth','/w/1','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/2','LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','SERVICE')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2,':','LIBRARY')");
            // Inserted in an order that is neither alphabetical nor traversal order, so an
            // "ordered by repo, path, fqcn" assertion can only pass via a real ORDER BY.
            type(h, 2, CHANNELS);
            type(h, 1, AUTH_LISTENER);
            type(h, 1, AUTH_CONFIG);
            type(h, 1, FAR);
            type(h, 1, CANDLES_CONFIG);
            type(h, 1, CANDLES_LISTENER);
            // Channels <-inbound- config -outbound-> listener, in both services.
            ref(h, AUTH_CONFIG, CHANNELS, "IMPORT", 1);
            ref(h, AUTH_CONFIG, AUTH_LISTENER, "TYPE", 2);
            ref(h, CANDLES_CONFIG, CHANNELS, "IMPORT", 1);
            ref(h, CANDLES_CONFIG, CANDLES_LISTENER, "TYPE", 1);
            // One hop further out, so depth 2 has something to exclude at depth 1.
            ref(h, AUTH_LISTENER, FAR, "CALL", 3);
            // An UNINDEXED target: referenced by everything, must never become a path.
            ref(h, AUTH_CONFIG, LOGGER, "IMPORT", 1);
            ref(h, CANDLES_CONFIG, LOGGER, "IMPORT", 1);
        });
    }

    private static void type(org.jdbi.v3.core.Handle h, int moduleId, String fqcn) {
        h.execute("INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path) VALUES (?,?,'CLASS',0,?)",
                moduleId, fqcn, "src/main/java/" + fqcn.replace('.', '/') + ".java");
    }

    private static void ref(org.jdbi.v3.core.Handle h, String from, String to, String kind, int count) {
        h.execute("INSERT INTO type_ref(from_type_id, to_fqcn, ref_kind, ref_count) "
                + "VALUES ((SELECT id FROM java_type WHERE fqcn=?),?,?,?)", from, to, kind, count);
    }

    @Test
    void inboundNamesTheTypesThatReferenceOneType() {
        assertThat(KbRefGraph.inbound(db.jdbi(), CHANNELS))
                .extracting(KbRefGraph.Edge::fqcn, KbRefGraph.Edge::repo, KbRefGraph.Edge::refKind)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(AUTH_CONFIG, "svc-auth", "IMPORT"),
                        org.assertj.core.groups.Tuple.tuple(CANDLES_CONFIG, "svc-auth", "IMPORT"));
    }

    @Test
    void outboundSkipsTargetsThatAreNotThemselvesIndexed() {
        // AuthWebConfig references Channels, TierInvalidationListener and org.slf4j.Logger. The
        // logger has no java_type row, so it is a leaf: nameable, never walkable. This filter is
        // what makes an undirected walk safe, and it replaces the direction restriction that a
        // measured case proved wrong.
        assertThat(KbRefGraph.outbound(db.jdbi(), AUTH_CONFIG))
                .extracting(KbRefGraph.Edge::fqcn)
                // Ordered by repo name first, so lib-core's Channels precedes svc-auth's
                // listener -- proving the ORDER BY is real rather than reproducing insertion order.
                .containsExactly(CHANNELS, AUTH_LISTENER)
                .doesNotContain(LOGGER);
    }

    @Test
    void anUndirectedWalkReachesTheListenerThatNeverReferencesTheAnchor() {
        KbRefGraph.Neighbourhood n = KbRefGraph.expand(db.jdbi(), Set.of(CHANNELS), 2);

        assertThat(n.distanceOf(CHANNELS)).contains(0);
        // Depth 1 is the wiring...
        assertThat(n.distanceOf(AUTH_CONFIG)).contains(1);
        assertThat(n.distanceOf(CANDLES_CONFIG)).contains(1);
        // ...and depth 2 is the components, which an inbound-only walk would never see because
        // neither listener references Channels.
        assertThat(n.distanceOf(AUTH_LISTENER)).contains(2);
        assertThat(n.distanceOf(CANDLES_LISTENER)).contains(2);
        // Beyond the bound.
        assertThat(n.distanceOf(FAR)).isEmpty();
        assertThat(n.distanceOf(LOGGER)).isEmpty();
    }

    @Test
    void theDepthBoundIsHonoured() {
        assertThat(KbRefGraph.expand(db.jdbi(), Set.of(CHANNELS), 1).distanceByFqcn().keySet())
                .containsExactlyInAnyOrder(CHANNELS, AUTH_CONFIG, CANDLES_CONFIG);
        assertThat(KbRefGraph.expand(db.jdbi(), Set.of(CHANNELS), 3).distanceOf(FAR)).contains(3);
    }

    @Test
    void shortestDistanceWinsWhateverOrderTheWalkFindsIt() {
        // AUTH_LISTENER is 1 from AUTH_CONFIG and 2 from CHANNELS. Anchoring on both must record 1.
        KbRefGraph.Neighbourhood n =
                KbRefGraph.expand(db.jdbi(), Set.of(CHANNELS, AUTH_CONFIG), 2);
        assertThat(n.distanceOf(AUTH_CONFIG)).contains(0);
        assertThat(n.distanceOf(AUTH_LISTENER)).contains(1);
    }

    @Test
    void anEmptyAnchorSetReturnsAnEmptyNeighbourhoodWithoutQuerying() {
        // Not an optimisation. This is what lets PlanDrafter prove that a spec anchoring nothing
        // composes byte-identically to the pre-graph build.
        Database closed = Database.open(ws);
        closed.close();
        KbRefGraph.Neighbourhood n = KbRefGraph.expand(closed.jdbi(), Set.of(), 2);
        assertThat(n.distanceByFqcn()).isEmpty();
        assertThat(n.suppressions()).isEmpty();
    }

    @Test
    void repeatedExpansionsAreIdentical() {
        Map<String, Integer> first = KbRefGraph.expand(db.jdbi(), Set.of(CHANNELS), 2).distanceByFqcn();
        for (int i = 0; i < 3; i++) {
            assertThat(KbRefGraph.expand(db.jdbi(), Set.of(CHANNELS), 2).distanceByFqcn())
                    .isEqualTo(first);
        }
        assertThat(List.copyOf(first.keySet())).isEqualTo(List.copyOf(
                KbRefGraph.expand(db.jdbi(), Set.of(CHANNELS), 2).distanceByFqcn().keySet()));
    }
}
