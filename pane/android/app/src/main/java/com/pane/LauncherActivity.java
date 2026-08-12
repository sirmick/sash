package com.pane;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * A list, and a way to put each app on the home screen.
 *
 * Deliberately plain. The point of pinning is that the apps stop needing this
 * screen at all — a shortcut lands on *whatever launcher the user already
 * has*, so the icons are ordinary home-screen icons rather than a grid inside
 * our app. A launcher of our own is a later argument, not a prerequisite.
 */
public class LauncherActivity extends Activity {
    /** Where a proposed catalogue entry goes. */
    private static final String REPO = "sirmick/sash";


    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0b0e12"));
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad * 3, pad, pad);

        for (App app : Apps.all()) {
            root.addView(row(app));
            View learned = learned(app);
            if (learned != null) root.addView(learned);
        }

        TextView note = new TextView(this);
        note.setText("Google Account and Gmail share one jar, so signing in to "
                + "either signs in both. Chase shares nothing with either.");
        note.setTextColor(Color.parseColor("#8b98a5"));
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        note.setPadding(0, pad, 0, 0);
        root.addView(note);

        setContentView(root);
    }

    /**
     * What this app's fence has learned, and an offer to send it upstream.
     *
     * Shown only when there is something to show. `seen` is the useful half:
     * hosts the app reached for and was refused, which is the one thing nobody
     * works out by reading a site's documentation. gds.google.com was invisible
     * until a real sign-in ejected on it.
     *
     * Nothing leaves the device by itself. The button opens a prefilled GitHub
     * editor and the user reads it before proposing anything — a list of hosts
     * a phone visited is a record of what its owner was doing, and some
     * hostnames carry identifiers.
     */
    private View learned(App app) {
        Fence fence = new Fence(this, app);
        java.util.Set<String> allowed = fence.allowed();
        java.util.Set<String> seen = fence.seen();
        if (allowed.isEmpty() && seen.isEmpty()) return null;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        box.setPadding(pad * 2, 0, pad, pad);

        if (!allowed.isEmpty()) box.addView(note("added here: " + String.join(", ", allowed)));
        if (!seen.isEmpty()) box.addView(note("blocked, not added: " + String.join(", ", seen)));

        Button propose = new Button(this);
        propose.setText("Propose entry");
        propose.setOnClickListener(v -> {
            java.util.Set<String> all = new java.util.TreeSet<>(fence.proposable(app));
            all.addAll(seen);
            android.util.Log.i(Sessions.TAG, "proposing " + app.id + ":\n" + Fence.entry(app, all));
            startActivity(new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse(Fence.proposeUrl(REPO, app, all))));
        });
        box.addView(propose);
        return box;
    }

    private TextView note(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.parseColor("#8a94a6"));
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        return v;
    }

    private View row(App app) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (10 * getResources().getDisplayMetrics().density);
        row.setPadding(0, pad, 0, pad);

        TextView name = new TextView(this);
        name.setText(app.label + "\n" + app.contextId + " · " + String.join(", ", app.origins));
        name.setTextColor(Color.WHITE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        row.addView(name, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button open = new Button(this);
        open.setText("Open");
        open.setOnClickListener(v -> startActivity(AppActivity.intentFor(this, app)));
        row.addView(open);

        Button pin = new Button(this);
        pin.setText("Pin");
        pin.setOnClickListener(v -> pin(app));
        row.addView(pin);

        return row;
    }

    /**
     * Put the app on the home screen as an ordinary launcher icon.
     *
     * The user confirms — there is deliberately no API to place an icon without
     * asking, and an app that could would be malware.
     */
    private void pin(App app) {
        ShortcutManager sm = getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Toast.makeText(this, "this launcher does not take pinned shortcuts",
                    Toast.LENGTH_LONG).show();
            return;
        }
        ShortcutInfo shortcut = new ShortcutInfo.Builder(this, app.id)
                .setShortLabel(app.label)
                .setLongLabel(app.label)
                .setIcon(Icon.createWithResource(this, android.R.drawable.ic_menu_compass))
                .setIntent(AppActivity.intentFor(this, app))
                .build();
        sm.requestPinShortcut(shortcut, null);
    }
}
