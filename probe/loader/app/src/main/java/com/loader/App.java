package com.loader;

import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

/**
 * The graft has to happen before anything else touches a Gecko class, and
 * before Android instantiates any of the 89 child-process services this
 * package declares — all of which resolve to classes that live in core.
 */
public class App extends Application {
    private static final String TAG = "loader";
    private Resources merged;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            Core.graft(base, getClassLoader());
            Core.preload(base);
            merged = Core.resources(base, base.getResources());
            Log.i(TAG, "graft: OK");
        } catch (Throwable t) {
            Log.e(TAG, "graft: FAILED — " + t, t);
        }
    }

    @Override public Resources getResources() {
        return merged != null ? merged : super.getResources();
    }
}
