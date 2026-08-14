package sdd.plan.gen;

import org.jdbi.v3.core.Jdbi;
import sdd.core.kb.ContractEdges;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic execution order over the affected subgraph: Gradle edges order providers
 * before consumers; REST/Kafka provider->consumer pseudo-edges break ties (design: contract
 * provider-first); SCC cycles co-schedule as one unit (design M3). Ready units emit in
 * alphabetical order. A pseudo-edge deadlock appends the remainder alphabetically — the
 * Phase 3C-2 validator owns hard enforcement of a legal order.
 */
public final class ExecutionOrder {

    public record Unit(List<String> repos) {
        public Unit {
            repos = List.copyOf(repos);
        }
    }

    private ExecutionOrder() {
    }

    public static List<Unit> order(Jdbi jdbi, ImpactResult result) {
        Set<String> affected = new LinkedHashSet<>();
        for (AffectedRepo repo : result.affected()) {
            affected.add(repo.repo());
        }
        // collapse cycles into units keyed by their (sorted) first member
        Map<String, String> unitOf = new HashMap<>();
        Map<String, List<String>> members = new LinkedHashMap<>();
        for (String cycle : result.cycles()) {
            List<String> cycleMembers = List.of(cycle.split(" <-> "));
            String key = cycleMembers.get(0);
            for (String member : cycleMembers) {
                unitOf.put(member, key);
            }
            members.put(key, new ArrayList<>(cycleMembers));
        }
        for (String repo : affected) {
            String key = unitOf.computeIfAbsent(repo, k -> k);
            members.computeIfAbsent(key, k -> new ArrayList<>(List.of(repo)));
        }

        // edges: provider-unit -> consumer-unit (provider must come first)
        Map<String, Set<String>> consumersOf = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (String key : members.keySet()) {
            consumersOf.put(key, new LinkedHashSet<>());
            indegree.put(key, 0);
        }
        for (String[] edge : edges(jdbi, affected)) {
            String providerUnit = unitOf.get(edge[0]);
            String consumerUnit = unitOf.get(edge[1]);
            if (providerUnit.equals(consumerUnit)) {
                continue;
            }
            if (consumersOf.get(providerUnit).add(consumerUnit)) {
                indegree.merge(consumerUnit, 1, Integer::sum);
            }
        }

        TreeSet<String> ready = new TreeSet<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }
        List<Unit> order = new ArrayList<>();
        Set<String> emitted = new LinkedHashSet<>();
        while (!ready.isEmpty()) {
            String key = ready.pollFirst();
            emitted.add(key);
            order.add(new Unit(members.get(key).stream().sorted().toList()));
            for (String consumer : consumersOf.get(key)) {
                if (indegree.merge(consumer, -1, Integer::sum) == 0) {
                    ready.add(consumer);
                }
            }
        }
        // pseudo-edge deadlock: append whatever remains, alphabetically
        members.keySet().stream().filter(k -> !emitted.contains(k)).sorted()
                .forEach(k -> order.add(new Unit(members.get(k).stream().sorted().toList())));
        return order;
    }

    /** [provider, consumer] constraint edges among the given repos — shared with PlanValidator. */
    public static List<String[]> edges(Jdbi jdbi, Set<String> affected) {
        List<String[]> edges = new ArrayList<>();
        jdbi.useHandle(h -> h.createQuery("""
                        SELECT rt.name AS provider, rf.name AS consumer
                        FROM v_repo_dep_edge v
                        JOIN repo rf ON rf.id = v.from_repo_id
                        JOIN repo rt ON rt.id = v.to_repo_id
                        ORDER BY rt.name, rf.name""")
                .mapToMap().forEach(row -> edges.add(new String[]{
                        String.valueOf(row.get("provider")), String.valueOf(row.get("consumer"))})));
        for (ContractEdges.RestEdge edge : ContractEdges.rest(jdbi)) {
            edges.add(new String[]{edge.providerRepo(), edge.consumerRepo()});
        }
        for (ContractEdges.KafkaEdge edge : ContractEdges.kafka(jdbi)) {
            edges.add(new String[]{edge.producerRepo(), edge.consumerRepo()});
        }
        return edges.stream().filter(e -> affected.contains(e[0]) && affected.contains(e[1])).toList();
    }
}
