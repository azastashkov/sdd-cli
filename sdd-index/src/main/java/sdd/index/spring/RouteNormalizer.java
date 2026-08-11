package sdd.index.spring;

public final class RouteNormalizer {
    private RouteNormalizer() {}

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
}
