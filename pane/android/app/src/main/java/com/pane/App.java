package com.pane;

/**
 * One installed app: an icon, a jar, and a fence.
 *
 * The two identity concepts are deliberately orthogonal, and keeping them apart
 * is what makes tiering work:
 *
 *   contextId  WHO YOU ARE. Gecko's storage partition — cookies, localStorage,
 *              service workers, cache. Apps that share one are the same
 *              identity: Google Login and Gmail share "google", so signing in
 *              through one signs in the other. That is the whole of tier-1
 *              inheritance, and for Google's own properties it is the *only*
 *              thing that works — Gmail is not an OAuth client of Google, it is
 *              a first-party surface of the same SSO family, so there is no
 *              token dance to run.
 *
 *   origins    WHERE THIS ICON MAY GO. Per app, regardless of jar. Gmail shares
 *              Google's cookies and still cannot wander to YouTube. Without
 *              this an icon is only a bookmark: one open redirect on a
 *              first-party domain and somebody else's page is rendering under
 *              your bank's name.
 */
public final class App {
    public final String id;
    public final String label;
    public final String home;
    public final String contextId;
    public final String[] origins;
    /** Recents-card tint, so the task looks like the app rather than like us. */
    public final int color;

    App(String id, String label, String home, String contextId, int color, String... origins) {
        this.id = id;
        this.label = label;
        this.home = home;
        this.contextId = contextId;
        this.color = color;
        this.origins = origins;
    }
}
