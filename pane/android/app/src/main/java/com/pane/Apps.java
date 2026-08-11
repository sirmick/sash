package com.pane;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole catalogue, hardcoded on purpose.
 *
 * Three apps is the smallest set that tests the thesis: a base identity, an app
 * that inherits it, and an app that shares nothing with either. A registry, a
 * launcher and an installer are all generalisations of an AppActivity that
 * works — and none of them prove anything this list does not.
 */
public final class Apps {

    /** Tier 1. The base identity: signing in here signs in everything sharing "google". */
    public static final App GOOGLE_LOGIN = new App(
            "google-login", "Google Account",
            "https://accounts.google.com/signin",
            "google", 0xFF4285F4,
            "accounts.google.com", "myaccount.google.com");

    /**
     * Tier 2, inheriting tier 1 — same jar, so Google is already logged in.
     * Locked to its own host regardless: shared identity, separate fence.
     */
    public static final App GMAIL = new App(
            "gmail", "Gmail",
            "https://mail.google.com/",
            "google", 0xFFEA4335,
            "mail.google.com");

    /** Its own tier 1. Shares nothing with anything, and nothing inherits it. */
    public static final App CHASE = new App(
            "chase", "Chase",
            "https://chase.com/",
            "chase", 0xFF117ACA,
            "chase.com");

    public static List<App> all() {
        List<App> out = new ArrayList<>();
        out.add(GOOGLE_LOGIN);
        out.add(GMAIL);
        out.add(CHASE);
        return out;
    }

    /**
     * The app that owns a URL *within the same jar*, if any.
     *
     * Signed out, Gmail redirects to accounts.google.com — outside Gmail's own
     * fence, so it is refused. But that host belongs to Google Account, which
     * shares Gmail's jar, so the honest answer is not to widen Gmail's fence:
     * it is to open the app whose fence it already is. Sign in there and Gmail
     * works, because they were always the same identity.
     *
     * Restricted to the same contextId on purpose. Handing a blocked navigation
     * to an app in a *different* jar would be a way to move a session across an
     * isolation boundary by redirect, which is the thing the boundary is for.
     */
    public static App siblingOwning(App from, String url) {
        for (App a : all()) {
            if (a.id.equals(from.id)) continue;
            if (!a.contextId.equals(from.contextId)) continue;
            if (Origins.allowed(url, a.origins)) return a;
        }
        return null;
    }

    public static App byId(String id) {
        for (App a : all()) {
            if (a.id.equals(id)) return a;
        }
        return null;
    }

    private Apps() {}
}
