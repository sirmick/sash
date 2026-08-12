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
     * when resolving one library's dependencies on another, so Gecko's own
     * dlopen of libxul fails on the first sibling it needs. Loading them here
     * first means those sonames are already resident.
     *
     * The set is read out of core's APK rather than written down. Measured
     * across GeckoView 140 to 153 — fourteen months — the library set gained
     * two and lost one, so a hardcoded list is a slow-acting break: a removed
     * library is merely noisy, a *new* one that libxul needs is fatal, and
     * neither is visible until a page fails to load.
     *
     * Order is discovered by repetition rather than known: load what will load,
     * repeat while progress is being made. Dependencies resolve themselves.
     */
    static void preload(Context ctx) throws Exception {
        String apk = apkPath(ctx);
        String prefix = "lib/" + abi() + "/";
        java.util.List<String> pending = new java.util.ArrayList<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> e = zip.entries();
            while (e.hasMoreElements()) {
                String name = e.nextElement().getName();
                if (name.startsWith(prefix) && name.endsWith(".so")) pending.add(name);
            }
        }
        // libxul is Gecko's to load, with its own flags. We only owe it its deps.
        pending.removeIf(n -> n.endsWith("/libxul.so"));

        int loaded = 0;
        for (boolean progress = true; progress && !pending.isEmpty(); ) {
            progress = false;
            for (java.util.Iterator<String> it = pending.iterator(); it.hasNext(); ) {
                String name = it.next();
                try {
                    System.load(apk + "!/" + name);
                    it.remove();
                    loaded++;
                    progress = true;
                } catch (Throwable ignored) {
                    // Probably a dependency not yet loaded; another pass may fix it.
                }
            }
        }
        Log.i(TAG, "preload: " + loaded + " loaded, " + pending.size() + " unresolved");
        for (String n : pending) Log.w(TAG, "  unresolved: " + n);
    }

    private static String abi() {
        return android.os.Build.SUPPORTED_ABIS[0];
    }

    private Core() {}
}
