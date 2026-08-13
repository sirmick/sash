package com.loader;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * One surface of a catalogue entry, read from the entry baked in at mint time.
 *
 * An entry may carry several surfaces — Google carries four — and they share
 * this package, therefore its uid, its data directory and its cookie jar. That
 * sharing is what makes Gmail already signed in once Google Account is, and it
 * is why a jar cannot span packages.
 *
 * Each surface still has its own fence. Sharing an identity is not sharing
 * permission to wander.
 */
final class Site {
    final String id, label, home;
    final String[] origins;
    /** The other surfaces in this entry — same package, therefore same jar. */
    final java.util.Map<String, String[]> siblings = new java.util.LinkedHashMap<>();

    private Site(String id, String label, String home, String[] origins) {
        this.id = id; this.label = label; this.home = home; this.origins = origins;
    }

    /**
     * Which sibling owns this URL, if any.
     *
     * Signed out, Gmail bounces to accounts.google.com — which is not a fence
     * failure, it is the identity surface of the same account. Routing there
     * beats refusing, and it is safe precisely because siblings share this
     * package: no session crosses a boundary that was not already shared.
     */
    String siblingOwning(String url) {
        for (java.util.Map.Entry<String, String[]> e : siblings.entrySet()) {
            if (!e.getKey().equals(id) && Origins.allowed(url, e.getValue())) return e.getKey();
        }
        return null;
    }

    /**
     * @param surfaceId which icon was tapped, or null for the entry's first
     *                  surface — the launcher tells us via the activity-alias
     *                  that started us.
     */
    static Site load(Context ctx, String surfaceId) {
        try (InputStream in = ctx.getAssets().open("site.json")) {
            byte[] buf = new byte[in.available()];
            int n = in.read(buf);
            JSONObject entry = new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
            JSONArray surfaces = entry.getJSONArray("surfaces");

            JSONObject chosen = surfaces.getJSONObject(0);
            if (surfaceId != null) {
                for (int i = 0; i < surfaces.length(); i++) {
                    if (surfaceId.equals(surfaces.getJSONObject(i).getString("id"))) {
                        chosen = surfaces.getJSONObject(i);
                        break;
                    }
                }
            }
            JSONArray a = chosen.getJSONArray("origins");
            String[] origins = new String[a.length()];
            for (int i = 0; i < a.length(); i++) origins[i] = a.getString(i);
            Site site = new Site(chosen.getString("id"), chosen.getString("label"),
                                 chosen.getString("home"), origins);
            for (int i = 0; i < surfaces.length(); i++) {
                JSONObject s = surfaces.getJSONObject(i);
                JSONArray o = s.getJSONArray("origins");
                String[] so = new String[o.length()];
                for (int j = 0; j < o.length(); j++) so[j] = o.getString(j);
                site.siblings.put(s.getString("id"), so);
            }
            return site;
        } catch (Exception e) {
            throw new IllegalStateException("no usable site.json", e);
        }
    }
}
