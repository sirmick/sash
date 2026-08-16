package com.manager;

/**
 * One entry in the catalogue.
 *
 * The type is written by hand; the list is not — see the generated
 * {@link Catalogue}, which scripts/mint.py writes from catalogue/*.json.
 *
 * [permissions] is the honest part of the UI: it is what the site app's manifest
 * asks for, and therefore the ceiling on what that site can ever reach. An app
 * installed without CAMERA cannot be granted it later — there is nothing to
 * grant — so this line is a guarantee rather than a preference.
 *
 * [surfaces] is how many launcher icons the one package installs. It is worth
 * saying out loud: installing Google puts four icons on the home screen, and
 * they share a cookie jar because they share the package.
 */
final class Site {
    final String label, pkg, asset, url, permissions;
    final int surfaces;

    Site(String label, String pkg, String asset, String url, String permissions, int surfaces) {
        this.label = label; this.pkg = pkg; this.asset = asset;
        this.url = url; this.permissions = permissions; this.surfaces = surfaces;
    }

    /** The line under the name: where it may go, what it may reach, how many icons. */
    String detail() {
        String s = url + "  ·  " + permissions;
        return surfaces > 1 ? s + "  ·  " + surfaces + " icons" : s;
    }
}
