package com.manager;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * The catalogue: a list of sites, and one button each.
 *
 * Installing a site does not download an engine. Every site app is ~19KB and
 * borrows Gecko from the engine package at runtime, which is why a catalogue of
 * a hundred sites costs a couple of megabytes rather than fifty gigabytes.
 */
public class CatalogueActivity extends Activity {
    private static final String TAG = "manager";
    private static final String ENGINE = "com.pane";
    private LinearLayout list;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        list.setPadding(pad, pad, pad, pad);
        scroll.addView(list);
        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        list.removeAllViews();
        list.addView(heading("Sites"));
        // The size is measured, not stated. It is the whole claim of this
        // screen — a catalogue of a hundred sites costs a couple of megabytes
        // rather than fifty gigabytes — and a number typed into a string is a
        // number that goes quietly wrong the first time an icon changes.
        long total = 0;
        for (Site s : Catalogue.ALL) total += sizeOf(s.asset);
        list.addView(body(installed(ENGINE)
                ? "Engine installed. These " + Catalogue.ALL.length + " sites are "
                  + kb(total) + " in total, because they share it."
                : "Engine (" + ENGINE + ") is NOT installed — site apps will not run."));

        for (Site s : Catalogue.ALL) {
            boolean have = installed(s.pkg);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(12), 0, dp(12));

            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.addView(title(s.label));
            text.addView(small(s.detail() + "  ·  " + kb(sizeOf(s.asset))));
            LinearLayout.LayoutParams grow =
                    new LinearLayout.LayoutParams(0, -2, 1f);
            row.addView(text, grow);

            Button action = new Button(this);
            action.setText(have ? "Open" : "Install");
            action.setOnClickListener(v -> { if (have) open(s); else install(s); });
            row.addView(action);

            if (have) {
                Button rm = new Button(this);
                rm.setText("Remove");
                rm.setOnClickListener(v -> uninstall(s));
                row.addView(rm);
            }
            list.addView(row);
        }
    }

    private boolean installed(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    /**
     * How big a site app actually is.
     *
     * openFd rather than reading the stream: the APKs are stored uncompressed
     * (noCompress += "apk"), so the length is already known and no bytes move.
     * A site missing from the assets reports 0 rather than throwing — the
     * catalogue is generated and the assets are copied in by the build, and
     * this screen should say so rather than crash if the two disagree.
     */
    private long sizeOf(String asset) {
        try (android.content.res.AssetFileDescriptor fd = getAssets().openFd(asset)) {
            return fd.getLength();
        } catch (Exception e) {
            Log.w(TAG, "no asset " + asset);
            return 0;
        }
    }

    private static String kb(long bytes) {
        return bytes <= 0 ? "missing" : (bytes + 512) / 1024 + " KB";
    }

    /** Streams the site's APK straight out of our assets into a session. */
    private void install(Site s) {
        try {
            PackageInstaller pi = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int id = pi.createSession(params);
            try (PackageInstaller.Session session = pi.openSession(id);
                 InputStream in = getAssets().open(s.asset);
                 OutputStream out = session.openWrite("apk", 0, -1)) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                session.fsync(out);
            }
            try (PackageInstaller.Session session = pi.openSession(id)) {
                Intent cb = new Intent(this, InstallResult.class);
                PendingIntent pending = PendingIntent.getBroadcast(this, id, cb,
                        PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                session.commit(pending.getIntentSender());
            }
            Log.i(TAG, "install session " + id + " committed for " + s.pkg);
        } catch (Exception e) {
            Log.e(TAG, "install failed", e);
            Toast.makeText(this, "install failed: " + e, Toast.LENGTH_LONG).show();
        }
    }

    private void open(Site s) {
        Intent i = getPackageManager().getLaunchIntentForPackage(s.pkg);
        if (i != null) startActivity(i); else Toast.makeText(this, "no launcher", Toast.LENGTH_SHORT).show();
    }

    private void uninstall(Site s) {
        startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + s.pkg)));
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
    private TextView heading(String t) { TextView v = mk(t, 24); v.setPadding(0,0,0,dp(8)); return v; }
    private TextView title(String t)   { return mk(t, 17); }
    private TextView body(String t)    { TextView v = mk(t, 13); v.setPadding(0,0,0,dp(12)); return v; }
    private TextView small(String t)   { TextView v = mk(t, 12); v.setTextColor(Color.GRAY); return v; }
    private TextView mk(String t, int sp) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }
}
