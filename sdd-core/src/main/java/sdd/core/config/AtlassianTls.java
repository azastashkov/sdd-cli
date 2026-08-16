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
 * <p>{@code password}/{@code passwordError} follow the same deferred-credential idiom as a site's
 * {@code token}/{@code tokenError} (see {@link AtlassianSite}), copied from
 * {@code ConfigLoader.endpoint}'s {@code api_key} handling. An earlier draft resolved
 * {@code truststore_password} eagerly, reasoning that a shared-by-every-site credential has no
 * unfair read-only command to protect the way a single site's token does — that reasoning was
 * wrong: eager resolution meant an unset {@code ${CORP_TRUSTSTORE_PASSWORD}} failed
 * {@code ConfigLoader.load} for EVERY command, including {@code sdd index}/{@code status}/
 * {@code clean}, none of which ever open an Atlassian connection. That is exactly the failure the
 * idiom exists to prevent. {@code password} is null and {@code passwordError} carries the message
 * when the {@code ${VAR}} is unset; {@code sdd.core.http.HttpClients} raises it, byte-identical, at
 * the point it is about to actually open the truststore.
 */
public record AtlassianTls(Path truststore, String password, String passwordError) {}
