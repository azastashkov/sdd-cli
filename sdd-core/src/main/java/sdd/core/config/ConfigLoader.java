package sdd.core.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import sdd.core.http.TlsConfig;
import sdd.core.llm.WireFormat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConfigLoader {
    private static final Pattern ENV_REF = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");
    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final double DEFAULT_TEMPERATURE = 0.15;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(600);
    private static final Duration DEFAULT_ATLASSIAN_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_FOLLOW_DEPTH = 1;
    private static final int DEFAULT_MAX_PAGES = 20;
    private static final int DEFAULT_MAX_LINKED_ISSUES = 10;

    private ConfigLoader() {}

    public static SddConfig load(Path workspace) {
        return load(workspace, System::getenv);
    }

    public static SddConfig load(Path workspace, Function<String, String> env) {
        Path file = workspace.resolve("sdd.yml");
        if (!Files.isRegularFile(file)) {
            throw new ConfigException("sdd.yml not found in " + workspace);
        }
        Map<String, Object> root = parseYaml(file);

        rejectUnimplementedRetrieval(root, env);

        Map<String, ModelEndpoint> models = new LinkedHashMap<>();
        Object modelsNode = root.get("models");
        if (modelsNode instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                models.put(String.valueOf(e.getKey()),
                        endpoint(String.valueOf(e.getKey()), e.getValue(), env));
            }
        }
        for (String required : List.of("planner", "coder")) {
            if (!models.containsKey(required)) {
                throw new ConfigException("models." + required + " is required");
            }
        }

        Map<Integer, Path> jdkHomes = new LinkedHashMap<>();
        if (root.get("jdk_homes") instanceof Map<?, ?> jm) {
            for (Map.Entry<?, ?> e : jm.entrySet()) {
                jdkHomes.put(parseInt("jdk_homes key", String.valueOf(e.getKey())),
                        Path.of(str(e.getValue(), env, "jdk_homes")));
            }
        }

        Path nodeHome = root.get("node_home") == null
                ? null : Path.of(str(root.get("node_home"), env, "node_home"));
        Path gradleHome = root.get("gradle_home") == null
                ? null : Path.of(str(root.get("gradle_home"), env, "gradle_home"));

        Object excludesNode = root.get("excludes");
        List<String> excludes;
        if (excludesNode == null) {
            excludes = List.of();
        } else if (excludesNode instanceof List<?> l) {
            excludes = l.stream().map(String::valueOf).toList();
        } else {
            throw new ConfigException("excludes must be a list, got: " + excludesNode);
        }

        Map<String, String> artifactOverrides = new LinkedHashMap<>();
        if (root.get("artifact_overrides") instanceof Map<?, ?> am) {
            for (Map.Entry<?, ?> e : am.entrySet()) {
                artifactOverrides.put(String.valueOf(e.getKey()),
                        str(e.getValue(), env, "artifact_overrides"));
            }
        }

        List<ManualEdge> manualEdges = new ArrayList<>();
        Object manualEdgesNode = root.get("manual_edges");
        if (manualEdgesNode == null) {
            // empty
        } else if (manualEdgesNode instanceof List<?> edges) {
            for (int i = 0; i < edges.size(); i++) {
                if (!(edges.get(i) instanceof Map<?, ?> e)) {
                    throw new ConfigException("manual_edges[" + i + "] must be a mapping");
                }
                manualEdges.add(new ManualEdge(
                        requiredEdgeKey(e, "client_repo", i, env),
                        requiredEdgeKey(e, "http_method", i, env).toUpperCase(java.util.Locale.ROOT),
                        requiredEdgeKey(e, "path", i, env),
                        requiredEdgeKey(e, "provider_repo", i, env)));
            }
        } else {
            throw new ConfigException("manual_edges must be a list, got: " + manualEdgesNode);
        }

        List<RuntimeEdge> runtimeEdges = new java.util.ArrayList<>();
        Object runtimeEdgesNode = root.get("runtime_edges");
        if (runtimeEdgesNode == null) {
            // empty
        } else if (runtimeEdgesNode instanceof List<?> edges) {
            for (int i = 0; i < edges.size(); i++) {
                if (!(edges.get(i) instanceof Map<?, ?> e)) {
                    throw new ConfigException("runtime_edges[" + i + "] must be a mapping");
                }
                runtimeEdges.add(new RuntimeEdge(
                        requiredEdgeKey(e, "host_repo", i, env),
                        requiredEdgeKey(e, "remote", i, env),
                        requiredEdgeKey(e, "module_repo", i, env)));
            }
        } else {
            throw new ConfigException("runtime_edges must be a list, got: " + runtimeEdgesNode);
        }

        RunSettings run = RunSettings.defaults();
        Object runNode = root.get("run");
        if (runNode instanceof Map<?, ?> rm) {
            // parseInt/parseLong take (String where, String value) — see the max_tokens/timeout_seconds
            // pattern in this file; wrap raw node values with String.valueOf.
            int gradleWorkers = rm.get("gradle_workers") != null
                    ? parseInt("run.gradle_workers", String.valueOf(rm.get("gradle_workers"))) : run.gradleWorkers();
            int modelConcurrency = rm.get("model_concurrency") != null
                    ? parseInt("run.model_concurrency", String.valueOf(rm.get("model_concurrency"))) : run.modelConcurrency();
            long tokenBudget = rm.get("token_budget") != null
                    ? parseLong("run.token_budget", String.valueOf(rm.get("token_budget"))) : run.tokenBudget();
            int agentTurns = rm.get("agent_turns") != null
                    ? parseInt("run.agent_turns", String.valueOf(rm.get("agent_turns"))) : run.agentTurns();
            long agentTokens = rm.get("agent_tokens") != null
                    ? parseLong("run.agent_tokens", String.valueOf(rm.get("agent_tokens"))) : run.agentTokens();
            if (gradleWorkers < 1) {
                throw new ConfigException("run.gradle_workers must be at least 1, got '" + gradleWorkers + "'");
            }
            if (modelConcurrency < 1) {
                throw new ConfigException("run.model_concurrency must be at least 1, got '" + modelConcurrency + "'");
            }
            if (tokenBudget < 1) {
                throw new ConfigException("run.token_budget must be at least 1, got '" + tokenBudget + "'");
            }
            if (agentTurns < 1) {
                throw new ConfigException("run.agent_turns must be at least 1, got '" + agentTurns + "'");
            }
            if (agentTokens < 1) {
                throw new ConfigException("run.agent_tokens must be at least 1, got '" + agentTokens + "'");
            }
            List<String> escalationLadder;
            Object ladderNode = rm.get("escalation_ladder");
            if (ladderNode == null) {
                escalationLadder = run.escalationLadder();
            } else if (ladderNode instanceof List<?> l) {
                escalationLadder = l.stream().map(String::valueOf).toList();
            } else {
                throw new ConfigException("run.escalation_ladder must be a list, got: " + ladderNode);
            }
            if (escalationLadder.isEmpty()) {
                throw new ConfigException("run.escalation_ladder must not be empty");
            }
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String key : escalationLadder) {
                if (!models.containsKey(key)) {
                    throw new ConfigException("run.escalation_ladder: unknown model '" + key
                            + "' — not declared under models:");
                }
                if (!seen.add(key)) {
                    throw new ConfigException("run.escalation_ladder: duplicate model '" + key + "'");
                }
            }
            run = new RunSettings(gradleWorkers, modelConcurrency, tokenBudget, agentTurns, agentTokens,
                    escalationLadder,
                    rm.get("single_tool") != null
                            ? Boolean.parseBoolean(String.valueOf(rm.get("single_tool")))
                            : run.singleTool());
        } else if (runNode != null) {
            throw new ConfigException("run must be a mapping, got: " + runNode);
        }

        ExploreSettings explore = ExploreSettings.defaults();
        Object exploreNode = root.get("explore");
        if (exploreNode instanceof Map<?, ?> em) {
            // The record's compact constructor owns the range checks, so a bad value reads the same
            // whether it came from sdd.yml or from code.
            explore = new ExploreSettings(
                    em.get("turns") != null
                            ? parseInt("explore.turns", String.valueOf(em.get("turns")))
                            : explore.turns(),
                    em.get("tokens") != null
                            ? parseLong("explore.tokens", String.valueOf(em.get("tokens")))
                            : explore.tokens(),
                    em.get("wall_seconds") != null
                            ? parseLong("explore.wall_seconds", String.valueOf(em.get("wall_seconds")))
                            : explore.wallSeconds(),
                    em.get("context_soft_cap") != null
                            ? parseInt("explore.context_soft_cap", String.valueOf(em.get("context_soft_cap")))
                            : explore.contextSoftCap(),
                    em.get("single_tool") != null
                            ? Boolean.parseBoolean(String.valueOf(em.get("single_tool")))
                            : explore.singleTool());
        } else if (exploreNode != null) {
            throw new ConfigException("explore must be a mapping, got: " + exploreNode);
        }

        Map<String, List<String>> verificationExclusions = new LinkedHashMap<>();
        Object exclusionsNode = root.get("verification_exclusions");
        if (exclusionsNode instanceof Map<?, ?> em) {
            for (Map.Entry<?, ?> entry : em.entrySet()) {
                if (!(entry.getValue() instanceof List<?> tasks)) {
                    throw new ConfigException("verification_exclusions." + entry.getKey()
                            + " must be a list of task names");
                }
                List<String> names = new ArrayList<>();
                for (Object task : tasks) {
                    names.add(String.valueOf(task));
                }
                verificationExclusions.put(String.valueOf(entry.getKey()), List.copyOf(names));
            }
        } else if (exclusionsNode != null) {
            throw new ConfigException("verification_exclusions must be a mapping, got: " + exclusionsNode);
        }

        Object atlassianNode = root.get("atlassian");
        AtlassianConfig atlassian = atlassianNode == null ? null : parseAtlassian(atlassianNode, env);

        return new SddConfig(workspace, Map.copyOf(models), Map.copyOf(jdkHomes), nodeHome, gradleHome,
                excludes, Map.copyOf(artifactOverrides), List.copyOf(manualEdges),
                List.copyOf(runtimeEdges), run, Map.copyOf(verificationExclusions), atlassian,
                explore);
    }

    // --- atlassian: block ------------------------------------------------------------------

    private static AtlassianConfig parseAtlassian(Object node, Function<String, String> env) {
        if (!(node instanceof Map<?, ?> m)) {
            // Gate review I4: unlike every other "must be a mapping" message in this file, the
            // atlassian.* messages used to echo the raw node — and sdd.yml permits a LITERAL token
            // (AtlassianSite.tokenVar() is then null), so a malformed block echoed the secret
            // straight to stdout. Matches the pre-existing models."X" must be a mapping" convention
            // (no node echoed) across every atlassian.* message below.
            throw new ConfigException("atlassian must be a mapping");
        }
        AtlassianTls tls = m.get("tls") == null ? null : parseAtlassianTls(m.get("tls"), env);
        AtlassianProxy proxy = m.get("proxy") == null ? null : parseAtlassianProxy(m.get("proxy"), env);
        AtlassianSite jira = m.get("jira") == null ? null : parseAtlassianSite("atlassian.jira", m.get("jira"), env);
        AtlassianSite confluence = m.get("confluence") == null
                ? null : parseAtlassianSite("atlassian.confluence", m.get("confluence"), env);
        BitbucketSite bitbucket = m.get("bitbucket") == null ? null : parseBitbucketSite(m.get("bitbucket"), env);

        int followDepth = m.get("follow_depth") == null
                ? DEFAULT_FOLLOW_DEPTH : parseInt("atlassian.follow_depth", String.valueOf(m.get("follow_depth")));
        int maxPages = m.get("max_pages") == null
                ? DEFAULT_MAX_PAGES : parseInt("atlassian.max_pages", String.valueOf(m.get("max_pages")));
        int maxLinkedIssues = m.get("max_linked_issues") == null
                ? DEFAULT_MAX_LINKED_ISSUES
                : parseInt("atlassian.max_linked_issues", String.valueOf(m.get("max_linked_issues")));
        WriteBack writeBack = parseWriteBack(m.get("write_back"));
        boolean pullRequests = m.get("pull_requests") != null
                && parseBool("atlassian.pull_requests", String.valueOf(m.get("pull_requests")));

        return new AtlassianConfig(tls, proxy, jira, confluence, bitbucket, followDepth, maxPages,
                maxLinkedIssues, writeBack, pullRequests);
    }

    private static AtlassianTls parseAtlassianTls(Object node, Function<String, String> env) {
        if (!(node instanceof Map<?, ?> m)) {
            throw new ConfigException("atlassian.tls must be a mapping");
        }
        String truststore = requiredAt(m, "truststore", "atlassian.tls", env);
        Object rawPassword = m.get("truststore_password");
        String password = null;
        String passwordError = null;
        // Deferred-credential idiom, same as a site's token below: an unset ${VAR} must not fail
        // config loading for every command — sdd index/status/clean never open an Atlassian
        // connection and must not be blocked by a truststore password they will never use. See
        // AtlassianTls's javadoc for why an earlier draft got this wrong by resolving eagerly.
        if (rawPassword != null) {
            try {
                password = str(rawPassword, env, "atlassian.tls.truststore_password");
            } catch (ConfigException e) {
                passwordError = e.getMessage();
            }
        }
        return new AtlassianTls(Path.of(truststore), password, passwordError);
    }

    private static AtlassianProxy parseAtlassianProxy(Object node, Function<String, String> env) {
        if (!(node instanceof Map<?, ?> m)) {
            throw new ConfigException("atlassian.proxy must be a mapping");
        }
        String host = requiredAt(m, "host", "atlassian.proxy", env);
        Object rawPort = m.get("port");
        if (rawPort == null) {
            throw new ConfigException("atlassian.proxy.port is required");
        }
        // Gate re-review Fix 2: this used to run rawPort through str(rawPort, env, ...) — which
        // expands a ${VAR} reference — BEFORE parseInt ever saw it, matching no other numeric key
        // in this file (follow_depth/max_pages/max_linked_issues all parseInt the RAW node). A
        // misconfigured `port: ${JIRA_API_KEY}` therefore threw "... must be an integer, got
        // '<the real resolved token>'" straight to the terminal — worse than the literal-node echo
        // I4 fixed, since this one actually resolves an environment variable. A port number has no
        // legitimate reason to come from a secret-shaped ${VAR} anyway, so this now matches its
        // numeric siblings: parse the raw node, never resolve it as an env reference.
        int port = parseInt("atlassian.proxy.port", String.valueOf(rawPort));
        List<String> noProxy = stringList(m.get("no_proxy"), "atlassian.proxy.no_proxy");
        return new AtlassianProxy(host, port, noProxy);
    }

    // Shared by jira/confluence directly and by bitbucket via its embedded AtlassianSite — path is
    // the dotted prefix ("atlassian.jira", "atlassian.bitbucket", ...) every error message under
    // this site is reported at.
    private static AtlassianSite parseAtlassianSite(String path, Object node, Function<String, String> env) {
        if (!(node instanceof Map<?, ?> m)) {
            // Gate review I4: this is the one path a LITERAL token can reach (a human pasting a raw
            // PAT instead of a mapping) — echoing `node` here is precisely the secret-leak case.
            throw new ConfigException(path + " must be a mapping");
        }
        String baseUrl = requiredAt(m, "base_url", path, env);
        Object rawToken = m.get("token");
        String token = null;
        String tokenVar = null;
        String tokenError = null;
        // Deferred-credential idiom, copied from ConfigLoader.endpoint's api_key handling
        // (models.<name>.api_key): a token is only ever consumed by something about to make a
        // network call (RestClient), so an unset ${VAR} must not fail config loading and block a
        // read-only command that never touches this site.
        if (rawToken != null) {
            Matcher wholeValueVarRef = ENV_REF.matcher(String.valueOf(rawToken));
            if (wholeValueVarRef.matches()) {
                tokenVar = wholeValueVarRef.group(1);
            }
            try {
                token = str(rawToken, env, path + ".token");
            } catch (ConfigException e) {
                tokenError = e.getMessage();
            }
        }
        Duration timeout = m.get("timeout_seconds") == null
                ? DEFAULT_ATLASSIAN_TIMEOUT
                : Duration.ofSeconds(parseLong(path + ".timeout_seconds", String.valueOf(m.get("timeout_seconds"))));
        return new AtlassianSite(baseUrl, token, tokenVar, timeout, tokenError);
    }

    private static BitbucketSite parseBitbucketSite(Object node, Function<String, String> env) {
        if (!(node instanceof Map<?, ?> m)) {
            throw new ConfigException("atlassian.bitbucket must be a mapping");
        }
        AtlassianSite site = parseAtlassianSite("atlassian.bitbucket", node, env);
        String project = requiredAt(m, "project", "atlassian.bitbucket", env);
        List<String> defaultReviewers = stringList(m.get("default_reviewers"), "atlassian.bitbucket.default_reviewers");
        return new BitbucketSite(site, project, defaultReviewers);
    }

    private static WriteBack parseWriteBack(Object node) {
        if (node == null) {
            return WriteBack.NONE;
        }
        String v = String.valueOf(node);
        return switch (v) {
            case "none" -> WriteBack.NONE;
            case "comment" -> WriteBack.COMMENT;
            default -> throw new ConfigException("atlassian.write_back must be 'none' or 'comment', got '" + v + "'");
        };
    }

    private static List<String> stringList(Object node, String path) {
        if (node == null) {
            return List.of();
        }
        if (!(node instanceof List<?> l)) {
            throw new ConfigException(path + " must be a list, got: " + node);
        }
        return l.stream().map(String::valueOf).toList();
    }

    private static String requiredAt(Map<?, ?> m, String key, String path, Function<String, String> env) {
        Object v = m.get(key);
        if (v == null) {
            throw new ConfigException(path + "." + key + " is required");
        }
        return str(v, env, path + "." + key);
    }

    // No EmbeddingsRetriever exists and every command retrieves with SQLite FTS5 regardless of this
    // key, so accepting "embeddings" here would be a promise ConfigLoader cannot keep: the user
    // declares a models.embeddings endpoint, wires a credential, and silently gets FTS anyway. The
    // key is still parsed and validated — never stored, see SddConfig's javadoc — so that snakeyaml
    // does not just swallow the setting and let the same lie back in, quieter. "embeddings" and any
    // other bad value get deliberately different messages: a user who wrote "embeddings" followed
    // the (former) documentation and needs to be told the feature does not exist and what to do
    // instead, while a user who wrote something else (e.g. "vector") made a typo and must not be
    // told their config mentions a feature that was never real.
    private static void rejectUnimplementedRetrieval(Map<String, Object> root, Function<String, String> env) {
        Object node = root.get("retrieval");
        if (node == null) {
            return;
        }
        String v = str(node, env, "retrieval");
        if (v.equals("fts")) {
            return;
        }
        if (v.equals("embeddings")) {
            throw new ConfigException("retrieval: embeddings is not implemented — no embeddings "
                    + "backend exists in this build and every command retrieves with SQLite FTS5. "
                    + "Set retrieval: fts, or remove the key.");
        }
        throw new ConfigException("retrieval must be 'fts', got '" + v + "'");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseYaml(Path file) {
        try {
            Object parsed = new Yaml(new SafeConstructor(new LoaderOptions()))
                    .load(Files.readString(file));
            if (!(parsed instanceof Map)) {
                throw new ConfigException("sdd.yml must be a YAML mapping");
            }
            return (Map<String, Object>) parsed;
        } catch (IOException e) {
            throw new ConfigException("cannot read " + file, e);
        }
    }

    private static ModelEndpoint endpoint(String name, Object node, Function<String, String> env) {
        if (!(node instanceof Map<?, ?> m)) {
            throw new ConfigException("models." + name + " must be a mapping");
        }
        String baseUrl = required(m, "base_url", name, env);
        String model = required(m, "model", name, env);
        Object rawKey = m.get("api_key");
        String apiKey = null;
        String apiKeyError = null;
        // Unlike base_url/model (structural — a command cannot work around them), api_key is only
        // ever consumed by HttpChatModel/EndpointProbe, both about to make a network call. An
        // unset ${VAR} here must not block a read-only command that never touches a model: capture
        // the message instead of throwing, and let the point of use raise it — byte-identical —
        // if and when this endpoint is actually reached.
        if (rawKey != null) {
            try {
                apiKey = str(rawKey, env, "models." + name + ".api_key");
            } catch (ConfigException e) {
                apiKeyError = e.getMessage();
            }
        }
        int maxTokens = m.get("max_tokens") == null
                ? DEFAULT_MAX_TOKENS
                : parseInt("models." + name + ".max_tokens", String.valueOf(m.get("max_tokens")));
        double temperature = m.get("temperature") == null
                ? DEFAULT_TEMPERATURE
                : parseDouble("models." + name + ".temperature", String.valueOf(m.get("temperature")));
        Duration timeout = m.get("timeout_seconds") == null
                ? DEFAULT_TIMEOUT
                : Duration.ofSeconds(parseLong("models." + name + ".timeout_seconds", String.valueOf(m.get("timeout_seconds"))));
        Map<String, Object> extraBody = extraBody(m, name);
        Object rawTls = m.get("tls");
        TlsConfig tls = rawTls == null ? null : parseModelTls("models." + name + ".tls", rawTls, env);
        // Structural, like base_url/model, so a typo fails config load rather than silently
        // sending the wrong dialect and surfacing as a gateway 500 several layers away.
        Object rawWire = m.get("wire");
        WireFormat wire = rawWire == null
                ? WireFormat.OPENAI
                : WireFormat.parse("models." + name + ".wire", String.valueOf(rawWire));
        return new ModelEndpoint(baseUrl, model, apiKey, maxTokens, temperature, timeout, extraBody,
                apiKeyError, tls, wire);
    }

    // --- models.<name>.tls: ------------------------------------------------------------------

    /**
     * {@code models.<name>.tls} — mutual-TLS client-certificate auth for a model endpoint. {@code
     * cert}/{@code key} are structural, like {@code base_url}/{@code model}: an unset {@code ${VAR}}
     * in either fails config load eagerly (via {@code str}, same as every other path-shaped key —
     * {@code node_home}, {@code jdk_homes}), because a cert/key path that cannot be resolved is a
     * real misconfiguration, not a credential a read-only command might simply never touch. Exactly
     * one of the pair present is also a hard, eager error, named with both dotted paths so the
     * operator does not have to guess which one is missing — unlike {@code HttpClients.keyManagers}'
     * generic {@code "tls.cert and tls.key..."} message (that class has no endpoint name to put in
     * it), this one does, so it is validated here rather than left to fire later, more confusingly,
     * deep inside {@code HttpClients}.
     *
     * <p>{@code key_password} alone follows the deferred-credential idiom — copied from this class's
     * {@code api_key} handling above, and from {@code AtlassianTls.passwordError}: an unset
     * {@code ${VAR}} must not fail config loading for a command that never opens the key (only
     * {@code HttpChatModel}/{@code EndpointProbe}, via {@code HttpClients.keyManagers}, ever do), so
     * the message is captured in {@code keyPasswordError} and raised byte-identically at the point
     * the key is actually about to be read.
     *
     * <p>{@code truststore_password} follows that same deferred-credential idiom, copied from
     * {@code parseAtlassianTls} exactly: an unset {@code ${VAR}} must not fail {@code
     * ConfigLoader.load} for a command that never opens this endpoint's truststore, so the message
     * is captured in {@code truststorePasswordError} and raised byte-identically at the point
     * {@link sdd.core.http.HttpClients#trustManagers(TlsConfig)} is about to actually open the
     * file. An earlier draft of this method had no such key at all — {@code keytool} itself
     * refuses to create a password-less keystore ("Keystore password must be at least 6
     * characters"), so that draft could never actually be satisfied by anything the JDK's own
     * tooling produces; see the Gate re-review that added this key for the full failure mode.
     *
     * <p>{@code path} — already {@code "models.<name>.tls"}, threaded through every dotted key
     * above — is also stamped onto the returned {@link TlsConfig} as {@code configPath}, the same
     * way {@code parseAtlassianTls} stamps {@code "atlassian.tls"} onto the {@code AtlassianTls} it
     * builds. That is what lets {@code HttpClients} name this endpoint's own {@code models.<name>.tls.cert}/
     * {@code .key}/{@code .truststore} in a load-time TLS error thrown later — a bare {@code
     * PKIX path building failed} or generic {@code tls.cert ... does not exist} sends an operator
     * hunting for a top-level {@code tls:} block that does not exist in {@code sdd.yml}, when the
     * key they actually need to edit is right here.
     */
    private static TlsConfig parseModelTls(String path, Object node, Function<String, String> env) {
        if (!(node instanceof Map<?, ?> m)) {
            throw new ConfigException(path + " must be a mapping");
        }
        Object rawCert = m.get("cert");
        Object rawKey = m.get("key");
        if ((rawCert == null) != (rawKey == null)) {
            throw new ConfigException(path + ".cert and " + path + ".key must both be configured, or neither");
        }
        Path cert = rawCert == null ? null : Path.of(str(rawCert, env, path + ".cert"));
        Path key = rawKey == null ? null : Path.of(str(rawKey, env, path + ".key"));

        Object rawKeyPassword = m.get("key_password");
        String keyPassword = null;
        String keyPasswordError = null;
        if (rawKeyPassword != null) {
            try {
                keyPassword = str(rawKeyPassword, env, path + ".key_password");
            } catch (ConfigException e) {
                keyPasswordError = e.getMessage();
            }
        }

        List<String> protocols = stringList(m.get("protocols"), path + ".protocols");

        Object rawTruststore = m.get("truststore");
        Path truststore = rawTruststore == null ? null : Path.of(str(rawTruststore, env, path + ".truststore"));

        Object rawTruststorePassword = m.get("truststore_password");
        String truststorePassword = null;
        String truststorePasswordError = null;
        // Deferred-credential idiom, copied from parseAtlassianTls above: an unset ${VAR} must not
        // fail config loading for a command that never opens this endpoint's truststore.
        if (rawTruststorePassword != null) {
            try {
                truststorePassword = str(rawTruststorePassword, env, path + ".truststore_password");
            } catch (ConfigException e) {
                truststorePasswordError = e.getMessage();
            }
        }

        return new TlsConfig(truststore, truststorePassword, truststorePasswordError, cert, key, keyPassword,
                keyPasswordError, protocols, path);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extraBody(Map<?, ?> m, String name) {
        Object raw = m.get("extra_body");
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new ConfigException("models." + name + ".extra_body must be a mapping, got: " + raw);
        }
        validateNoNulls(map, "models." + name + ".extra_body");
        return (Map<String, Object>) deepCopy(map);
    }

    private static void validateNoNulls(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (e.getValue() == null) {
                    throw new ConfigException(path + " contains null value for key '" + key + "'");
                }
                validateNoNulls(e.getValue(), path + "." + key);
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (item == null) {
                    throw new ConfigException(path + "[" + i + "] contains null value");
                }
                validateNoNulls(item, path + "[" + i + "]");
            }
        }
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                copy.put(String.valueOf(e.getKey()), deepCopy(e.getValue()));
            }
            return Map.copyOf(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(deepCopy(item));
            }
            return List.copyOf(copy);
        }
        return value;
    }

    private static String required(Map<?, ?> m, String key, String endpointName, Function<String, String> env) {
        Object v = m.get(key);
        if (v == null) {
            throw new ConfigException("models." + endpointName + "." + key + " is required");
        }
        return str(v, env, "models." + endpointName + "." + key);
    }

    private static String requiredEdgeKey(Map<?, ?> m, String key, int index, Function<String, String> env) {
        Object v = m.get(key);
        if (v == null) {
            throw new ConfigException("manual_edges[" + index + "]: " + key + " is required");
        }
        return str(v, env, "manual_edges[" + index + "]." + key);
    }

    private static int parseInt(String where, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new ConfigException(where + " must be an integer, got '" + value + "'");
        }
    }

    private static long parseLong(String where, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ConfigException(where + " must be an integer, got '" + value + "'");
        }
    }

    private static boolean parseBool(String where, String value) {
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new ConfigException(where + " must be true or false, got '" + value + "'");
    }

    private static double parseDouble(String where, String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new ConfigException(where + " must be a number, got '" + value + "'");
        }
    }

    private static String str(Object value, Function<String, String> env, String where) {
        String s = String.valueOf(value);
        Matcher matcher = ENV_REF.matcher(s);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String var = matcher.group(1);
            String resolved = env.apply(var);
            if (resolved == null) {
                throw new ConfigException(where + ": environment variable " + var + " is not set");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(resolved));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
