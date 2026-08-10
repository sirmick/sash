# hush — 0.3, the shell and five real apps

The milestone where hush stops being wash-in-a-WebView.

**Tap an icon on hush's own home screen and get an origin-locked surface with
its own jar. Come back, tap another, and the two cannot see each other.** Google
signs in once at the identity root and Gmail and Maps inherit the *flow* without
sharing the jar. Two or three banks log in for real. Nothing is bound on the
device.

---

## Table of contents

- [Why this comes before the broker](#why-this-comes-before-the-broker)
- [Phases](#phases)
- [The registry](#the-registry)
- [Where hush's Go code lives](#where-hushs-go-code-lives)
- [The chrome](#the-chrome)
- [The app set](#the-app-set)
- [Decisions](#decisions)
- [What will go wrong](#what-will-go-wrong)
- [Out of scope](#out-of-scope)

---

## Why this comes before the broker

A tier-0 web app is a name, an icon, an origin allowlist and a profile. It asks
for **no capabilities at all** — not contacts, not telephony, not location. The
broker exists to mediate capability requests, so an app set that requests none
does not need one.

That is not a shortcut, it is the correct order. The broker is the entire policy
surface and `SHELL.md` asks it to stay small enough to read in an afternoon.
Writing it before there is a single real caller means designing its API against
imagined callers. Five real surfaces, two of them banks, is a far better brief.

What this milestone *does* need is the substrate the broker will sit on: a
registry, a surface lifecycle that survives Android, and a chrome that launches
things. All three are prerequisites regardless.

---

## Phases

| | | Exit |
| --- | --- | --- |
| **P0** | ✅ Origin-lock correctness; probe what we're about to design against | Build green; the probe answers the WebAuthn question — `FOR_BROWSER` accepted |
| **P1** | ✅ `hush-registry` — hush's first Go code | On device: `com.hush.registry` registered, 7 apps seeded into the sandbox |
| **P2** | Surface lifecycle in the host | A surface opens, takes IME, spawns a popup, downloads a file, is killed by Android, and comes back |
| **P3** | The chrome: grid, pages, dock, drawer, trust states | Tap an icon → surface fills the screen; back returns home |
| **P4** | Settings and About | Grid size configurable; apps installable, editable, removable |
| **P5** | The app set: `id-google` + Gmail + Maps, five financial leaves | Sign into Gmail; open Maps already signed in; two banks log in; cookie isolation asserted between two live surfaces |
| **P6** | CDP smoke spec over the host | The long-unticked roadmap item, now with something worth smoking |

### P0 — two bugs and a question

Both bugs are in `AndroidSurfaceEngine`, both found by reading rather than by
running, and both would present as something other than their cause.

**`shouldOverrideUrlLoading` does not check `request.isForMainFrame`.** A
*subframe* navigating outside the allowlist is currently ejected. Google's OAuth
runs in iframes, as do embedded captchas — so this breaks tier 0's login on day
one, and it would look like Google being hostile rather than like our bug. The
allowlist is a rule about navigation, not about subresources; assets from
`gstatic` and friends were never in scope.

**`allowed()` matches host suffixes with no scheme check**, so `http://` on an
allowlisted host passes origin lock. Origins have schemes; ours should too.

The question is **WebAuthn**. `Probe` reports what this WebView can do, and
whether passkeys exist decides how much of the tier-2 login story is available.
Better to know before P5 designs around it than to discover it during.

### P1 — the registry

hush's first Go code. See [Where hush's Go code lives](#where-hushs-go-code-lives)
and [The registry](#the-registry).

The chrome must not read a file. Registry state is policy, and the chrome is the
unprivileged layer — putting registry writes in Kotlin or in the chrome's own
storage would place policy on the wrong side of the trust boundary and then have
to move again when the broker absorbs it.

### P2 — surface lifecycle

Composition and touch routing are done. What is missing is everything Android
hands back as a callback, where the default is either "nothing happens" or "the
app dies":

| | Without it |
| --- | --- |
| `onCreateWindow` | `target=_blank` is a dead link. Banks open statements and secure messages this way. |
| `DownloadListener` | Statements do not download. |
| `onShowFileChooser` | Check deposit and document upload are dead. |
| `onRenderProcessGone` | Android reclaiming a surface **crashes the whole app**. |
| `onPermissionRequest`, geolocation | Must default to *deny*, not prompt. Prompting is the broker's job later. |
| `onReceivedHttpAuthRequest`, `onReceivedClientCertRequest` | Rare; fatal when hit. |
| IME insets → chrome → new geometry | The focused field sits behind the keyboard. |
| Back policy | Back reaches the Activity and does nothing sensible. |
| Edge-gesture interception | A full-screen surface eats swipe-up-to-home. The free hit-testing works against us in exactly this one place. |

Also here: save-and-restore. "Don't keep activities" goes on and stays on.

### P3 — the chrome

Replaces wash's session shell as what the WebView loads. See
[The chrome](#the-chrome).

### P4 — Settings and About

**Both live in the chrome**, not as `app://` surfaces. They are launcher
settings. Making them local app surfaces would drag local-app packaging and
signing forward a whole milestone for no test value — the surface path is better
exercised by Gmail and Chase, which is content we do not control and therefore
content that can surprise us. `SHELL.md` promotes them to real surfaces when
local-app packaging exists.

About shows version, host facts, and the probe results, which are already
collected. Settings covers grid geometry, the app list, per-app origins and
presentation, and the profile list.

### P5 — the app set

See [The app set](#the-app-set). Sequence one bank **early**, not last: bot
detection is the risk that could invalidate the approach rather than merely
delay it, and it should be discovered in week one.

### P6 — the smoke spec

Drives the host over CDP: APK installs, router up, chrome loads, an icon opens a
surface, the surface reaches its origin, and a cookie set in one surface is
invisible in another. Guards the exec/symlink/apps-dir plumbing that every
future milestone sits on.

---

## The registry

One document, `schema: 1`, three top-level maps. Everything keyed by stable id;
no positional arrays anywhere, because position is what does not merge.

```json
{
  "schema": 1,
  "profiles": {
    "id-google": { "kind": "identity-root", "origins": ["accounts.google.com"] },
    "p-gmail":   { "kind": "leaf" }
  },
  "apps": {
    "com.google.mail": {
      "name": "Gmail",
      "icon_ref": "sha256-…",
      "kind": "web",
      "origins": ["mail.google.com"],
      "profile": "p-gmail",
      "identity_root": "id-google",
      "presentation": "linux-tablet",
      "viewport": "desktop",
      "installed": true
    }
  },
  "layout": {
    "phone": {
      "com.google.mail": { "page": 0, "col": 1, "row": 0, "cw": 1, "ch": 1 }
    }
  }
}
```

Three rules from `STATE.md` apply now even though nothing syncs yet, and the
second is the one that cannot be added retroactively:

1. Every document carries a schema version.
2. **Unknown fields are round-tripped, never dropped.**
3. Keyed by id, per-field, no wall-clock ordering.

Two shapes are deliberate. `layout` is keyed by **device class** first, because a
phone grid means nothing on a 27" monitor and `STATE.md` scopes layout to
`device-class`. And layout entries carry **`cw`/`ch` spans** even though nothing
spans yet: a widget in hush is just an app pinned at a larger size — the same
registry row, the same origin lock, the same port — and adding spans after
layouts have started syncing is miserable.

`capabilities[]` is absent, and stays absent. Grants never sync
(`STATE.md`), and this milestone grants nothing.

---

## Where hush's Go code lives

The router discovers apps by scanning `--apps-dir` for executables named
`wash-*` and exec-probing each with `--wash-manifest`. The prefix is a safety
filter, not a trademark: it means "an app for the wash router", the way `git-*`
means a git subcommand. **hush's app binaries are therefore named `wash-*` too,
and this needs no change to wash.**

App *ids* come from the manifest, not the filename, so the binary is
`wash-hushreg` while the app is `com.hush.registry`.

```
hush/cmd/hush/            hush's own multicall — dispatches on basename(argv[0])
hush/apps/registry/       com.hush.registry, surface=background
  →  jniLibs/<abi>/libhush.so
  →  filesDir/apps/wash-hushreg  (symlink; same mechanism as libwash.so)
```

Multicall from the start even with one applet, so the broker and the providers
are a new symlink rather than a new `.so`. The `.so` name and the symlink dance
are forced by Android and already understood — see `ANDROID.md`.

hush's binary is separate from wash's `libwash.so` and always will be: they are
different modules, and the compiler-enforced boundary is the point.

---

## The chrome

Lawnchair-shaped: a grid of pages with a dock and a drawer. Configurable
rows×cols. No widgets, because we do not need a widget subsystem — a pinned
surface at a larger span is the same object.

**Three trust states must be legible on the grid itself** — local app,
origin-locked web app, arbitrary browsing. No Android launcher has this, because
no Android launcher hosts hostile origins next to your bank. It is hush-specific
and has to be designed into the icon treatment rather than bolted on, and it is
what makes `SHELL.md`'s origin-locking argument hold: if an installed-app surface
and an arbitrary-URL surface look identical, the attacker just uses the other
door.

**App open is a cut, not a zoom.** I3 says surfaces do not scroll or animate, so
Pixel's icon-expands-into-the-app transition is unavailable. The chrome itself
may animate freely — it is one full-screen WebView — but the moment a real
surface is on screen its geometry is discrete.

Search is deferred to 0.5, when contacts and files exist to search. A search box
over twenty app names is not worth the frame.

---

## The app set

```
id-google  (identity root)        accounts.google.com — holds the IdP cookie
  ├── com.google.mail             identity_root: id-google
  └── com.google.maps             identity_root: id-google

com.chase, com.schwab, com.fidelity, com.morganstanley, com.firsttechfed
                                  identity_root: none — each its own leaf
```

A financial surface is never a child of an identity root. That is not a knob.

**Allowlists are discovered, not authored.** Real login flows hop through hosts
you only learn by running them, and a wrong list fails as an ejection mid-login —
the worst possible failure, because it looks like the site is broken. So install
is **install-by-navigation**: you enter the URL, the surface records
out-of-allowlist *main-frame* navigations through one login run, and the shell
proposes the origin set. This also collapses `SHELL.md`'s residual install-time
risk into a single act — typing the URL once.

The identity-root flow is the one from `SHELL.md` and is not profile
inheritance, because no engine offers that:

```
child surface needs login
  -> ephemeral TOP-LEVEL window in the identity root
  -> OAuth runs there against the root's IdP cookie
  -> only the token returns to the child's redirect URI
  -> the IdP cookie never lands in the child profile
```

---

## Surface or handoff

The registry's `launch` field decides, per app, whether opening it composites a
surface or opens the user's real browser in a Custom Tab. It is a setting rather
than a tier, because the right answer differs by site.

**Compositing buys unlinkability, not security.** Same-origin policy already
keeps other sites out of a bank session. What a private profile prevents is one
cookie jar and one fingerprint carrying your identity, your ad profile and your
accounts together.

Run the app set through that test and it splits cleanly, in the opposite
direction from where this document started:

| | |
| --- | --- |
| **Surface** — Gmail, Maps | You are logged in, they follow you across the web, and they tolerate unusual clients because their business depends on reach. There is an identity here worth keeping in a jar of its own. |
| **Handoff** — the five financials | They already know exactly who you are, so a private jar withholds no identity — while they are the most hostile sites to a non-standard client and the most expensive to break. |

Handing off does not *mitigate* the bot-detection risk, it deletes it.
`presentation: android-chrome` exists because looking like stock mobile Chrome
is the safest thing a surface can do behind Akamai-class fingerprinting, and
actually being it beats impersonating it — permanently, with no maintenance.
PDF statements, downloads, print and real passkey integration arrive with it,
which removes two of the walls listed under [What will go wrong](#what-will-go-wrong).

**The cost, stated exactly:** no browser on Android has profiles. Everything
handed off shares one jar with everything else handed off and with ordinary
browsing. That is the status quo rather than a regression, but it is precisely
what a surface buys and a handoff cannot.

Custom Tabs rather than `ACTION_VIEW`, and the difference is not cosmetic: a
Custom Tab renders inside hush's task, so back returns to the grid. Observed on
Cuttlefish, which has no Custom Tabs provider and therefore takes the fallback:
the browser opened in a *different task*, which is the "dumped out of the app"
feel the choice exists to avoid. Whether a real Custom Tab feels like part of
hush is still untested — the AOSP image has no browser that provides them.

Gmail stays a surface for a second reason: without at least one composited app
in the set, P5 never exercises the surface path against content we do not
control.

## Surfaces are not tasks

Worth stating plainly, because everything about switching follows from it: a
surface is a `View` inside hush's single Activity, in one task. Android's
recents lists **tasks**, so hush appears there exactly once no matter how many
surfaces are open. Verified rather than assumed — with Gmail full-screen,
`dumpsys activity recents` shows one `com.hush.shell` entry and no Gmail.

Everything Android attributes per app therefore lands on hush: permissions,
battery, network accounting, notifications, force-stop. That is not a new
constraint, it is the reason the broker has to exist — there is no OS-level
isolation between hush's apps to lean on.

**But web content is already out-of-process and out-of-uid**, which is the part
that actually matters and which the "one uid" phrasing understates:

```
com.hush.shell                      u0_a122    host: chrome + compositing
wash-hushreg                        u0_a122    hush's registry
...SandboxedProcessService0:2       u0_i9118   renderer, ISOLATED uid
...SandboxedProcessService0:3       u0_i9119   renderer, ISOLATED uid
```

Those `u0_i…` uids are Android *isolated* uids — no permissions, no filesystem,
SELinux-confined, one per renderer. Chromium already provides the strong
boundary exactly where hostile content lives. **The security boundary is the
profile and the renderer sandbox, not the process and not the task.**

That settles the recurring temptation to give each surface its own task via
`FLAG_ACTIVITY_NEW_DOCUMENT`, or its own process via `android:process`. Neither
buys a security boundary — separate host processes would still share hush's uid.
Tasks would cost re-parenting live WebViews between windows (with the
`MutableContextWrapper` hazards that brings for IME, file choosers and permission
dialogs), a trust overlay per window, and recents cards that are screenshots of
your bank. Processes would additionally cost real IPC, a
`WebView.setDataDirectorySuffix()` per process, and a second WebView
initialisation apiece — buying crash isolation and nothing else.

## The switcher, and Android's ceiling

hush cannot merge into the system switcher, and this is a hard limit rather than
a missing feature:

- The overview UI is drawn by whichever component is registered as
  `config_recentsComponentName` — the system launcher on a stock device. A
  third-party launcher cannot provide it, which is why they lose the
  swipe-up-to-overview animation.
- `getRecentTasks()` has returned only the caller's own tasks since API 21, so
  hush cannot even read what the system switcher shows.

So the switcher is hush's own, and covers both halves: **live surfaces**, which
it can describe better than recents ever could — resident versus cold, and their
trust class — and **the device's apps**, listed through a `<queries>`
declaration for MAIN/LAUNCHER (the sanctioned launcher route;
`QUERY_ALL_PACKAGES` asks for far more) and launched by intent into their own
task. hush hosts none of those and claims nothing about them, which is the same
honesty as a handoff.

Recency ordering for the device half needs `PACKAGE_USAGE_STATS`, a special
access granted on its own Settings screen. Deferred: it is how third-party
launchers do "recent apps", and it is a grant to ask for deliberately rather
than in passing.

## Decisions

| | |
| --- | --- |
| Settings and About are chrome, not surfaces | Avoids dragging local-app packaging forward a milestone |
| The registry is a Go app now | Registry state is policy; the chrome is unprivileged |
| Grid launcher with a drawer; no widgets | A pinned surface *is* a widget |
| Spans in the layout schema from day one | Cheap now, miserable after layouts sync |
| No `CATEGORY_HOME` yet | Being the home screen raises cold-draw and wallpaper obligations; that is 0.7 |
| `presentation` per app | An explicit, recorded concession — see below |

**`presentation` deserves its own paragraph** because it is a real retreat.
`SHELL.md` commits to one fictional device, the Linux tablet, and never
deviating. Financial sites sit behind Akamai-class fingerprinting where a
Linux-desktop UA on a touchscreen is a population of roughly one — which is
precisely what anomaly scoring detects. For those surfaces, presenting as stock
mobile Chrome on Android is *safer*, because the herd is enormous. So
`presentation` is `linux-tablet` by default and `android-chrome` for financials.
Recorded here as a stated trade rather than discovered later as a support burden.

---

## What will go wrong

**Bot detection** could invalidate P5 rather than delay it. Mitigated by
`presentation`, and by sequencing a bank early as a canary.

**PDF statements.** WebView renders no PDF inline. Every institution in the list
serves statements as PDFs, so it is download-plus-viewer or pdf.js in a local
surface. Out of scope here, named so it is not a surprise.

**SMS 2FA** has no provider until 0.7. You will read codes off the same phone.

**Passkeys** depend on the P0 probe answer.

**Google's OAuth iframes** are the first thing the `isForMainFrame` fix protects,
which is why it is in P0 and not discovered in P5.

---

## Out of scope

Broker, capability tokens, providers, sync, Vaultwarden, autofill, folders,
widgets, search, `app://` packaging and signing, `CATEGORY_HOME`.

Tier 0 and tier 2 both request zero capabilities, which is exactly what lets this
milestone precede the broker.
