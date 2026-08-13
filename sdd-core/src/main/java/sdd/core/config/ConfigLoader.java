package sdd.core.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

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

        String retrieval = str(root.getOrDefault("retrieval", "fts"), env, "retrieval");
        if (!retrieval.equals("fts") && !retrieval.equals("embeddings")) {
            throw new ConfigException("retrieval must be 'fts' or 'embeddings', got '" + retrieval + "'");
        }

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
        if (retrieval.equals("embeddings") && !models.containsKey("embeddings")) {
            throw new ConfigException("retrieval=embeddings requires a models.embeddings endpoint");
        }

        Map<Integer, Path> jdkHomes = new LinkedHashMap<>();
        if (root.get("jdk_homes") instanceof Map<?, ?> jm) {
            for (Map.Entry<?, ?> e : jm.entrySet()) {
                jdkHomes.put(parseInt("jdk_homes key", String.valueOf(e.getKey())),
                        Path.of(str(e.getValue(), env, "jdk_homes")));
            }
        }

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
                    escalationLadder);
        } else if (runNode != null) {
            throw new ConfigException("run must be a mapping, got: " + runNode);
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

        return new SddConfig(workspace, retrieval, Map.copyOf(models), Map.copyOf(jdkHomes),
                excludes, Map.copyOf(artifactOverrides), List.copyOf(manualEdges), run,
                Map.copyOf(verificationExclusions));
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
        String apiKey = rawKey == null ? null : str(rawKey, env, "models." + name + ".api_key");
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
        return new ModelEndpoint(baseUrl, model, apiKey, maxTokens, temperature, timeout, extraBody);
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
