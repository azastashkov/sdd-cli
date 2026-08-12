package sdd.agent.run;

import java.util.List;
import java.util.Objects;

/** A plan.json interface contract as the work order embeds it. */
public record ContractRef(String id, String kind, String provider, List<String> consumers, String body) {
    public ContractRef {
        Objects.requireNonNull(id);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(provider);
        consumers = List.copyOf(consumers);
        Objects.requireNonNull(body);
    }
}
