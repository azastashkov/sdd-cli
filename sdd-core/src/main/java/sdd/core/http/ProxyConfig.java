package sdd.core.http;

import java.util.List;

/**
 * The transport-neutral counterpart to {@code sdd.core.config.AtlassianProxy}, structurally
 * identical to it — same three fields, same exact-or-dotted-suffix case-insensitive
 * {@code noProxy} match rule (see {@link HttpClients#proxySelector(ProxyConfig)}) — just moved
 * into {@code sdd.core.http} so a model endpoint's proxy settings can build the same
 * {@link java.net.ProxySelector} the Atlassian one does, through the same code, rather than a
 * second implementation of the bypass rule that could quietly drift from this one.
 */
public record ProxyConfig(String host, int port, List<String> noProxy) {
    public ProxyConfig {
        noProxy = List.copyOf(noProxy);
    }
}
