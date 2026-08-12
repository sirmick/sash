package com.manager;

/**
 * One entry in the catalogue.
 *
 * [permissions] is the honest part of the UI: it is what the site app's manifest
 * asks for, and therefore the ceiling on what that site can ever reach. An app
 * installed without CAMERA cannot be granted it later — there is nothing to
 * grant — so this line is a guarantee rather than a preference.
 */
final class Site {
    final String label, pkg, asset, url, permissions;

    Site(String label, String pkg, String asset, String url, String permissions) {
        this.label = label; this.pkg = pkg; this.asset = asset;
        this.url = url; this.permissions = permissions;
    }

    static final Site[] ALL = {
        new Site("Wikipedia", "com.loader.wikipedia", "wikipedia.apk",
                 "en.m.wikipedia.org", "network only"),
        new Site("Hacker News", "com.loader.news", "news.apk",
                 "news.ycombinator.com", "network only"),
        new Site("Meet", "com.loader.meet", "meet.apk",
                 "meet.jit.si", "network, camera, microphone"),
    };
}
