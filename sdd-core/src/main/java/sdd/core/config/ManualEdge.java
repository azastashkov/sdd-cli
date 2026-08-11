package sdd.core.config;

public record ManualEdge(String clientRepo, String httpMethod, String path, String providerRepo) {}
