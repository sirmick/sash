package com.pane;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

/**
 * One app, one task, one card in Recents.
 *
 * Each app being its own Activity is the whole design, and it is what makes the
 * thing feel installed rather than browsed. Android's task system then supplies
 * for free what a single-activity compositor makes you build by hand: a recents
 * entry per app with its own name and colour, switching between apps, and a
 * back stack that behaves. Nothing composites over anything, so the trust bar
 * is simply a view above the content in the same window.
 */
public class AppActivity extends Activity {

    public static final String EXTRA_APP = "app";

    private App app;
    private GeckoSession session;
    private GeckoView view;
    private TextView bar;
    private boolean canGoBack;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        String id = getIntent().getStringExtra(EXTRA_APP);
        app = Apps.byId(id);
        if (app == null) {
            Toast.makeText(this, "unknown app: " + id, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // The recents card wears the app's name and colour, not ours. The
        // Builder is required for the label to take — the deprecated
        // constructor sets the colour and the system keeps showing the package
        // label, which is how every card ended up reading "pane".
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            setTaskDescription(new ActivityManager.TaskDescription.Builder()
                    .setLabel(app.label)
                    .setPrimaryColor(app.color)
                    .build());
        } else {
            setTaskDescription(new ActivityManager.TaskDescription(app.label, null, app.color));
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0b0e12"));

        bar = new TextView(this);
        bar.setGravity(Gravity.CENTER);
        bar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        bar.setTextColor(Color.WHITE);
        bar.setBackgroundColor(app.color);
        int pad = (int) (6 * getResources().getDisplayMetrics().density);
        bar.setPadding(pad, pad, pad, pad);
        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        view = new GeckoView(this);
        root.addView(view, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        // Below the status bar rather than behind it: the bar is the one thing
        // on screen the user is meant to be able to trust, and half of it under
        // the clock is not a cosmetic problem.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            bar.setPadding(pad, pad + top, pad, pad);
            return insets;
        });

        // Ask *before* creating it: a session that already exists is holding a
        // live page, and pointing it at home again would throw away whatever
        // the user was doing.
        boolean fresh = !Sessions.isLive(app.id);

        session = Sessions.of(this, app);
        session.setNavigationDelegate(new Lock());
        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStop(GeckoSession s, boolean success) {
                if (!success) Log.i(Sessions.TAG, app.id + ": load failed");
            }
        });

        view.setSession(session);
        setBar(app.origins.length > 0 ? app.origins[0] : app.home);

        // Only navigate on first open. A relaunch — from the shortcut, from
        // recents, after a rotation — must find the page where it was left.
        if (fresh) session.loadUri(app.home);
    }

    private void setBar(String host) {
        bar.setText(host);
    }

    /** Back walks the page's history before it leaves the app. */
    @Override
    public void onBackPressed() {
        if (canGoBack) {
            session.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        // Release the view's claim without closing the session: the page stays
        // alive for the next activity instance.
        if (view != null) view.releaseSession();
        super.onDestroy();
    }

    /**
     * The fence, enforced on every navigation the page attempts.
     *
     * GeckoView is materially better than WebView here. `onLoadRequest` is
     * consulted for server-side redirects too — `LoadRequest.isRedirect` says
     * so — where WebView's equivalent silently is not, and that gap let a page
     * render under another app's name until it was caught at commit time.
     * Subframes have their own callback, so federated login inside an iframe is
     * not mistaken for the surface leaving its fence.
     */
    private final class Lock implements GeckoSession.NavigationDelegate {

        @Override
        public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest req) {
            String url = Origins.upgrade(req.uri);
            if (Origins.allowed(url, app.origins)) {
                setBar(Origins.hostOf(url));
                return GeckoResult.fromValue(AllowOrDeny.ALLOW);
            }
            App sibling = Apps.siblingOwning(app, url);
            if (sibling != null) {
                Log.i(Sessions.TAG, app.id + ": " + url + " belongs to " + sibling.id);
                handOver(sibling, url);
                return GeckoResult.fromValue(AllowOrDeny.DENY);
            }
            Log.i(Sessions.TAG, app.id + ": EJECTED " + url);
            ejected(url);
            return GeckoResult.fromValue(AllowOrDeny.DENY);
        }

        /**
         * Subframes are not fenced. The allowlist governs where the *app* may
         * go, not what a page may embed — and federated login runs in iframes,
         * so fencing them breaks every sign-in while looking like the provider's
         * fault. What a page may load, as opposed to navigate to, is per-origin
         * network policy: a different mechanism.
         */
        @Override
        public GeckoResult<AllowOrDeny> onSubframeLoadRequest(GeckoSession s, LoadRequest req) {
            return GeckoResult.fromValue(AllowOrDeny.ALLOW);
        }

        @Override
        public void onCanGoBack(GeckoSession s, boolean value) {
            canGoBack = value;
        }
    }

    /** Open the sibling that owns this URL, in its own task. */
    private void handOver(App sibling, String url) {
        Toast.makeText(this, "Opening " + sibling.label, Toast.LENGTH_SHORT).show();
        startActivity(AppActivity.intentFor(this, sibling));
    }

    /**
     * A blocked navigation, said out loud.
     *
     * Silence here is the worst failure this design has: the page simply stops,
     * which reads as the site being broken rather than as the app refusing.
     */
    private void ejected(String url) {
        String host = Origins.hostOf(url);
        Toast.makeText(this, app.label + " does not go to " + host, Toast.LENGTH_LONG).show();
        setBar(host + " — blocked");
        bar.postDelayed(() -> setBar(app.origins.length > 0 ? app.origins[0] : app.home), 4000);
    }

    /** The intent that opens an app, used by the launcher and by pinned shortcuts. */
    public static Intent intentFor(android.content.Context ctx, App app) {
        Intent i = new Intent(ctx, AppActivity.class);
        i.setAction(Intent.ACTION_VIEW);
        // Distinct data per app so each gets its own task and its own recents
        // card rather than all of them reusing one.
        i.setData(android.net.Uri.parse("pane://app/" + app.id));
        i.putExtra(EXTRA_APP, app.id);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        return i;
    }
}
