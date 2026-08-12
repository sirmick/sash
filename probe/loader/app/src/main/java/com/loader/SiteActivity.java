package com.loader;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * One site, fenced, using an engine this APK does not contain.
 *
 * Everything touching GeckoView is reflective: there is no compile-time
 * dependency on it, because the classes do not exist until Core.graft has run.
 * That is the price of the shared engine, and it is confined to this file.
 */
public class SiteActivity extends Activity {
    private static final String TAG = "loader";
    private static final String REPO = "sirmick/sash";

    private Site site;
    private Fence fence;
    private Resources merged;
    private TextView bar;
    private Object session;
    /** The host actually on screen. The bar must never claim otherwise. */
    private String currentHost;
    private Class<?> sessionC;

    @Override public Resources getResources() {
        if (merged == null) {
            try { merged = Core.resources(this, super.getResources()); }
            catch (Throwable t) { return super.getResources(); }
        }
        return merged;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        site = Site.load(this);
        fence = new Fence(this, site);
        Log.i(TAG, site.id + ": uid=" + android.os.Process.myUid()
                + " origins=" + String.join(",", fence.effective(site)));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0b0e12"));

        // The one thing on screen the user is meant to be able to trust. A
        // full-screen surface can paint anything it likes; this is above it.
        bar = new TextView(this);
        bar.setGravity(Gravity.CENTER);
        bar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        bar.setTextColor(Color.WHITE);
        bar.setBackgroundColor(Color.parseColor("#1f6feb"));
        int pad = (int) (6 * getResources().getDisplayMetrics().density);
        bar.setPadding(pad, pad, pad, pad);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        currentHost = site.origins.length > 0 ? site.origins[0] : site.home;
        setBar(currentHost);

        try {
            root.addView(engine(), new LinearLayout.LayoutParams(-1, 0, 1f));
        } catch (Throwable t) {
            Log.e(TAG, "engine: FAILED — " + t, t);
            TextView err = new TextView(this);
            err.setText("Engine unavailable.\n\n" + t);
            err.setTextColor(Color.WHITE);
            err.setPadding(pad * 4, pad * 4, pad * 4, pad * 4);
            root.addView(err);
        }
        setContentView(root);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            bar.setPadding(pad, pad + insets.getSystemWindowInsetTop(), pad, pad);
            return insets;
        });
    }

    /** Builds the GeckoView, wires the fence, and loads home. */
    private View engine() throws Exception {
        ClassLoader cl = getClassLoader();
        Class<?> runtimeC = cl.loadClass("org.mozilla.geckoview.GeckoRuntime");
        sessionC = cl.loadClass("org.mozilla.geckoview.GeckoSession");
        Class<?> viewC = cl.loadClass("org.mozilla.geckoview.GeckoView");
        Class<?> navC = cl.loadClass("org.mozilla.geckoview.GeckoSession$NavigationDelegate");

        Object runtime = runtimeC.getMethod("getDefault", android.content.Context.class)
                .invoke(null, new CoreContext(this, Core.apkPath(this), getResources()));
        session = sessionC.getDeclaredConstructor().newInstance();
        sessionC.getMethod("setNavigationDelegate", navC).invoke(session, lock(navC, cl));
        sessionC.getMethod("open", runtimeC).invoke(session, runtime);

        Object view = viewC.getConstructor(android.content.Context.class).newInstance(this);
        viewC.getMethod("setSession", sessionC).invoke(view, session);
        sessionC.getMethod("loadUri", String.class).invoke(session, site.home);
        return (View) view;
    }

    /**
     * The fence, as a NavigationDelegate built at runtime.
     *
     * Subframes are deliberately not fenced: federated sign-in runs in iframes,
     * and ejecting them breaks logging in to almost everything. The allowlist
     * governs where the *app* may go, not every resource a page may pull.
     */
    private Object lock(Class<?> navC, ClassLoader cl) throws Exception {
        Class<?> allowDenyC = cl.loadClass("org.mozilla.geckoview.AllowOrDeny");
        Class<?> resultC = cl.loadClass("org.mozilla.geckoview.GeckoResult");
        Object ALLOW = allowDenyC.getField("ALLOW").get(null);
        Object DENY = allowDenyC.getField("DENY").get(null);
        Method fromValue = resultC.getMethod("fromValue", Object.class);

        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "onLoadRequest": {
                    String url = Origins.upgrade(uriOf(args[1]));
                    if (Origins.allowed(url, fence.effective(site))) {
                        currentHost = Origins.hostOf(url);
                        runOnUiThread(() -> setBar(currentHost));
                        return fromValue.invoke(null, ALLOW);
                    }
                    Log.i(TAG, site.id + ": EJECTED " + url);
                    fence.record(url);
                    runOnUiThread(() -> ejected(url));
                    return fromValue.invoke(null, DENY);
                }
                case "onSubframeLoadRequest":
                    return fromValue.invoke(null, ALLOW);
                case "toString": return "fence(" + site.id + ")";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                default: return null;
            }
        };
        return Proxy.newProxyInstance(cl, new Class<?>[]{navC}, h);
    }

    private static String uriOf(Object loadRequest) throws Exception {
        return (String) loadRequest.getClass().getField("uri").get(loadRequest);
    }

    private void setBar(String host) {
        bar.setText(host + "  ·  " + site.label);
    }

    /**
     * A blocked navigation, explained — and offered, quietly.
     *
     * "Always allow" is the neutral button and comes last. Widening a fence is
     * a security decision put to someone at the moment they are frustrated and
     * will press whatever dismisses the dialog.
     */
    private void ejected(String url) {
        String host = Origins.hostOf(url);
        setBar(host + " — outside " + site.label);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Leaving " + site.label)
                .setMessage(site.label + " is limited to "
                        + String.join(", ", fence.effective(site))
                        + ".\n\n" + host + " is somewhere else, so it does not open here.")
                .setPositiveButton("Open in browser", (d, w) -> browser(url))
                .setNegativeButton("Stay", (d, w) -> {})
                .setNeutralButton("Always allow " + host, (d, w) -> {
                    fence.allow(url);
                    Log.i(TAG, site.id + ": allowed " + host);
                    load(url);
                })
                // Restore what is actually on screen, not the app's first
                // origin. Resetting to origins[0] left the bar reading
                // news.ycombinator.com over a page served by tailscale.com --
                // the one element meant to be trustworthy, quietly lying.
                .setOnDismissListener(d -> setBar(currentHost != null ? currentHost
                        : (site.origins.length > 0 ? site.origins[0] : site.home)))
                .show();
    }

    private void load(String url) {
        try { sessionC.getMethod("loadUri", String.class).invoke(session, url); }
        catch (Throwable t) { Log.e(TAG, "loadUri: " + t); }
    }

    /** Outside the fence is the ordinary web, and that is the browser's job. */
    private void browser(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "no browser for " + url);
        }
    }
}
