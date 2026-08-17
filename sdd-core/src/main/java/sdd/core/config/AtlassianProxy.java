package sdd.core.config;

import java.util.List;

/**
 * {@code atlassian.proxy} — a forward proxy fronting the corporate network's egress, common
 * wherever Jira/Confluence/Bitbucket Data Center live. {@code noProxy} carries the entries that
 * must bypass it (typically the Atlassian hosts themselves, which are usually reachable directly
 * even when the proxy is required for everything else) — see
 * {@code sdd.core.http.HttpClients} for the exact-or-dotted-suffix, case-insensitive match rule.
 */
public record AtlassianProxy(String host, int port, List<String> noProxy) {
    public AtlassianProxy {
        noProxy = List.copyOf(noProxy);
    }
}
