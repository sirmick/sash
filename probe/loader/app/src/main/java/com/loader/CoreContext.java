package com.loader;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;

/**
 * A Context that tells Gecko it lives in core's APK.
 *
 * Gecko's native side finds omni.ja and its other resources by asking the
 * Context where the APK is — and ours is 15KB with nothing in it. Loading
 * libxul worked; initialising it segfaulted, because it went looking for
 * resources in com.loader and found none.
 *
 * getApplicationContext returns this rather than the real one, since anything
 * that reaches through to the application Context would otherwise get the
 * undoctored answer.
 */
final class CoreContext extends ContextWrapper {
    private final ApplicationInfo info;
    private final Resources res;
    private final String apk;

    CoreContext(Context base, String coreApk, Resources merged) {
        super(base);
        this.apk = coreApk;
        this.res = merged;
        this.info = new ApplicationInfo(base.getApplicationInfo());
        this.info.sourceDir = coreApk;
        this.info.publicSourceDir = coreApk;
        this.info.splitSourceDirs = null;
        this.info.splitPublicSourceDirs = null;
    }

    @Override public ApplicationInfo getApplicationInfo() { return info; }
    @Override public String getPackageResourcePath() { return apk; }
    @Override public String getPackageCodePath() { return apk; }
    @Override public Resources getResources() { return res; }
    @Override public AssetManager getAssets() { return res.getAssets(); }
    @Override public Context getApplicationContext() { return this; }
}
