package sdd.core.route;

/**
 * Shared route semantics: the indexer writes rest_endpoint.norm_path with normalize(); the
 * planner matches touchpoints against it with templatesMatch()/verbsCompatible(). Moved from
 * sdd-index (RouteNormalizer + RestMatcher helpers) verbatim so both modules share one truth.
 */
public final class Routes {
    private Routes() {
    }

    public static String join(String basePath, String methodPath) {
        String base = strip(basePath);
        String method = strip(methodPath);
        String joined = base + (method.isEmpty() ? "" : "/" + method);
        if (joined.isEmpty()) {
            return "/";
        }
        if (joined.startsWith("/")) {
            return joined;
        }
        return "/" + joined;
    }

    public static String normalize(String template) {
        if (template == null) {
            return "/";
        }
        String collapsed = ("/" + template).replaceAll("\\{[^}]*}", "{}")
                .replaceAll("/{2,}", "/");
        if (collapsed.length() > 1 && collapsed.endsWith("/")) {
            collapsed = collapsed.substring(0, collapsed.length() - 1);
        }
        return collapsed;
    }

    private static String strip(String s) {
        if (s == null) {
            return "";
        }
        String out = s;
        while (out.startsWith("/")) {
            out = out.substring(1);
        }
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    public static boolean templatesMatch(String clientNorm, String endpointNorm) {
        String[] a = clientNorm.split("/");
        String[] b = endpointNorm.split("/");
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!a[i].equals(b[i]) && !a[i].equals("{}") && !b[i].equals("{}")) {
                return false;
            }
        }
        return true;
    }

    public static boolean verbsCompatible(String clientVerb, String endpointVerb) {
        return "ANY".equals(clientVerb) || "ANY".equals(endpointVerb)
                || clientVerb.equals(endpointVerb);
    }
}
