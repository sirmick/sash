package com.loader;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.Log;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import dalvik.system.PathClassLoader;

/**
 * Borrowing an engine from another package.
 *
 * The loader ships no Gecko at all — 50KB against core's 500MB. At startup it
 * splices core's dex and native libraries into its own classloader, so every
 * Gecko class and every .so resolves as though they had been compiled in.
 *
 * The code then runs in *this* process, under *this* uid, with *this* app's
 * permissions — which is the whole reason to do it. A shared engine that ran in
 * the engine's process would share the engine's permissions too.
 */
final class Core {
    static final String PACKAGE = "com.pane";
    private static final String TAG = "loader";

    static String apkPath(Context ctx) throws Exception {
        ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(PACKAGE, 0);
        return ai.sourceDir;
    }

    /** Splice core's dex + native paths into the classloader we already have. */
    static void graft(Context ctx, ClassLoader target) throws Exception {
        String apk = apkPath(ctx);
        String libs = apk + "!/lib/" + abi();
        Log.i(TAG, "grafting " + apk);

        Class<?> bdcl = Class.forName("dalvik.system.BaseDexClassLoader");
        Field pathListF = bdcl.getDeclaredField("pathList");
        pathListF.setAccessible(true);

        Object mine = pathListF.get(target);
        // A throwaway loader over core, purely to have Android build the
        // elements for us rather than constructing them by hand.
        ClassLoader donor = new PathClassLoader(apk, libs, target.getParent());
        Object theirs = pathListF.get(donor);

        merge(mine, theirs, "dexElements");
        merge(mine, theirs, "nativeLibraryPathElements");
    }

    private static void merge(Object mine, Object theirs, String field) throws Exception {
        Field f = mine.getClass().getDeclaredField(field);
        f.setAccessible(true);
        Object a = f.get(mine), b = f.get(theirs);
        int la = Array.getLength(a), lb = Array.getLength(b);
        Object merged = Array.newInstance(a.getClass().getComponentType(), la + lb);
        System.arraycopy(a, 0, merged, 0, la);
        System.arraycopy(b, 0, merged, la, lb);
        f.set(mine, merged);
        Log.i(TAG, "  " + field + ": " + la + " + " + lb);
    }

    /**
     * Core's resources, merged over ours.
     *
     * Gecko needs its own assets — omni.ja above all — and PathClassLoader
     * carries dex, not resources. addAssetPath is the only way in.
     */
    static Resources resources(Context ctx, Resources base) throws Exception {
        AssetManager am = AssetManager.class.newInstance();
        Method add = AssetManager.class.getMethod("addAssetPath", String.class);
        add.invoke(am, ctx.getPackageResourcePath());
        add.invoke(am, apkPath(ctx));
        return new Resources(am, base.getDisplayMetrics(), base.getConfiguration());
    }

    /**
     * Load Gecko's libraries into the process before Gecko does.
     *
     * The grafted namespace can *find* each library but does not search itself
     * when resolving one library's dependencies on another — so Gecko's own
     * dlopen of libxul fails on the first sibling it needs. Loading them here,
     * dependency order first, means those sonames are already resident and
     * resolve when libxul finally asks.
     *
     * libxul is deliberately absent: GeckoLoader loads it with its own flags,
     * and this only has to satisfy what it depends on.
     */
    static void preload(Context ctx) throws Exception {
        String base = apkPath(ctx) + "!/lib/" + abi() + "/";
        String[] order = {
            "libmozglue.so", "libnss3.so", "libsoftokn3.so", "libfreebl3.so",
            "libgkcodecs.so", "libmozavutil.so", "libmozavcodec.so",
            "liblgpllibs.so", "libclearkey.so", "libcrashtools.so",
            "libcrashhelper.so"
        };
        int ok = 0;
        for (String lib : order) {
            try { System.load(base + lib); ok++; }
            catch (Throwable t) { Log.w(TAG, "preload " + lib + ": " + t); }
        }
        Log.i(TAG, "preload: " + ok + "/" + order.length);
    }

    private static String abi() {
        return android.os.Build.SUPPORTED_ABIS[0];
    }

    private Core() {}
}
