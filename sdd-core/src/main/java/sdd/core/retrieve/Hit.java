package sdd.core.retrieve;

public record Hit(String identifier, String fqcn, long moduleId, double score) {}
