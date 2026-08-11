package com.pane;

import android.net.Uri;

/**
 * The fence around an app.
 *
 * An entry is `host` or `host/path-prefix`. The host half matches itself and its
 * subdomains; the path half, when present, must prefix the URL's path. Paths
 * are not decoration: one host can serve several products — www.google.com is
 * search, the account pages *and* Maps — so a bare-host allowlist for one of
 * them claims all three.
 */
public final class Origins {

    /** https only. An icon is a promise about where it goes, and cleartext cannot keep it. */
    public static boolean allowed(String url, String[] origins) {
        Uri u;
        try {
            u = Uri.parse(url);
        } catch (Exception e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(u.getScheme())) return false;

        String host = u.getHost();
        if (host == null) return false;
        String path = u.getPath() == null ? "/" : u.getPath();

        for (String origin : origins) {
            int cut = origin.indexOf('/');
            String h = cut < 0 ? origin : origin.substring(0, cut);
            String prefix = cut < 0 ? "" : origin.substring(cut);
            boolean hostOk = host.equals(h) || host.endsWith("." + h);
            if (hostOk && (prefix.isEmpty() || path.startsWith(prefix))) return true;
        }
        return false;
    }

    /**
     * Live redirect chains still emit http hops, and refusing them produces a
     * blank page — which reads as the site being broken rather than as us
     * refusing. Upgrade instead; a site with genuinely no https then fails
     * visibly with a network error, which is honest and also true.
     */
    public static String upgrade(String url) {
        return url.startsWith("http://") ? "https://" + url.substring("http://".length()) : url;
    }

    public static String hostOf(String url) {
        try {
            String h = Uri.parse(url).getHost();
            return h == null ? url : h;
        } catch (Exception e) {
            return url;
        }
    }

    private Origins() {}
}
