# hush — roadmap

---

## The thesis

**A second machine, a QR scan, and one minute later my apps and logins are
there.**

That sentence is what 0.5 has to demonstrate, and it has not changed. What has
changed is the route to it.

---

## Status

| | |
| --- | --- |
| Android host, steps 1–5 | **done** — router runs in the app sandbox, spawns apps, two profiled WebViews composite with isolation holding in the engine |
| Step 6, the transport half | **done** — bundle served from APK assets, wire over a MessagePort, **nothing bound on the device** |
| Step 6, the chrome half | not started — what renders today is wash's session shell, not a chrome of hush's own |
| Platforms verified | Android 16 (AVD) and **Android 17 (Cuttlefish)**, the latter on host-GPU gfxstream |
| hush Go code | none yet, so no `pkg/` promotion yet |
| Broker, registry, capabilities | not started |

See `ANDROID.md` for what that milestone did and did not prove.

---

## The re-cut: Android leads

The original staging built 0.1–0.5 on Electron, treating it as scaffolding
beside Android. Two things changed that.

**Android turned out to be cheap to reach.** A static Go binary runs there
unmodified, every router flag the host needed already existed, and the whole host
is ~300 lines of Kotlin. The premise that Electron was the fast path did not
survive contact.

**Android is the adoption thesis.** "Installed as an ordinary app on the phone
you already have" (S3) is the product's central claim. Building it last means
testing the claim last.

Electron remains the desktop target — "one private space, every device" needs
one — but it is no longer the scaffold, and 0.5's portability proof will be
phone-to-desktop rather than desktop-to-desktop.

---

## Near-term steps

| | | Exit |
| --- | --- | --- |
| 1 | Static wash binary runs on Android | ✅ `wash list-apps` on device |
| 2 | APK execs the router under a foreground service | ✅ apps registered and spawned from the sandbox |
| 3 | WebView renders a shell | ✅ CDP reports the page; About opens |
| 4 | Land docs, promote wash's SDK to `pkg/` | ✅ hush can have Go code; boundary verified from both sides |
| 5 | Compositing spike — surfaces, profiles, origin locking | ✅ two profiled WebViews composited; isolation holds in the engine |
| — | CDP smoke spec over the **host** | APK installs, router up, WebView loads, About opens — guards the exec/symlink/apps-dir plumbing |
| 6a | Bundle from APK assets, wire over MessagePort | ✅ no listening socket on the device |
| 6b | hush's own chrome in place of wash's session shell | a grid that is hush's, not a desktop squeezed into a phone |
| 7 | Broker skeleton: reserved id, registry, one grant, capability port | one surface renders origin-locked with a mediated capability |

Steps 6 and 7 are where hush stops being wash-in-a-WebView.

**The Kotlin wire client is not in this list**, and that is deliberate.
`--listen-raw` carries the same frames the WebSocket does, so the host can pipe
bytes between a MessagePort and the unix socket without parsing them. A real
Kotlin wire client is needed only when Kotlin becomes an *app* — the SMS and
contacts providers, which is 0.7 work.

---

## Milestones

### 0.5 — portability

Vaultwarden bundled and autofilling into surfaces; Syncthing bundled and driven
over REST; CRDT state plane; one pairing QR carrying all three planes; pairing
order enforced vault → state → files; printed recovery code.

**Exit:** install hush on a second machine, scan the QR, and inside a minute the
grid, apps, settings, and vault are there. Files stream in behind.

The load-bearing risk is unchanged and is Android-specific: **Vaultwarden on
loopback is reachable by every app on the device.** Android has no per-uid
loopback isolation, which is the same reasoning C2 used to ban a localhost
capability API. Resolve before the credentials plane, not during it.

### 0.7 — a phone you could carry

Contacts (store, app, and publication into `ContactsContract`), Messages over
SMS, and the shell as a daily-driver home screen.

In: chrome, registry, N origin-locked surfaces, Contacts, Messages, Vaultwarden
and autofill.

Out: dialer/InCall, calendar, camera, location, BLE, per-origin network policy,
passkeys.

Three things to know going in:

- **SMS is bigger than it looks.** `ROLE_SMS` requires the full component quad —
  `SMS_DELIVER` receiver, `WAP_PUSH_DELIVER` receiver, respond-via-message
  service, `sendto:` activity. Plain SMS is a week; **MMS** is where SMS apps
  die, and MMS is what most group messaging actually is. RCS is closed.
- **Contacts force a sharing decision the design has not made.** A private store
  means Signal and the system dialer show bare numbers. Publishing into
  `ContactsContract` under a hush account via AccountManager is the sanctioned
  fix and is real work.
- **Contacts without sync is just a worse Android contacts app.** If the state
  plane does not ship alongside, the app has no reason to exist.

### Beyond

| | |
| --- | --- |
| 0.8 | Calendar (cheap — reuses the contacts machinery), passkeys (cheap once autofill injection exists), `getUserMedia` mediation and QR scanning, Gallery |
| 0.9 | Telephony and InCall — expensive, and only if replacing the dialer is a product goal |
| 1.0 | postmarketOS target |

**Camera is a scope error worth correcting early.** `SHELL.md` specs a camera app
with a native viewfinder. But the OEM camera already exists, already beats stock
CameraX, and already writes to `DCIM/` — which Syncthing carries and the Gallery
reads. What is actually needed is narrower: `getUserMedia` mediated per surface
for bank ID checks, and a QR scanner for pairing.

**postmarketOS does not need the compositor**, which I2 assumed. `WebKitWebView`
widgets in a GTK container give the same model as Android's `ViewGroup`, with
content still in separate processes. Direct subsurface composition becomes an
optimisation rather than a prerequisite, which is what makes a second provider
implementation affordable — and implementing twice is the only real test of the
capability abstraction.

---

## Discipline

**Do not build on the dev shortcut.** The loopback listener that renders the
shell today is reachable by every app on the device. Step 6 deletes it; code
written against it before then acquires assumptions about it.

**Keep `SurfaceEngine` honest at five operations** — create-with-profile,
navigate, set bounds, set UA/viewport, open message port — even while there is
one implementation. Anything that reaches past it into a platform API makes the
next host expensive.

**Design surface save-and-restore now.** Turn on "Don't keep activities" and
leave it on; Android will kill surfaces routinely and Electron never will.

**Name profiles explicitly in the registry**, never derived from origin strings.
Android profiles are created and destroyed deliberately, with limits.
