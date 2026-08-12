package com.manager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;
import android.widget.Toast;

/** Where PackageInstaller reports back, including the confirmation prompt. */
public class InstallResult extends BroadcastReceiver {
    private static final String TAG = "manager";

    @Override public void onReceive(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        String pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // The user confirms every install. Not a formality — it is the
            // moment a site becomes an app with its own identity.
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(confirm);
            }
            return;
        }
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Log.i(TAG, "install " + pkg + ": status=" + status + " " + msg);
        Toast.makeText(ctx, status == PackageInstaller.STATUS_SUCCESS
                ? "installed " + pkg : "install failed: " + msg, Toast.LENGTH_SHORT).show();
    }
}
