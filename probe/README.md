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
| `loader` | A real site app: shared engine, own uid, own permissions, own profile, origin-locked, recording what it blocks. One product flavour per site, configured by a `site.json` asset. |
| `manager` | The catalogue. Lists sites, installs one via `PackageInstaller`, opens and removes it. |

## The site app

`site.json` is the catalogue entry, baked in at mint time and read at startup —
so an app cannot drift from the entry describing it, and minting a new site is a
manifest plus one file rather than a patch:

```json
{ "id": "news", "label": "Hacker News",
  "home": "https://news.ycombinator.com/",
  "origins": ["news.ycombinator.com"], "permissions": [] }
```

Everything pane's monolith did, the loader now does with an engine it does not
contain: a trust bar above the surface, the fence enforced on every top-level
navigation, subframes deliberately unfenced so federated sign-in still works,
ejections recorded, and "Always allow" offered as the quiet third option.

## End to end

    manager (75 KB, carrying three site apps)
      → catalogue lists Wikipedia, Hacker News, Meet
      → tap Install → Android's own "Install this app? Wikipedia" prompt
      → com.loader.wikipedia installed, a real launcher entry
      → tap Open → Wikipedia renders, in its own app

Measured afterwards:

```
com.loader.wikipedia   CAMERA in manifest: no
com.loader.meet        CAMERA in manifest: yes
```

Two apps from one source tree, sharing one engine, with different permission
ceilings — set at install and not changeable afterwards. The catalogue says so
before you install: *"en.m.wikipedia.org · network only"* against *"meet.jit.si
· network, camera, microphone"*.

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

## Profiles and permissions: measured

Two flavours of the same source, `alpha` and `beta`, sharing one engine. Only
`beta` declares CAMERA:

```
alpha   uid=10136   camera: DENIED    /data/user/0/com.loader.alpha/mozilla
beta    uid=10137   camera: GRANTED   /data/user/0/com.loader.beta/mozilla
```

Different uids, different data directories, a Gecko profile each. And
`pm grant android.permission.CAMERA com.loader.alpha` **reports success and does
nothing** — `dumpsys` shows no CAMERA entry for alpha at all, because a package
that never requested a permission cannot be given it. That is the guarantee: not
policy, not our code, the package manifest.

Minting is a build variant. Same source, different applicationId, label and
permission set; `assembleAlphaDebug` produces a 19 KB app.

## What is not proven

- **Version coupling.** The loader lifts core's service names and permissions at
  build time. An engine update that renames either breaks every loader silently.
- **On-device minting.** Flavours prove a site app is a build target, not a
  project. Generating and signing one *on the phone* is untested, and signing is
  where it gets interesting.
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
