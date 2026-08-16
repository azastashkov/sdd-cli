package sdd.index.store;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import sdd.core.config.ManualEdge;
import sdd.core.route.Routes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class RestMatcher {
    public record Report(int high, int medium, int low, int manual, List<String> warnings) {}

    private static final Pattern ABSOLUTE_URL = Pattern.compile("^[a-z][a-z0-9+.-]*://.*");

    private record Client(long id, long moduleId, String repo, String kind, String verb,
                          String norm, String targetHint) {}
    private record Endpoint(long id, String repo, String verb, String norm, String springAppName) {}

    private RestMatcher() {}

    public static Report match(Jdbi jdbi, List<ManualEdge> manualEdges) {
        List<String> warnings = new ArrayList<>();
        int[] counts = jdbi.inTransaction(h -> {
            h.execute("DELETE FROM rest_call_edge");
            List<Client> clients = h.createQuery("""
                            SELECT c.id, c.module_id, r.name AS repo, c.kind, c.http_method,
                                   c.norm_path, c.target_hint, c.uri_template
                            FROM rest_client c
                            JOIN module m ON m.id = c.module_id JOIN repo r ON r.id = m.repo_id
                            WHERE c.norm_path IS NOT NULL""")
                    .map((rs, ctx) -> {
                        String uri = rs.getString("uri_template");
                        if (uri != null && ABSOLUTE_URL.matcher(uri).matches()) {
                            return null;    // binding note #1: absolute URLs never match by norm_path
                        }
                        return new Client(rs.getLong("id"), rs.getLong("module_id"),
                                rs.getString("repo"), rs.getString("kind"), rs.getString("http_method"),
                                rs.getString("norm_path"), rs.getString("target_hint"));
                    }).list().stream().filter(c -> c != null).toList();
            List<Endpoint> endpoints = h.createQuery("""
                            SELECT e.id, r.name AS repo, e.http_method, e.norm_path, m.spring_app_name
                            FROM rest_endpoint e
                            JOIN module m ON m.id = e.module_id JOIN repo r ON r.id = m.repo_id
                            WHERE e.norm_path IS NOT NULL""")
                    .map((rs, ctx) -> new Endpoint(rs.getLong("id"), rs.getString("repo"),
                            rs.getString("http_method"), rs.getString("norm_path"),
                            rs.getString("spring_app_name"))).list();

            int high = 0;
            int medium = 0;
            int low = 0;
            for (Client c : clients) {
                List<Endpoint> loose = endpoints.stream()
                        .filter(e -> verbsCompatible(c.verb(), e.verb())
                                && templatesMatch(c.norm(), e.norm())).toList();
                if (loose.isEmpty()) {
                    continue;   // unmatched — curation report material
                }
                List<Endpoint> candidates = preferExact(loose, Endpoint::norm, c.norm());
                boolean exact = candidates.size() < loose.size();
                List<Endpoint> named = c.kind().equals("FEIGN") && c.targetHint() != null
                        ? candidates.stream().filter(e -> c.targetHint()
                                .equalsIgnoreCase(e.springAppName())).toList()
                        : List.of();
                if (!named.isEmpty()) {
                    for (Endpoint e : named) {
                        insertEdge(h, c.id(), e.id(), "HIGH", "FEIGN_NAME_PATH");
                        high++;
                    }
                } else if (candidates.size() == 1) {
                    // Still MEDIUM. Narrowing says WHICH endpoint, not that the client necessarily
                    // reaches this service — a browser talks to one origin and an ingress fans it
                    // out — so the reason changes and the confidence does not.
                    insertEdge(h, c.id(), candidates.get(0).id(), "MEDIUM",
                            exact ? "EXACT_PATH" : "UNIQUE_PATH");
                    medium++;
                } else {
                    for (Endpoint e : candidates) {
                        insertEdge(h, c.id(), e.id(), "LOW", "AMBIGUOUS");
                        low++;
                    }
                }
            }

            int manual = 0;
            for (ManualEdge edge : manualEdges) {
                String norm = Routes.normalize(edge.path());
                // Exact-first here too, and this half is the point of the tier: without it a
                // human could not correct an ambiguous match at all, because a manual edge naming
                // /api/candles/{}/symbols selected BOTH that endpoint and /api/candles/{}/{} —
                // the very ambiguity it was written to resolve.
                List<Long> clientIds = preferExact(clients.stream()
                        .filter(c -> c.repo().equals(edge.clientRepo())
                                && verbsCompatible(c.verb(), edge.httpMethod())
                                && templatesMatch(c.norm(), norm)).toList(), Client::norm, norm)
                        .stream().map(Client::id).toList();
                List<Long> endpointIds = preferExact(endpoints.stream()
                        .filter(e -> e.repo().equals(edge.providerRepo())
                                && verbsCompatible(edge.httpMethod(), e.verb())
                                && templatesMatch(norm, e.norm())).toList(), Endpoint::norm, norm)
                        .stream().map(Endpoint::id).toList();
                if (clientIds.isEmpty() || endpointIds.isEmpty()) {
                    warnings.add("manual edge unmatched: " + edge);
                    continue;
                }
                for (long cid : clientIds) {
                    for (long eid : endpointIds) {
                        List<Map<String, Object>> replaced = h.createQuery(
                                "SELECT confidence, matched_by FROM rest_call_edge WHERE client_id=:c AND endpoint_id=:e")
                                .bind("c", cid).bind("e", eid).mapToMap().list();
                        for (Map<String, Object> row : replaced) {
                            if ("MANUAL".equals(row.get("matched_by"))) {
                                manual--;
                                continue;
                            }
                            switch (String.valueOf(row.get("confidence"))) {
                                case "HIGH" -> high--;
                                case "MEDIUM" -> medium--;
                                case "LOW" -> low--;
                                default -> { }
                            }
                        }
                        h.createUpdate("DELETE FROM rest_call_edge WHERE client_id=:c AND endpoint_id=:e")
                                .bind("c", cid).bind("e", eid).execute();
                        insertEdge(h, cid, eid, "HIGH", "MANUAL");
                        manual++;
                    }
                }
            }
            return new int[]{high, medium, low, manual};
        });
        return new Report(counts[0], counts[1], counts[2], counts[3], List.copyOf(warnings));
    }

    /**
     * Narrows a fuzzy candidate set to the rows whose template matches EXACTLY, when any do.
     *
     * <p>{@code templatesMatch} treats {@code {}} as a wildcard on both sides, so
     * {@code /api/candles/{}/symbols} matches {@code /api/candles/{}/{}} as well as itself, and a
     * call with a perfectly specific path lands AMBIGUOUS against an endpoint it plainly is not.
     * Exact-first is the same shape {@code KbEntities.resolveEndpoint} already applies, for the
     * same reason and after the same symptom.
     *
     * <p>Only a narrowing: when nothing matches exactly the original set comes back untouched, so
     * a genuinely templated client keeps behaving exactly as it did.
     */
    private static <T> List<T> preferExact(List<T> candidates, java.util.function.Function<T, String> norm,
                                           String target) {
        List<T> exact = candidates.stream().filter(c -> target.equals(norm.apply(c))).toList();
        return exact.isEmpty() ? candidates : exact;
    }

    private static void insertEdge(Handle h, long clientId, long endpointId,
                                   String confidence, String matchedBy) {
        h.createUpdate("INSERT INTO rest_call_edge(client_id, endpoint_id, confidence, matched_by) "
                        + "VALUES (:c, :e, :conf, :by)")
                .bind("c", clientId).bind("e", endpointId)
                .bind("conf", confidence).bind("by", matchedBy).execute();
    }

    static boolean templatesMatch(String clientNorm, String endpointNorm) {
        return Routes.templatesMatch(clientNorm, endpointNorm);
    }

    static boolean verbsCompatible(String clientVerb, String endpointVerb) {
        return Routes.verbsCompatible(clientVerb, endpointVerb);
    }
}
