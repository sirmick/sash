# hush — the Android host

What Android actually imposes, established by building it rather than by
reading about it. Everything here was verified on an Android 16 emulator
(`system-images;android-36;google_apis;x86_64`), SELinux **enforcing**, no root.

---

## Table of contents

- [The host is not a user interface](#the-host-is-not-a-user-interface)
- [Process model](#process-model)
- [Verified constraints](#verified-constraints)
- [What Android does not give you](#what-android-does-not-give-you)
- [The dev shortcut and its expiry](#the-dev-shortcut-and-its-expiry)
- [Development environment](#development-environment)
- [What no emulator can answer](#what-no-emulator-can-answer)
- [Build status](#build-status)

---

## The host is not a user interface

Decision C5 says the chrome holds no OS capabilities. On Android that has a
concrete consequence worth stating before any of the mechanics: **the native app
is a host, not a UI.** The interface is web, rendered in a WebView. Native Kotlin
views appear only where they structurally cannot be web — the trust chrome, and
later the incoming-call screen, which must wake and draw inside about a second
and cannot afford a cold-started surface.

So "the Android app" is: a foreground service supervising the router, a WebView
hosting the chrome, a `ViewGroup` positioning surface WebViews, and — later —
Kotlin providers for the framework APIs Go cannot reach.

At the time of writing the entire host is about 300 lines of Kotlin.

## Process model

```
Android app process (one uid, one sandbox)
  └── RouterService (foreground service, specialUse)
        └── exec filesDir/bin/wash-router  ──symlink──▶  nativeLibraryDir/libwash.so
              ├── scans --apps-dir for wash-* symlinks
              └── spawns each app as a child process
```

**The router is a static Go binary and needs no NDK.** `CGO_ENABLED=0
GOOS=linux` for `arm64` and `amd64` produces binaries that run on Android
unmodified — no `GOOS=android`, no bionic linking. The NDK is installed by the
toolchain script and is currently unused; keep it for the day a dependency pulls
in cgo.

**It ships in `jniLibs` as `libwash.so`.** Since API 29 an app may not exec from
its own data directory, so `nativeLibraryDir` is the only exec-permitted
location, and only files named `lib*.so` land there. `useLegacyPackaging = true`
and `android:extractNativeLibs="true"` are both required, or the binary stays
compressed inside the APK and there is nothing to exec.

**Symlinks carry the multicall dispatch.** wash's multicall dispatches on
`basename(argv[0])`, and `ProcessBuilder` cannot set argv[0] independently of the
executable path — so exec'ing `libwash.so` directly looks for an applet named
`libwash.so` and fails before any subcommand is parsed. The fix is a directory of
symlinks in `filesDir` pointing back at the single real binary: exec resolves
through to the exec-permitted inode while argv[0] keeps the name the dispatcher
and the router's app scanner expect.

This works for the router *and* for the apps the router spawns, which was the
open question when the design was written. It is verified, and it is the single
most load-bearing mechanism in the host.

**Foreground service type is `specialUse`.** Nothing in Android's taxonomy means
"supervises a long-lived daemon", and mislabelling this as `dataSync` or
`mediaPlayback` would be a lie told to the platform. `specialUse` requires a
declared justification property. The persistent notification is unavoidable and
is a real, permanent UX cost.

## Verified constraints

**One uid, one sandbox, for everything.** Every hush process — router, apps,
providers — shares the app's uid. There is therefore *no filesystem isolation
available between hush apps at the OS level*, whatever the router ships. This is
what makes `--fs-root` moot here (it is per-router and advisory anyway; see
`DECISIONS.md`) and what makes capability mediation through the broker not one
option among several but the only one.

**`/proc` is restricted.** `wash-session`'s `host.stats` logs `open /proc/stat:
permission denied` every five seconds. `top`-style apps do not port, and the
chrome must not depend on that data.

**Unix sockets bind fine in `filesDir`.** An earlier `bind: permission denied`
was SELinux denying the *shell* domain in `/data/local/tmp`; it does not occur in
the app domain. The consequence is for the dev loop, not the design: push-and-run
as `shell` needs permissive SELinux, hence root, hence a non-Play system image.

**The FE catalogue is not the router's roster.** With a trimmed multicall the
session shell still offers Notifications, Bulk Ops, Privilege and Network,
producing `app_msg cross-instance: no app "com.wash.notify"` warnings. Harmless,
and moot once the chrome brings its own catalogue — but a reminder that the menu
comes from the bundle, not from what registered.

## WebView capabilities — probed, not assumed

These ship in the WebView APK and track *its* version, not the OS version, which
is why `DECISIONS.md` listed their availability as open. `Probe.kt` reports them
at startup. Verified on two different WebViews — the AVD's
`com.google.android.webview 133.0.6943.137` and Cuttlefish's
`com.android.webview 145.0.7632.218`, which is **AOSP WebView rather than
Google's**, and therefore a useful second data point for the Vanadium question
even though it does not settle it:

| Feature | | Needed for |
| --- | --- | --- |
| `MULTI_PROFILE` | ok | Per-surface profiles. Gates the entire surface model. |
| `DOCUMENT_START_SCRIPT` | ok | Autofill injection and the C4 WebAuthn interception — must run before page script. |
| `WEB_MESSAGE_LISTENER` | ok | The C2 capability port, origin-scoped. |
| `CREATE_WEB_MESSAGE_CHANNEL` / `POST_WEB_MESSAGE` | ok | The MessagePort pair the port rides on. |
| `USER_AGENT_METADATA` | ok | `Sec-CH-UA` overrides, which must agree with the UA string. |
| `SERVICE_WORKER_BASIC_USAGE` | ok | Push and PWA behaviour in surfaces. |
| `OFF_SCREEN_PRERASTER` | ok | Surfaces that are composited but not visible. |
| `WEB_AUTHENTICATION` | ok | Passkeys in surfaces. See below — the flag alone is not the answer. |

**WebAuthn is opt-in per WebView and off by default**, with three levels, so the
feature flag does not settle the passkey question on its own. `FOR_APP` scopes
credentials to the hosting app, which is the wrong shape: a surface
authenticates as `chase.com`, not as `com.hush.shell`. `FOR_BROWSER` — arbitrary
origins, each with its own credentials — is what the design needs, and whether a
non-browser app may ask for it was worth establishing by asking:

```
probe:   ok  webauthn FOR_BROWSER
probe:   ok  webauthn FOR_APP
```

Accepted, with no silent downgrade. The qualification: this is the *settings*
API accepting the level, not a completed ceremony. Whether a real passkey login
succeeds is P5's business (`CHROME.md`).

**Isolation was verified, not inferred.** A cookie written through profile A's
`CookieManager` is invisible to profile B's:

```
probe: cookie in A = hush=a
probe: cookie in B = null
probe: PROFILE ISOLATION HOLDS
```

So every mechanism the architecture depends on exists on stock WebView. Two
qualifications:

- **This is AOSP/Google WebView, not Vanadium.** The GrapheneOS question stays
  open until it runs on a Pixel. The probe is what will answer it in one launch.
- **Profile creation and deletion are not symmetric.** `deleteProfile` throws
  `IllegalStateException: Cannot delete in-use profile` once a profile has been
  touched. Profiles are cheap to make and not cheap to reclaim, which is the
  concrete reason behind I6's "name profiles explicitly in the registry, never
  derived from origin strings" — a profile created by accident is difficult to
  remove.

## Compositing — verified

`SurfaceHost` is three `FrameLayout` layers in one `ViewGroup`:

```
overlay     trust chrome, chrome popups — native, above everything
surfaces    N WebViews, absolutely positioned
chrome      grid / drawer / taskbar — one WebView, full screen
```

Ordering earns its keep twice. **Touch routing falls out for free**: a touch
inside a surface rect hits that surface, anything else falls through to the
chrome beneath. Inverting it — a transparent chrome above the surfaces — buys a
hit-testing problem you then solve by hand. And the **overlay is not
decoration**: the trust chrome must be drawn where content cannot reach, and a
chrome popup that needs to appear *over* a surface cannot come from the chrome
WebView underneath it.

`SpikeActivity` proves the model with no chrome, broker or registry, so it
depends on nothing unbuilt. Two surfaces, two profiles, the **same** origin
loaded into both — the sharpest isolation test available:

```
surface bank:   profile=profile-bank
surface social: profile=profile-social
surface bank:   capability port open for [https://example.com]
spike: setBounds applied to 'bank'
surface social: EJECTED https://example.org/ (not in [example.com])
spike: cookie visible in 'bank'   = "spike=bank"
spike: cookie visible in 'social' = ""
spike: ENGINE ISOLATION HOLDS
spike: 'social' is now at https://example.com/
```

Four things established:

- Two profiled WebViews composite side by side, and `setBounds` repositions a
  live surface rather than only laying it out once.
- **Origin locking ejects rather than follows.** Without it the
  name→icon→origin binding means nothing: one open redirect on the bank's own
  domain and an installed-app surface is showing somebody else's page under the
  bank's chrome.
- **Isolation holds in the engine**, not merely at the `CookieManager` API that
  `Probe` exercised. A cookie set by page script in one surface is invisible to
  page script in the other, at the same origin.
- The capability port binds per surface with origin rules, so a page navigated
  somewhere unexpected never sees the capability object.

Two implementation notes worth keeping:

- `WebViewCompat.setProfile` must be called **before any load**; a WebView that
  has been used cannot be moved between profiles.
- Presentation is applied at the embedder, never by injecting JS — embedder
  overrides land before page script and leave no patched property descriptors.
  On Android that ceiling is the UA string plus `Sec-CH-UA` metadata: `pointer`,
  `hover` and `maxTouchPoints` are not overridable, which is what settles
  `SHELL.md`'s fictional-device question in favour of the Linux-tablet story.

## What a surface tells the network — measured

Cloudflare challenged a hush browsing surface, so the headers were read rather
than reasoned about. Every request carried:

```
user-agent:         Mozilla/5.0 (X11; Linux x86_64) … Chrome/133.0.0.0 …
sec-ch-ua:          "Not:A-Brand";v="99", "Android WebView";v="145", "Chromium";v="145"
sec-ch-ua-platform: "Android"
x-requested-with:   com.hush.shell
```

Three tells, none of them subtle, and two of them ours. The client hints
**named themselves as Android WebView** while the UA claimed desktop Linux; the
platform hint contradicted the UA outright; and the UA claimed Chrome 133 while
the engine was Chromium 145. `SHELL.md` had already written the rule this
violated — *Sec-CH-UA must agree with the UA string; disagreement is itself the
signal* — and the code carried a note saying the metadata was left for later.
Later arrived as a bot challenge.

After `Presentation`:

```
user-agent:         Mozilla/5.0 (X11; Linux x86_64) … Chrome/145.0.0.0 …
sec-ch-ua:          "Not:A-Brand";v="99", "Chromium";v="145", "Google Chrome";v="145"
sec-ch-ua-platform: "Linux"
sec-ch-ua-mobile:   ?0
```

The principle that fixed it: **change the platform story, never the engine
version.** Everything is derived from the WebView actually installed, so the UA
version, the brand list and anything a site can feature-detect all agree.
Claiming an older Chrome is a contradiction any page can check. The UA's minor
and build are frozen to `0.0.0` because that is what real Chrome sends — the
true build number belongs only in the hints.

**`X-Requested-With` survives, and is now the whole gap.** It is sent only by
app-embedded WebViews, which makes it the least ambiguous "not a browser"
signal there is, and it carries the package name to every site. androidx exposes
`setRequestedWithHeaderOriginAllowList` — an empty set suppresses it entirely —
but `REQUESTED_WITH_HEADER_ALLOW_LIST` is **absent on AOSP WebView 145**. The
probe now reports it, so the answer is known per device rather than discovered
from a challenge; Google's WebView and Vanadium are untested.

### Layer 2, measured from inside a live surface

`Audit.kt` reads what a page can actually observe and writes it to
`filesDir/audit.json`. Run under both presentations, the divergences from
desktop Chrome fall into two classes, and the distinction is the useful part.

**Class A — the platform story is inconsistent.** Present only under
`linux-tablet`:

| | `linux-tablet` | desktop Chrome | `android-chrome` |
| --- | --- | --- | --- |
| `pointer` | `coarse` | `fine` | `coarse` ✓ |
| `hover` | none | `hover` | none ✓ |
| `maxTouchPoints` | 2 | 0 | 2 ✓ |
| `ontouchstart` | present | absent | present ✓ |

None of these is settable — Chromium derives them from the real input devices,
and the API that overrides them is the DevTools protocol, which WebView does not
expose to an embedder. So **the Linux-desktop story is provably inconsistent at
layer 2 and cannot be made consistent**, while `android-chrome` is internally
coherent on every one of them.

That is worth stating plainly because it changes what the fiction is *for*.
`linux-tablet` still earns its place — it fires desktop breakpoints, avoids app
interstitials, and puts the surface in a large bucket for anti-linkage. It is
**not** anti-detection, and must not be claimed as such.

**Class B — WebView is not Chrome, whatever platform it claims.** Present under
*both* presentations:

| | Surface | Chrome |
| --- | --- | --- |
| `window.chrome` | absent | `{app, csi, loadTimes, runtime}` |
| `navigator.plugins` | empty | five synthetic PDF entries |
| `navigator.pdfViewerEnabled` | `false` | `true` |

These say "embedded WebView" regardless of the UA, and no embedder API changes
any of them. Only document-start injection could, and that is a fight worth
declining: the puppeteer-stealth arms race exists precisely because a *badly*
faked `window.chrome` is a stronger signal than an absent one.

What is honest and unchanged: `navigator.platform`, `vendor`, `webdriver`,
`colorDepth`, `hardwareConcurrency` and `deviceMemory` all match a plausible
desktop, and `uaData` now agrees with the UA in every field.

The remaining layer-4 items are unchanged and unfixable here: on Cuttlefish the
WebGL renderer reads *"Android Emulator OpenGL ES Translator"*, which is a VM
tell that will not exist on a real phone — though a real phone reports Adreno or
Mali, which is its own tell for anything claiming a Linux desktop.

*(The audit's font-availability probe returned empty for all six families,
including ones Android certainly ships. Treat that line as a defective
measurement rather than a result.)*

## What Android does not give you

Carried forward from `SHELL.md`'s platform deltas, with what building has
confirmed:

| | Reality |
| --- | --- |
| Per-origin network policy | `shouldInterceptRequest` is a policy layer with holes; real enforcement would be `VpnService`, which filters per-uid and so cannot separate one surface from another. Best-effort here, genuinely enforceable only on Linux with per-profile namespaces. |
| Viewport emulation | UA and `Sec-CH-UA` metadata are settable; `devicePixelRatio` may be reachable via a configuration context with an overridden density (unverified); `pointer`, `hover` and `maxTouchPoints` are not overridable at all. That settles `SHELL.md`'s fictional-device choice: **only the Linux-tablet story is available**, because a touchscreen cannot report `pointer: fine`. |
| Settings | `Settings.System` behind a grant; `Settings.Secure` and `Settings.Global` not at all. See `STATE.md`. |
| Vault on loopback | Android has no per-uid loopback isolation, so a bundled Vaultwarden listening on `127.0.0.1` is reachable by every app on the device — the same reasoning C2 used to ban a localhost capability API. **Unresolved, and on the critical path for the credentials plane.** |

## The wire without a listening socket — verified

`WirePipe` carries the shell wire with nothing bound anywhere on the device:

```
page  ⇄  MessagePort  ⇄  WirePipe  ⇄  unix socket (--listen-raw)  ⇄  router
```

The host understands none of it. `--listen-raw` serves the same
length-prefixed frames the WebSocket transport carries, so this is a byte pipe,
not a protocol implementation — **which is why the Kotlin wire client is not a
prerequisite for closing the listener.** Kotlin needs to speak the wire only
when it becomes an app itself, for the SMS and contacts providers.

`WireActivity` + `assets/wire-probe.html` prove it. The page is served by
`WebViewAssetLoader` from the APK under `https://appassets.androidplatform.net`,
which is a real https origin — the page reports `secureContext: true`, so
service workers and WebCrypto survive. A `file://` page would not.

Decoding only the 8-byte header (`[flags:8][channel:24][length:32]`), a cold
start yields a full session bootstrap:

```
 1  interactive  ch 0   141   {"t":"catalog",…
 2  interactive  ch 0    69   {"t":"session.s…
 3  interactive  ch 0   322   {"t":"app.declare…
 4  interactive  ch 0   116   {"t":"channel.bind…
 9  bulk         ch 2  58278
10  control      ch 0    20   {"t":"pong","seq":1}
11  control      ch 0   627   {"t":"link.stats…
```

So multi-class traffic (interactive, bulk, control), dynamic channels, and a
ping/pong round trip all work across the port. `WEB_MESSAGE_ARRAY_BUFFER` is
supported, so frames cross as binary rather than base64; the page keeps a
base64 fallback for WebViews that lack it.

Two notes for whoever wires the real chrome to this:

- The router **does** speak first — the bootstrap arrives before anything is
  sent. An earlier run of this probe appeared silent only because the page logs
  to the screen and not to logcat.
- wash's FE already has the seam this needs: `SocketLike` / `SocketFactory` in
  `web/shell/src/ws.ts`, with virtio-console and relay-channel implementations
  beside the WebSocket one. A MessagePort transport is a fourth sibling, not a
  new concept.

## The dev shortcut and its expiry

The host currently runs the router with `--http --no-auth --listen
127.0.0.1:11000` and points the WebView at it. That is how the shell renders
today, and it is **reachable by every other app on the device** — precisely what
C2 exists to prevent.

It must be replaced by:

1. `WebViewAssetLoader` serving the chrome bundle from APK assets, which also
   yields a real `https://appassets.androidplatform.net` origin so secure-context
   features keep working (`file://` would not).
2. A `MessagePort` injected per surface, with the host piping bytes between it
   and the router's `--listen-raw` unix socket. No Kotlin wire client is needed
   for this — the frames are identical to the WebSocket's.
3. Deleting the listener flags, verified by `ss -lnt` on the device showing
   nothing bound.

Nothing substantial should be built on top of the shortcut, because code written
against a listener acquires assumptions about it.

## Development environment

```
make toolchain     # root-free JDK + SDK/NDK under ~/.local/wash-toolchain/android
make emulator      # headless AVD
make install       # build binary + APK, install
make forward       # shell at http://127.0.0.1:11001/ (while the shortcut lives)
make devtools      # CDP at http://127.0.0.1:9222
make shot          # screenshot to hush.png
```

**Use a Google APIs image, not a Play Store one.** `adb root` is worth more at
this stage than anything Play adds: you can inspect the sandbox, walk the process
tree, and confirm what is and is not bound. Play images are not rootable. Switch
to one later, when you want a current WebView and want to observe how apps behave
on an uncertified device — a useful test in its own right, given S1.

**`-memory 4096` is not optional.** At the AVD default (~2.4G) the guest dies
within a minute of the WebView rendering the shell, and it presents as a lost adb
device rather than anything resembling OOM.

**Forward the CDP socket by pid.** Dead processes leave
`webview_devtools_remote_*` entries in `/proc/net/unix`, and forwarding to a
stale one hangs instead of failing.

**Force the failure modes rather than waiting for them.** This is where an
emulator beats a phone — a real device does these unpredictably, an emulator does
them on demand:

```
adb shell am send-trim-memory <pkg> COMPLETE   # memory pressure
adb shell am kill <pkg>                        # background process death
adb shell dumpsys deviceidle force-idle        # doze
```

plus "Don't keep activities" in Developer Options, which turns every run into a
test of surface save-and-restore — the thing `DECISIONS.md` I6 warns Electron
will never teach you.

### Cuttlefish, and the two things that stop it booting

The AVD's `-gpu host` path wants a window, so a headless session is forced onto
swiftshader, which cannot sustain rendering wash's shell. Cuttlefish is the
answer: gfxstream marshals guest GL/Vulkan to the host GPU with no window, and
the device UI is a web server.

```
sudo scripts/install-cuttlefish.sh && sudo reboot   # once
make cf-fetch     # ~3G of AOSP images
make cf-start     # boot
make cf-ui        # print the UI URL
```

Both of the following cost an afternoon to find and one line each to fix, so
they are recorded rather than left to be rediscovered.

**`EGL_PLATFORM=surfaceless` is not optional on a headless host.** gfxstream's
renderer calls `eglGetDisplay(EGL_DEFAULT_DISPLAY)`; with neither `DISPLAY` nor
`WAYLAND_DISPLAY` set, Mesa selects x11 and `eglInitialize` fails. Cuttlefish
reports that as *"The host does not have GLES support"* — which reads like a
missing driver and is not: the same host answers with 64 GLES 2.x configs the
moment the surfaceless platform is named explicitly. Symptom is a guest that
boots to nothing, never reaches adb, and floods the launcher log with
`vsock_connection.cpp: Failed to connect: No such device`.

**The operator must keep its own uid.** Upstream starts it via
`/etc/init.d/cuttlefish-operator` with `--chuid _cutf-operator:cvdnetwork`.
Overriding `ExecStart` in a systemd drop-in — which is how the LAN bind gets
set — silently drops that, the operator runs as root, and `/run/cuttlefish/operator`
comes out `root:root 0770`. The device's webRTC process runs as *you*, so it
cannot connect, and the UI shows no devices no matter how healthy the guest is.
The drop-in therefore sets `User=`/`Group=` explicitly.

Two smaller notes: `cvd create` boots a device that does not exist yet, while
`cvd start` only resumes one that does; and `cvd fetch` against
`aosp-main/…trunk_staging…` regularly resolves to a build whose img zip is
absent from GCS, so the pinned build is the release branch.

**Verified on Cuttlefish / Android 17** (`aosp_cf_x86_64_only_phone-userdebug`),
which moves the whole stack a major version past the Android 16 AVD the rest of
this document was established on: probes pass, profile isolation holds, the
router registers and spawns from the sandbox, and the shell connects over the
MessagePort wire with nothing bound on the device.

## What no emulator can answer

- **Frame timing.** The emulator draws on the host GPU. This matters
  specifically because I3 — surfaces do not scroll or animate — was decided on
  Android's geometry updates lagging the chrome. That decision cannot be
  validated or falsified here.
- **Vanadium.** The emulator is AOSP/Google WebView, so WebView profile and
  UA-metadata availability on GrapheneOS remains open.
- **StrongBox.** Software KeyMint only; irrelevant until seal/unseal.
- **Power and doze consumption.** Behaviour can be forced; cost cannot be
  measured.
- **MMS**, which needs a real carrier APN.
- **arm64**, which is compiled but unexercised.

A GrapheneOS Pixel is the truth oracle for that list, and the list is short
enough to be a manual checklist rather than a suite.

## Build status

Steps 1–3 complete on the emulator:

```
wash-router registered com.wash.about (surface=window)
wash-router registered com.wash.session (surface=desktop)
wash-router listening on unix:///data/user/0/com.hush.shell/files/wire.sock
wash-router app com.wash.about up instance=i-1 window=1
CDP: "title": "wash" @ http://127.0.0.1:11000/
```

The router runs inside the app sandbox, discovers and spawns apps, and a WebView
renders wash's session shell with About launchable from it — all against an
**unmodified wash**, with `hush/` as a separate module. See `BOUNDARY.md`.

What that does *not* yet include: a chrome of hush's own, any origin-locked
surface, a broker, or any mediated capability. Those are the product; this is the
substrate.
