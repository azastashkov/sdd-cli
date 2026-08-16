package sdd.core.config;

import java.nio.file.Path;

/**
 * {@code atlassian.tls} — the corporate CA chain for self-hosted Jira/Confluence/Bitbucket, which
 * almost never sit behind a certificate the JDK's bundled `cacerts` already trusts.
 *
 * <p>{@code truststore} is resolved (env vars substituted) but NOT checked for existence here —
 * {@code ConfigLoader} only ever produces a {@code Path}, exactly like {@code node_home}. Existence
 * is checked once, at the point something is about to actually open the file
 * ({@code sdd.core.http.HttpClients}), and a missing file is a hard error there rather than a
 * quiet fallback to the JDK default: a typo in this path must not silently change which trust
 * anchors are in play on a closed network where that is the whole point of configuring it.
 *
 * <p>{@code password} is resolved eagerly, unlike a site's {@code token} (see
 * {@link AtlassianSite}): it has no companion {@code *Error} field because, unlike a token, a
 * missing truststore password blocks the WHOLE {@code atlassian:} block (every site shares one
 * truststore) rather than a single command path — so there is no read-only command it would be
 * unfair to block, and deferring it would only hide a broken TLS setup until the first network
 * call instead of at `sdd doctor`'s config-load step.
 */
public record AtlassianTls(Path truststore, String password) {}
