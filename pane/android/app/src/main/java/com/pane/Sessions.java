package com.pane;

import android.content.Context;
import android.util.Log;

import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;

import java.util.HashMap;
import java.util.Map;

/**
 * The engine, and one live session per app.
 *
 * # Why sessions outlive activities
 *
 * Android destroys and recreates activities constantly — rotation, memory
 * pressure, "don't keep activities". If a GeckoSession died with its activity,
 * every rotation would reload your bank page and lose the form you were half
 * way through. So sessions live here, keyed by app id, and an activity attaches
 * a GeckoView to an existing one. This is the standard GeckoView pattern and it
 * is miserable to retrofit, so it is here from the first commit.
 *
 * # The base profile is the runtime
 *
 * "Inherited settings, isolated storage" is not something built on top of
 * Gecko — it is Gecko's shape. Content blocking and extensions live on the
 * *runtime* and therefore apply to every app; storage lives on the *contextId*
 * and therefore does not. Install a blocklist once and Chase gets it without
 * Chase sharing a cookie with anything.
 */
public final class Sessions {

    static final String TAG = "pane";

    private static GeckoRuntime runtime;
    private static final Map<String, GeckoSession> sessions = new HashMap<>();

    /** One runtime per process — creating a second one throws. */
    public static synchronized GeckoRuntime runtime(Context ctx) {
        if (runtime == null) {
            runtime = GeckoRuntime.create(
                    ctx.getApplicationContext(),
                    new GeckoRuntimeSettings.Builder()
                            // The base profile: applies to every app, shares nothing.
                            .contentBlocking(new ContentBlocking.Settings.Builder()
                                    .antiTracking(ContentBlocking.AntiTracking.DEFAULT)
                                    .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
                                    .build())
                            .consoleOutput(true)
                            .build());
            Log.i(TAG, "runtime created");
        }
        return runtime;
    }

    /**
     * The session for an app, created on first use.
     *
     * Apps sharing a contextId share a jar — which is exactly how Gmail arrives
     * already signed in after Google Login.
     */
    public static synchronized GeckoSession of(Context ctx, App app) {
        GeckoSession s = sessions.get(app.id);
        if (s != null) return s;

        s = new GeckoSession(new GeckoSessionSettings.Builder()
                .contextId(app.contextId)
                .usePrivateMode(false)
                .build());
        s.open(runtime(ctx));
        sessions.put(app.id, s);
        Log.i(TAG, "session " + app.id + " ctx=" + app.contextId);
        return s;
    }

    /** Whether this app has ever been opened — i.e. whether it holds a live page. */
    public static synchronized boolean isLive(String appId) {
        return sessions.containsKey(appId);
    }

    /** Close an app for good; its jar survives, so re-opening finds the session intact. */
    public static synchronized void close(String appId) {
        GeckoSession s = sessions.remove(appId);
        if (s != null) {
            s.close();
            Log.i(TAG, "session " + appId + " closed");
        }
    }

    private Sessions() {}
}
