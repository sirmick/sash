package com.loader;

import android.app.Activity;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;
import android.widget.FrameLayout;

import java.lang.reflect.Method;

/**
 * Renders one site, using an engine this APK does not contain.
 *
 * Everything is reflective because there is no compile-time dependency on
 * GeckoView — the classes only exist once Core.graft has run.
 */
public class SiteActivity extends Activity {
    private static final String TAG = "loader";
    private static final String URL = BuildConfig.SITE;
    private Resources merged;

    @Override public Resources getResources() {
        if (merged == null) {
            try { merged = Core.resources(this, super.getResources()); }
            catch (Throwable t) { return super.getResources(); }
        }
        return merged;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // What this app is, what it may touch, and where its profile lives.
        Log.i(TAG, "identity: pkg=" + getPackageName() + " uid=" + android.os.Process.myUid());
        Log.i(TAG, "camera: " + (checkSelfPermission(android.Manifest.permission.CAMERA)
                == android.content.pm.PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED"));
        Log.i(TAG, "datadir: " + getFilesDir().getParent());

        FrameLayout root = new FrameLayout(this);
        setContentView(root);
        try {
            ClassLoader cl = getClassLoader();
            Class<?> runtimeC = cl.loadClass("org.mozilla.geckoview.GeckoRuntime");
            Class<?> sessionC = cl.loadClass("org.mozilla.geckoview.GeckoSession");
            Class<?> viewC = cl.loadClass("org.mozilla.geckoview.GeckoView");
            Log.i(TAG, "classes: OK");

            android.content.Context geckoCtx =
                    new CoreContext(this, Core.apkPath(this), getResources());
            Method getRuntime = runtimeC.getMethod("getDefault", android.content.Context.class);
            Object runtime = getRuntime.invoke(null, geckoCtx);
            Log.i(TAG, "runtime: OK — " + runtime);

            Object session = sessionC.getDeclaredConstructor().newInstance();
            sessionC.getMethod("open", runtimeC).invoke(session, runtime);
            Log.i(TAG, "session: OK");

            Object view = viewC.getConstructor(android.content.Context.class).newInstance(this);
            viewC.getMethod("setSession", sessionC).invoke(view, session);
            root.addView((android.view.View) view,
                    new FrameLayout.LayoutParams(-1, -1));

            sessionC.getMethod("loadUri", String.class).invoke(session, URL);
            Log.i(TAG, "loadUri: OK — " + URL);
        } catch (Throwable t) {
            Log.e(TAG, "render: FAILED — " + t, t);
        }
    }
}
