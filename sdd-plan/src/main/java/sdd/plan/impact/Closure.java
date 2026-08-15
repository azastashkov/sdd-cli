package sdd.plan.impact;

import org.jdbi.v3.core.Jdbi;
import sdd.core.kb.ContractEdges;
import sdd.core.kb.KbStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Impact stage C (design): deterministic closure. Full transitive expansion over internal
 * Gradle edges reversed (provider -> consumers); api_usage evidence annotates code-change-likely
 * vs bump/rebuild-only but NEVER limits traversal (M1). BOM_MANAGED pulling edges also pull the
 * declaration-site repo (M2, heuristic + loud warning). REST/Kafka contracts add one hop,
 * provider -> consumer, marked PENDING_CONTRACT, no further recursion. SCCs among affected
 * repos are reported co-scheduled (M3).
 */
public final class Closure {

    public record Expansion(List<AffectedRepo> added, List<String> cycles, List<String> warnings) {
        public Expansion {
            added = List.copyOf(added);
            cycles = List.copyOf(cycles);
            warnings = List.copyOf(warnings);
        }
    }

    private record RepoEdge(String consumer, String provider, String mode) {
    }

    private Closure() {
    }

    public static Expansion expand(Jdbi jdbi, Set<String> rootRepos) {
        List<RepoEdge> edges = jdbi.withHandle(h -> h.createQuery("""
                        SELECT DISTINCT rf.name AS consumer, rt.name AS provider, v.mode AS mode
                        FROM v_repo_dep_edge v
                        JOIN repo rf ON rf.id = v.from_repo_id
                        JOIN repo rt ON rt.id = v.to_repo_id
                        ORDER BY rf.name, rt.name""")
                .map((rs, ctx) -> new RepoEdge(rs.getString("consumer"), rs.getString("provider"),
                        rs.getString("mode"))).list());
        Map<String, List<RepoEdge>> byProvider = new HashMap<>();
        for (RepoEdge edge : edges) {
            byProvider.computeIfAbsent(edge.provider(), k -> new ArrayList<>()).add(edge);
        }

        List<String> warnings = new ArrayList<>();
        Map<String, AffectedRepo> added = new LinkedHashMap<>();
        Set<String> affected = new LinkedHashSet<>(rootRepos);
        Set<String> bomConsumersSeen = new HashSet<>();
        expandBuildEdges(jdbi, byProvider, rootRepos, affected, added, bomConsumersSeen, warnings);

        // Contract edges add exactly one hop and never recurse into further contracts — but a repo
        // reached that way is affected like any other, so its own build-edge consumers must still be
        // expanded. Seeding a second build-edge pass from just those repos preserves the one-hop
        // rule (contracts() is still called exactly once) while restoring transitivity: a backend
        // whose endpoint changed reaches the SDK that calls it, and then everything built on that
        // SDK. Without this the blast radius stops one repo short, silently.
        Set<String> viaContracts = contracts(jdbi, affected, added, warnings);
        expandBuildEdges(jdbi, byProvider, viaContracts, affected, added, bomConsumersSeen, warnings);
        List<String> cycles = cycles(edges, affected, warnings);
        statusWarnings(jdbi, affected, warnings);
        return new Expansion(new ArrayList<>(added.values()), cycles, warnings);
    }

    /**
     * Transitive expansion over build edges, provider -> consumer, from the given seeds. Shared by
     * the initial pass over the changed repos and the follow-up pass over repos a contract edge
     * pulled in.
     */
    private static void expandBuildEdges(Jdbi jdbi, Map<String, List<RepoEdge>> byProvider,
                                         Set<String> seeds, Set<String> affected,
                                         Map<String, AffectedRepo> added, Set<String> bomConsumersSeen,
                                         List<String> warnings) {
        Deque<String> queue = new ArrayDeque<>(new TreeSet<>(seeds));
        while (!queue.isEmpty()) {
            String provider = queue.removeFirst();
            for (RepoEdge edge : byProvider.getOrDefault(provider, List.of())) {
                String reason = "depends on " + provider + " (" + edge.mode() + ")";
                if (affected.add(edge.consumer())) {
                    String annotation = usesApiOf(jdbi, edge.consumer(), provider)
                            ? "CODE_CHANGE_LIKELY" : "BUMP_REBUILD_ONLY";
                    added.put(edge.consumer(), new AffectedRepo(edge.consumer(), "dependent",
                            annotation, List.of(), List.of(reason)));
                    queue.addLast(edge.consumer());
                } else if (added.containsKey(edge.consumer())) {
                    AffectedRepo existing = added.get(edge.consumer());
                    List<String> reasons = new ArrayList<>(existing.reasons());
                    if (!reasons.contains(reason)) {
                        reasons.add(reason);
                        added.put(edge.consumer(), new AffectedRepo(existing.repo(), existing.role(),
                                existing.annotation(), existing.covers(), reasons));
                    }
                }
                if ("BOM_MANAGED".equals(edge.mode()) && bomConsumersSeen.add(edge.consumer())) {
                    pullBomSites(jdbi, edge.consumer(), added, affected, warnings);
                }
            }
        }
    }

    private static boolean usesApiOf(Jdbi jdbi, String consumerRepo, String providerRepo) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT count(*) FROM api_usage u
                        JOIN module mc ON mc.id = u.from_module_id
                        JOIN repo rc ON rc.id = mc.repo_id
                        JOIN module mp ON mp.id = u.target_module_id
                        JOIN repo rp ON rp.id = mp.repo_id
                        WHERE rc.name = :c AND rp.name = :p""")
                .bind("c", consumerRepo).bind("p", providerRepo).mapTo(Integer.class).one()) > 0;
    }

    private static void pullBomSites(Jdbi jdbi, String consumerRepo, Map<String, AffectedRepo> added,
                                     Set<String> affected, List<String> warnings) {
        List<String> sites = jdbi.withHandle(h -> h.createQuery("""
                        SELECT DISTINCT rt.name FROM dep_edge e
                        JOIN module mf ON mf.id = e.from_module_id
                        JOIN repo rf ON rf.id = mf.repo_id
                        JOIN module mt ON mt.id = e.to_module_id
                        JOIN repo rt ON rt.id = mt.repo_id
                        WHERE rf.name = :c AND e.is_internal = 1
                          AND (lower(e.to_name) LIKE '%bom%' OR lower(e.to_name) LIKE '%platform%')
                        ORDER BY rt.name""")
                .bind("c", consumerRepo).mapTo(String.class).list());
        if (sites.isEmpty()) {
            warnings.add("BOM_MANAGED edge from " + consumerRepo
                    + ": declaration site not identifiable — verify the managing BOM manually");
            return;
        }
        for (String site : sites) {
            if (affected.add(site)) {
                added.put(site, new AffectedRepo(site, "bom-site", "BOM_DECLARATION_SITE",
                        List.of(), List.of("manages versions consumed by " + consumerRepo)));
            }
        }
    }

    /** @return the repos this pass newly added, so their own build-edge consumers can be expanded */
    private static Set<String> contracts(Jdbi jdbi, Set<String> affected, Map<String, AffectedRepo> added,
                                  List<String> warnings) {
        record Contract(String consumerRepo, String reason) {
        }
        List<Contract> contracts = new ArrayList<>();
        for (ContractEdges.RestEdge edge : ContractEdges.rest(jdbi)) {
            if (affected.contains(edge.providerRepo())) {
                contracts.add(new Contract(edge.consumerRepo(),
                        "calls " + edge.verb() + " " + edge.normPath() + " on "
                                + edge.providerRepo() + " (" + edge.confidence() + ")"));
            }
        }
        for (ContractEdges.KafkaEdge edge : ContractEdges.kafka(jdbi)) {
            if (affected.contains(edge.producerRepo())) {
                contracts.add(new Contract(edge.consumerRepo(),
                        "consumes topic " + edge.topic() + " produced by " + edge.producerRepo()));
            }
        }
        Set<String> newlyAdded = new LinkedHashSet<>();
        for (Contract contract : contracts) {
            if (affected.add(contract.consumerRepo())) {
                newlyAdded.add(contract.consumerRepo());
                added.put(contract.consumerRepo(), new AffectedRepo(contract.consumerRepo(),
                        "contract", "PENDING_CONTRACT", List.of(), List.of(contract.reason())));
            } else if (added.containsKey(contract.consumerRepo())) {
                AffectedRepo existing = added.get(contract.consumerRepo());
                List<String> reasons = new ArrayList<>(existing.reasons());
                if (!reasons.contains(contract.reason())) {
                    reasons.add(contract.reason());
                    added.put(existing.repo(), new AffectedRepo(existing.repo(), existing.role(),
                            existing.annotation(), existing.covers(), reasons));
                }
            }
        }
        return newlyAdded;
    }

    /** Iterative Tarjan over the induced consumer->provider graph of affected repos. */
    private static List<String> cycles(List<RepoEdge> edges, Set<String> affected, List<String> warnings) {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (String repo : affected) {
            graph.put(repo, new ArrayList<>());
        }
        for (RepoEdge edge : edges) {
            if (affected.contains(edge.consumer()) && affected.contains(edge.provider())) {
                graph.get(edge.consumer()).add(edge.provider());
            }
        }
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Set<String> onStack = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        List<List<String>> sccs = new ArrayList<>();
        int[] counter = {0};
        record Frame(String node, int childIndex) {
        }
        for (String start : graph.keySet()) {
            if (index.containsKey(start)) {
                continue;
            }
            Deque<Frame> frames = new ArrayDeque<>();
            frames.push(new Frame(start, 0));
            index.put(start, counter[0]);
            low.put(start, counter[0]);
            counter[0]++;
            stack.push(start);
            onStack.add(start);
            while (!frames.isEmpty()) {
                Frame frame = frames.pop();
                List<String> children = graph.get(frame.node());
                int i = frame.childIndex();
                boolean descended = false;
                while (i < children.size()) {
                    String child = children.get(i);
                    i++;
                    if (!index.containsKey(child)) {
                        frames.push(new Frame(frame.node(), i));
                        frames.push(new Frame(child, 0));
                        index.put(child, counter[0]);
                        low.put(child, counter[0]);
                        counter[0]++;
                        stack.push(child);
                        onStack.add(child);
                        descended = true;
                        break;
                    } else if (onStack.contains(child)) {
                        low.put(frame.node(), Math.min(low.get(frame.node()), index.get(child)));
                    }
                }
                if (descended) {
                    continue;
                }
                if (low.get(frame.node()).equals(index.get(frame.node()))) {
                    List<String> scc = new ArrayList<>();
                    String popped;
                    do {
                        popped = stack.pop();
                        onStack.remove(popped);
                        scc.add(popped);
                    } while (!popped.equals(frame.node()));
                    if (scc.size() > 1) {
                        sccs.add(scc);
                    }
                }
                if (!frames.isEmpty()) {
                    Frame parent = frames.peek();
                    low.put(parent.node(), Math.min(low.get(parent.node()), low.get(frame.node())));
                }
            }
        }
        List<String> cycleStrings = new ArrayList<>();
        for (List<String> scc : sccs) {
            List<String> sorted = new ArrayList<>(new TreeSet<>(scc));
            cycleStrings.add(String.join(" <-> ", sorted));
            warnings.add("dependency cycle among affected repos (co-scheduled as one unit): "
                    + String.join(" <-> ", sorted));
        }
        return cycleStrings;
    }

    private static void statusWarnings(Jdbi jdbi, Set<String> affected, List<String> warnings) {
        warnings.addAll(KbStatus.warnings(jdbi, affected));
    }
}
