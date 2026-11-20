package de.sourcescout.core;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class UrlNormalizer {

    private static final Set<String> TRACKING_PARAMS = Set.of(
            "gclid", "fbclid", "mc_cid", "mc_eid", "igshid", "cmpid", "ref");

    private UrlNormalizer() {
    }

    public static String normalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "";
        }
        try {
            URI uri = new URI(rawUrl.trim());
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            String path = (uri.getPath() == null || uri.getPath().isBlank()) ? "/" : uri.getPath();
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            StringBuilder query = new StringBuilder();
            if (uri.getRawQuery() != null) {
                List<String> kept = new ArrayList<>();
                for (String pair : uri.getRawQuery().split("&")) {
                    if (pair.isBlank()) {
                        continue;
                    }
                    String name = pair.split("=", 2)[0].toLowerCase(Locale.ROOT);
                    if (name.startsWith("utm_") || TRACKING_PARAMS.contains(name)) {
                        continue;
                    }
                    kept.add(pair);
                }
                query.append(String.join("&", kept));
            }
            return scheme + "://" + host + path + (!query.isEmpty() ? "?" + query : "");
        } catch (Exception ex) {
            return rawUrl.trim().toLowerCase(Locale.ROOT);
        }
    }
}
