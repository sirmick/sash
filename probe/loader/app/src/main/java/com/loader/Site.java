package com.loader;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * What this app is, read from the catalogue entry baked into it at mint time.
 *
 * The asset is the entry a contributor wrote — same fields, same file. Nothing
 * is transcribed into code, so an app cannot drift from the entry that
 * describes it, and minting a new site is a manifest plus this file rather than
 * a patch.
 */
final class Site {
    final String id, label, home;
    final String[] origins;

    private Site(String id, String label, String home, String[] origins) {
        this.id = id; this.label = label; this.home = home; this.origins = origins;
    }

    static Site load(Context ctx) {
        try (InputStream in = ctx.getAssets().open("site.json")) {
            byte[] buf = new byte[in.available()];
            int n = in.read(buf);
            JSONObject o = new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
            JSONArray a = o.getJSONArray("origins");
            String[] origins = new String[a.length()];
            for (int i = 0; i < a.length(); i++) origins[i] = a.getString(i);
            return new Site(o.getString("id"), o.getString("label"),
                            o.getString("home"), origins);
        } catch (Exception e) {
            throw new IllegalStateException("no usable site.json", e);
        }
    }
}
