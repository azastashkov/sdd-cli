package sdd.core.retrieve;

import java.util.List;

public interface Retriever {
    List<Hit> search(String query, int limit);
}
