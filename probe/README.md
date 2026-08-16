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
| `wvprobe` | What the system WebView tells every site about the app hosting it. |
| `manager` | The catalogue. Lists sites, installs one via `PackageInstaller`, opens and removes it. Its list and its package-visibility queries are generated from `catalogue/*.json`, never written by hand — they were written by hand once, and went stale the day the catalogue changed. |

## Why not just use the system WebView

Because on GrapheneOS, Vanadium *is* the WebView provider — so a site app could
be 50 KB with no loader trick at all, sharing an engine the OS updates, JIT-less
and site-isolated, with its own uid and cookies for free. Everything this
experiment builds by hand, supported.

`wvprobe` measures the reason not to. Chromium 145, Android 17, echoing our own
request headers back:

```
x-requested-with : com.wvprobe
sec-ch-ua        : "Android WebView";v="145", "Chromium";v="145"
user-agent       : ... Build/CP2A.260605.016; wv) ... Chrome/145 ...
```

Three separate tells, not one: the exact package name, client hints naming
"Android WebView", and the `wv` token in the User-Agent. This is what Google
refuses sign-in on, re-measured years after it was first found.

Vanadium would have to suppress all three. Its 287 patches are hardening and
build plumbing — JIT-less, strict site isolation, 64-bit WebView processes —
and a code search across GrapheneOS for `X-Requested-With` returns nothing. It
is a hardening fork, not a behaviour fork.

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

    manager (687 KB, carrying every site app in the catalogue)
      → catalogue lists Chase, Facebook, Google, Instagram, StockPlan, Schwab
      → tap Install → Android's own "Install this app? Chase" prompt
      → com.loader.chase installed, a real launcher entry
      → tap Open → chase.com renders, in its own app

Six packages, one engine, built from `catalogue/*.json` by `scripts/mint.py`:

```
app-chase-debug.apk           85 KB
app-facebook-debug.apk        85 KB
app-google-debug.apk         130 KB   ← four surfaces, four icons
app-instagram-debug.apk      105 KB
app-morganstanley-debug.apk   92 KB
app-schwab-debug.apk          88 KB
```

**These are larger than the 15 KB earlier in this document, and the difference
is entirely icons.** The loader still contains no engine; a site app is a
manifest, one class per surface, a `site.json` and a PNG per icon. The engine it
renders with is 513 MB and is installed once.

The manager measures these at runtime rather than printing a number someone
typed: the claim it exists to make is about size, so it should not be able to
make it wrongly.

Permission ceilings are set at install and not changeable afterwards, and the
catalogue says so before you install — *"chase.com · network only"*. Every entry
shipped today declares nothing, which is the default and the point; an entry
that declares a permission has to carry a written reason, and minting refuses
one that does not.

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

## Autofill reaches a site app

It does, and the investigation that said otherwise was measuring the wrong
thing.

```
autofill: form on secure.chase.com     ← latch, invoked by a 35 KB site app
AutofillSupport lines: 4
```

The system is joined: a site app borrowing an engine from another package, under
its own uid, gets passwords from the vault.

### The hours lost, and to what

Every test had been run against **news.ycombinator.com**, whose login form is
`<input type=text name=acct>` and predates every autofill heuristic Gecko has.
Gecko never reports it, so Android is never told there are fields, so latch is
never asked. Pointing the same build at chase.com produced four
`AutofillSupport` lines and a fill request immediately.

Along the way these were each eliminated, correctly and pointlessly, because the
app was never the variable:

| | |
| --- | --- |
| the graft | `direct` — engine compiled in — failed identically |
| `mAutofillEnabled` default | `iconst_1` in GeckoView's constructor |
| `registerListeners()` | runs; the no-arg session delegates to the constructor that registers |
| session attach ordering | moved both ways |
| `setActive(true)` | called explicitly |
| `GeckoRuntime.getDefault` vs `create(settings)` | matched to pane |
| bare session vs `GeckoSessionSettings` | matched to pane |
| the reflective `Proxy` delegate | removed entirely |

Seven hypotheses about the app, and the page was never held up against a known-
good one. `direct` and the matched runtime/session construction stay — they are
a genuine control worth keeping — but they were answers to a question nobody was
asking.

The lesson is cheap to state and was expensive here: when something works in one
place and not another, **vary one thing at a time starting with the input**, not
the machinery.

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

The whole system, on an attached device, in one command:

```
../scripts/provision.sh
```

Or just the site apps — requires `com.pane` installed as the engine:

```
python3 ../scripts/mint.py              catalogue → flavours
gradle -p loader assembleDebug          one APK per catalogue entry
adb install -r loader/app/build/outputs/apk/chase/debug/app-chase-debug.apk
```

The fence has unit tests that run off-device, `../scripts/test.sh fence`.
