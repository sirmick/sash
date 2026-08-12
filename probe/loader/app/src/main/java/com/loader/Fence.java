package com.loader;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What an app's fence has learned since it was installed.
 *
 * Two lists, kept apart on purpose:
 *
 *   allowed — hosts the user has explicitly let in. Fixes their phone now.
 *   seen    — hosts that were ejected and not allowed. Evidence, nothing more.
 *
 * Conflating them would make every mis-tap a permanent hole, and make every
 * ejection look like a decision the user made.
 *
 * Neither is the catalogue. The catalogue changes by pull request, at its own
 * pace, from evidence that several people gathered — which is why `seen` is
 * worth keeping even when nobody allows anything.
 */
final class Fence {
    private static final String PREFS = "fence";

    private final SharedPreferences prefs;
    private final String id;

    Fence(Context ctx, Site app) {
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.id = app.id;
    }

    /** Hosts the user has added to this app, on this device. */
    Set<String> allowed() {
        return new TreeSet<>(prefs.getStringSet(id + ".allowed", Collections.emptySet()));
    }

    /** Hosts this app tried to reach and was refused. */
    Set<String> seen() {
        return new TreeSet<>(prefs.getStringSet(id + ".seen", Collections.emptySet()));
    }

    /**
     * Records an ejection.
     *
     * Always on, because the alternative is asking people to turn on recording
     * *before* the thing they could not have predicted happens. Nothing is sent
     * anywhere; it is a list of hostnames in this app's own preferences.
     */
    void record(String url) {
        String host = Origins.hostOf(url);
        if (host == null || host.isEmpty()) return;
        Set<String> s = new LinkedHashSet<>(seen());
        if (s.add(host)) put(id + ".seen", s);
    }

    /** Lets a host in, and stops calling it evidence. */
    void allow(String url) {
        String host = Origins.hostOf(url);
        if (host == null || host.isEmpty()) return;
        Set<String> a = new LinkedHashSet<>(allowed());
        a.add(host);
        put(id + ".allowed", a);
        Set<String> s = new LinkedHashSet<>(seen());
        if (s.remove(host)) put(id + ".seen", s);
    }

    void forget(String host) {
        Set<String> a = new LinkedHashSet<>(allowed());
        if (a.remove(host)) put(id + ".allowed", a);
    }

    /** The app's shipped fence plus whatever this device has added. */
    String[] effective(Site app) {
        Set<String> all = new LinkedHashSet<>();
        Collections.addAll(all, app.origins);
        all.addAll(allowed());
        return all.toArray(new String[0]);
    }

    private void put(String key, Set<String> value) {
        prefs.edit().putStringSet(key, value).apply();
    }

    // ----- proposing it back ------------------------------------------------

    /**
     * The catalogue entry this device would propose.
     *
     * A list of hostnames. Matching is host-or-suffix, so `chase.com` already
     * covers every subdomain and there is nothing to wildcard by hand — which
     * is the point: a regular expression here is an open redirect waiting for a
     * reviewer to miss it, and a hostname is not.
     */
    static String entry(Site app, Set<String> origins) {
        StringBuilder b = new StringBuilder();
        b.append("id: ").append(app.id).append('\n');
        b.append("label: ").append(app.label).append('\n');
        b.append("home: ").append(app.home).append('\n');
        b.append("origins:\n");
        for (String o : new TreeSet<>(origins)) b.append("  - ").append(o).append('\n');
        b.append("permissions: []\n");
        return b.toString();
    }

    /**
     * A one-tap pull request, with no account, no token and no API.
     *
     * GitHub prefills its web editor from the query string and offers "Propose
     * new file"; it does the fork and opens the PR itself. Our side of this is
     * a URL.
     */
    static String proposeUrl(String repo, Site app, Set<String> origins) {
        return new Uri.Builder()
                .scheme("https").authority("github.com")
                .appendEncodedPath(repo).appendPath("new").appendPath("main")
                .appendQueryParameter("filename", "catalogue/" + app.id + ".yaml")
                .appendQueryParameter("value", entry(app, origins))
                .build().toString();
    }

    /** Everything worth proposing: what shipped, plus what this device learned. */
    List<String> proposable(Site app) {
        Set<String> all = new TreeSet<>();
        Collections.addAll(all, app.origins);
        all.addAll(allowed());
        return new ArrayList<>(all);
    }
}
