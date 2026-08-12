package com.probe;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.util.Log;
import dalvik.system.DexClassLoader;
import dalvik.system.PathClassLoader;

/**
 * Can a small APK borrow another package's engine?
 *
 * Two separate questions, because they fail for different reasons:
 *   1. dex  — can we load com.pane's *classes* into our process?
 *   2. .so  — can we load com.pane's *native code* into our process?
 *
 * (2) is the one that decides the architecture. WebView solves it with a
 * dedicated linker namespace, which is a system mechanism apps cannot use.
 */
public class MainActivity extends Activity {
    private static final String TAG = "loadprobe";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo("com.pane", 0);
            Log.i(TAG, "apk=" + ai.sourceDir);
            Log.i(TAG, "libdir=" + ai.nativeLibraryDir);

            // 1. dex
            try {
                ClassLoader cl = new PathClassLoader(ai.sourceDir, ai.nativeLibraryDir, getClassLoader());
                Class<?> c = cl.loadClass("org.mozilla.geckoview.GeckoRuntime");
                Log.i(TAG, "DEX: OK — loaded " + c.getName());
            } catch (Throwable t) {
                Log.e(TAG, "DEX: FAILED — " + t);
            }

            // 2. Give a classloader the OTHER package's APK as its native
            //    library search path. Android accepts "<apk>!/lib/<abi>" here,
            //    which is how an app with extractNativeLibs=false finds its own.
            //    If the namespace built for this loader includes that path, the
            //    transitive deps that just failed should resolve.
            String libPath = ai.sourceDir + "!/lib/x86_64";
            ClassLoader shared = new PathClassLoader(ai.sourceDir, libPath, getClassLoader());
            try {
                java.lang.reflect.Method fl =
                        ClassLoader.class.getDeclaredMethod("findLibrary", String.class);
                fl.setAccessible(true);
                Log.i(TAG, "SEARCHPATH: findLibrary(xul) -> " + fl.invoke(shared, "xul"));
            } catch (Throwable t) {
                Log.e(TAG, "SEARCHPATH: " + t);
            }

            // 3. Load the dependency chain by hand, deepest first. If the only
            //    problem was search order, this resolves it.
            String[] chain = {"libmozglue.so", "libnss3.so", "libsoftokn3.so",
                              "libfreebl3.so", "libmozavutil.so", "libmozavcodec.so",
                              "libgkcodecs.so", "liblgpllibs.so", "libclearkey.so",
                              "libcrashtools.so", "libcrashhelper.so", "libxul.so"};
            for (String lib : chain) {
                try {
                    System.load(ai.sourceDir + "!/lib/x86_64/" + lib);
                    Log.i(TAG, "CHAIN: OK   " + lib);
                } catch (Throwable t) {
                    String m = String.valueOf(t);
                    Log.e(TAG, "CHAIN: FAIL " + lib + " — "
                            + m.substring(0, Math.min(160, m.length())));
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "probe failed: " + t);
        }
        finish();
    }
}
