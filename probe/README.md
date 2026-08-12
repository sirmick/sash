# probe

Two experiments answering one architectural question: **can a tiny APK borrow a
browser engine from another package, and run it under its own uid?**

Yes. `loader` is **15 KB**, contains no engine, and renders a real page using
GeckoView out of `com.pane`'s 513 MB APK — in its own process, under its own
uid, with its own permissions.

That matters because it is the only arrangement that satisfies all of:

- separate APKs, tiny, easily minted per site
- **one** copy of the engine on disk
- per-app permissions — the camera can be granted to one app and not another
- per-app profile separation, enforced by the kernel rather than by the engine

Every other arrangement gives up one of those. It is also, not coincidentally,
how Android's own WebView works — except WebView gets there with a system
provider and a dedicated linker namespace, neither of which an app can use.

## What each one is

| | |
| --- | --- |
| `loadprobe` | The minimal question: can another package's dex and `.so` be loaded at all? ~60 lines. |
| `loader` | The whole thing: graft, preload, resource redirection, and a rendered page. |

## What it took, in the order the walls appeared

**Dex** was never a problem. `PathClassLoader` over the other package's APK
loads `org.mozilla.geckoview.GeckoRuntime` immediately — `/data/app/…/base.apk`
is world-readable.

**Native code** failed in a way that looked fatal and was not:

```
dlopen failed: library "libgkcodecs.so" not found:
needed by …/base.apk!/lib/x86_64/libxul.so in namespace clns-9
```

That is a *search path* problem, not an access problem. It had already found and
opened `libxul.so`. Giving a classloader `<apk>!/lib/<abi>` as its native library
search path makes `findLibrary` resolve, and loading the libraries in dependency
order — deepest first — satisfies what Gecko's own `dlopen` later asks for.
GeckoView stores its `.so` files uncompressed *inside* the APK
(`extractNativeLibs=false`), so the extracted `lib/` directory is empty and this
zip-path form is the only one that works.

**Resources** were the segfault. `libxul` loaded and then died on a null
dereference during init, because Gecko finds `omni.ja` by asking the Context
where its APK is — and this one is 15 KB with nothing in it. `CoreContext`
answers with core's path instead, and `getApplicationContext()` returns itself so
nothing can reach around it.

**The 89 child-process services** must be declared in the *loader's* manifest.
Android starts child processes from the manifest of the package hosting them, so
a loader that omits them gets a parent process and nothing else. Same for
GeckoView's permissions — without the AAR dependency none of them are inherited,
and the first symptom is `SecurityException: … ACCESS_NETWORK_STATE` long after
the interesting parts already worked.

## What is not proven

- **Profile separation between two loaders.** Expected to be free — separate
  packages, separate data directories — but unverified.
- **Per-app permissions actually differing.** Same.
- **Version coupling.** The loader lifts core's service names and permissions at
  build time. An engine update that renames either breaks every loader silently.
- The reflection touches `BaseDexClassLoader.pathList`,
  `DexPathList.dexElements` and `nativeLibraryPathElements`. All logged as
  `unsupported … allowed` — greylisted rather than blocked — but that is a
  policy which has tightened before.

## The honest reading

The blockers are all *ergonomic*, not fundamental: paths, manifests,
declarations. Which is the strongest argument yet for modifying GeckoView
rather than working around it — every wall here is Gecko assuming it lives in
the app that embeds it, and each is a small, well-defined thing to make
configurable.

## Running

Requires `com.pane` installed as the engine, then:

```
gradle -p loader assembleDebug
adb install -r loader/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.loader/.SiteActivity
```
