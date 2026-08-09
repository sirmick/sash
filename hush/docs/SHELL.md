# hush — shell architecture

hush is the shell. Parts of it already exist as the wash desktop
environment; parts have to be written. This document specifies the
whole thing on its own terms, marking which is which — where a problem
is already solved, take the solution; where it isn't, build new.

---

## Table of contents

- [What already exists](#what-already-exists)
- [What has to be built](#what-has-to-be-built)
- [Trust roles](#trust-roles)
- [Components](#components)
- [Surfaces](#surfaces)
- [Profiles and identity roots](#profiles-and-identity-roots)
- [Composition](#composition)
- [Client presentation](#client-presentation)
- [The capability model](#the-capability-model)
- [The registry](#the-registry)
- [Origin locking and trust chrome](#origin-locking-and-trust-chrome)
- [The native list](#the-native-list)
- [Platform deltas](#platform-deltas)
- [Open questions](#open-questions)

---

## What already exists

| Requirement | Existing answer |
| --- | --- |
| Transport that can't couple apps together | The router — muxes channels, enforces QoS and flow control, never parses payloads |
| Chrome that is itself a web app | `surface: desktop` |
| Long-lived headless services | `surface: background` — the natural shape for every provider |
| Per-app isolation with an embedded UI | One-file apps: Go backend with an embedded web-component bundle. The backend is the only thing with syscalls; the frontend has zero OS authority |
| Unforgeable caller identity | Router-attested `app_msg` `from`. **Load-bearing** — it lets the broker trust who is calling without the router parsing anything |
| A privileged component content can't impersonate | Reserved app ids served only from uid-0-owned or declared-trusted paths |
| Visible, unspoofable privilege indication | `wash-priv`'s red ROOT stripe — the precedent the trust chrome follows |
| Single-file distribution across distros | Multicall static binary with apk/deb/rpm packaging. The Alpine path makes postmarketOS short |

Not part of the phone catalogue: `services`, `journal`, `syslogs`,
`packages`, `disks`, `net`, `top`, `vscode-workbench`, `connect`.
Desktop system-administration apps, kept in the desktop catalogue.
Sharing a router and SDK does not mean sharing a catalogue.

---

## What has to be built

### Multi-principal trust

The existing model is **single-principal**: router and apps run as one
trusted Unix user on a localhost-trust boundary, with no authentication
beyond socket reachability. That is right for a desktop environment you
own, and it is not a deficiency — it is a different problem.

The shell is **multi-principal**. Chase, Facebook, a local contacts app,
and a generic browsing surface are not one principal. The whole design
rests on Facebook's surface being unable to reach anything Chase's
surface touches. That requires policy: a grant table, minted tokens,
and providers that refuse untokened calls.

So the **broker is new** and has to be written. The mechanism it ships
as is not: a `background` app with a reserved id, served only from a
uid-0-owned binary or a declared-trusted path — the arrangement
`wash-priv` already uses. That keeps the transport pure, which is the
principle worth preserving: policy lives in one auditable process, not
in the thing that moves bytes.

Localhost-trust also does not carry over. Web surfaces render hostile
content, so no socket the shell exposes may be reachable by a rendered
page — ruling out any HTTP or WebSocket listener a page could address.
See [The capability model](#the-capability-model).

### Also new

- **Web surfaces** — hostile-content hosting with per-origin
  partitioning, origin locking, and per-origin network policy.
- **Profile model** — identity roots and leaf profiles
  ([below](#profiles-and-identity-roots)).
- **Client presentation** — viewport and emulation control
  ([below](#client-presentation)).
- **The provider layer** — see `PROVIDERS.md`.
- **On-device composition.** `wash-display` solves the *remote* case by
  capture-and-forward, which is the wrong path here. The wlroots
  groundwork is reusable; direct subsurface composition is new work.

---

## Trust roles

Three, and the important move is that **the UI is not privileged**.

| Role | Contents | Authority |
| --- | --- | --- |
| **Untrusted** | Web content in surfaces | None. Isolated by the engine's own sandbox plus per-origin partitioning. |
| **Unprivileged** | The shell chrome (`session`), local app frontends | Draws, lays out, launches. Holds no OS capabilities; calls the broker like anything else. Most code lives here and none of it is dangerous. |
| **Privileged** | Broker, providers | Broker holds all policy and no I/O. Providers hold all I/O and no policy. |

The consequence worth designing for: because the chrome is
unprivileged and web-based, **it is replaceable**. A third party can
write an alternative shell UI against the same broker without being
trusted with anything. That is a far healthier extension model than
plugins inside a privileged process.

---

## Components

**Surface Host** — owns engine instances and their placement. Its only
authority is drawing. No capability traffic passes through it.

**Capability Broker** (reserved id, `background`) — new; no wash
counterpart, written from scratch. It is the entire policy surface.
Holds the grant table, mints scoped
short-lived tokens, routes calls to providers. Performs no I/O of its
own. Keep it small enough to read in an afternoon; it should change
rarely.

**Providers** (`background`, one process and one permission set each) —
telephony, messaging, PIM, media, location, peripheral, credentials,
storage, sync, push. See `PROVIDERS.md`.

**Registry** — the installed-app database. Owned by the broker,
read-mostly, synced.

**Trust chrome** — drawn by the Surface Host in a layer content cannot
reach. The one thing that genuinely cannot be delegated.

---

## Surfaces

A surface is an isolated web context with its own storage partition,
network policy, and grant set.

| Kind | Origin | Delivery | Typical grants |
| --- | --- | --- | --- |
| **Local app** | `app://<id>` | Signed bundle, shipped with the shell | Whatever the app needs — PIM, telephony, credentials |
| **Web app** | Declared allowlist | Remote, re-delivered per load | `notifications`, `credentials`, nothing else by default |
| **Browsing** | Any | Remote | None. Ever. |

The `app://` distinction is not cosmetic. Signed local bundles are not
re-delivered on load, which restores the pinning property that makes
E2EE clients safe — the objection to web-delivered crypto is *delivery*,
not web technology. A local app with a hardware-sealed identity key is
architecturally sound where a hosted equivalent is not.

---

## Profiles and identity roots

Two levels, not arbitrary nesting: **identity roots** and **leaf
profiles** that may reference one.

A leaf profile is the default. Independent cookie jar, storage
partition, cache, and service workers. Nothing crosses.

An identity root is a profile that exists to hold a federated login —
typically Google, Apple, or a work SSO — because many sites accept it
and re-entering credentials per surface is unacceptable UX.

### Sharing the flow, not the jar

The critical distinction, and getting it wrong hands away exactly what
the design exists to prevent:

| Sharing | Exposure |
| --- | --- |
| **Top-level federated login** | The child redirects to the IdP, returns with a token. The IdP learns you signed into that site. Inherent to using federated login at all — nothing extra is lost. |
| **Shared cookie jar** | Every *third-party* IdP resource — captcha, analytics, fonts, embedded video — links all child profiles into one browsing profile. **This is the real leak.** |

So third-party requests to an identity provider are always
unauthenticated and always partitioned, in every profile, with no
exception. Only top-level, user-initiated auth navigations run in the
root.

### The flow

```
child surface needs login
     -> shell opens an ephemeral TOP-LEVEL window in the identity root
     -> OIDC / OAuth runs there; the root's IdP cookie is used
     -> only the code or ID token returns to the child's registered
        redirect URI
     -> child stores a session cookie for ITS OWN origin
     -> the IdP cookie never lands in the child profile
```

This is essentially FedCM, and a shell that owns the browser can
*enforce* what a browser can only encourage: the flow may only ever run
top-level, in the root.

### Registry field

`identity_root: <profile-id> | none`

Two properties fall out: the relationship is visible in settings rather
than implicit in a cookie jar, and revoking a root revokes every
child's ability to re-authenticate, in one action.

**Expect breakage.** Sites doing silent re-auth through a hidden iframe
to the IdP will fail under partitioning and fall back to a visible
sign-in. That is correct behaviour and FedCM is the sanctioned path,
but it will generate bug reports.

---

## Client presentation

Two goals that get conflated. One is finishable; one is asymptotic.

### Goal A — mobile layout without mobile identity

Responsive CSS keys off **viewport width in CSS pixels**, which is not
a mobile identifier: a narrow window on a desktop hits the same
breakpoints. The target state is therefore just *a desktop browser with
a narrow window*. This is fully achievable and also fixes app
interstitials and deliberately-crippled mobile web.

| Signal | Set to |
| --- | --- |
| UA string | Stock desktop Linux. Never invented. |
| `Sec-CH-UA-Mobile` / `-Platform` | `?0` / `"Linux"` — must agree with the UA |
| Viewport | ~400–480 CSS px. This alone fires every breakpoint |
| `devicePixelRatio` | **1 or 2.** Never 2.625 or 3 — phone-only values, immediate tell |
| `maxTouchPoints`, touch events, `pointer`, `hover` | Consistent with one committed device story |

> **Engine caveat.** How much of this table is achievable is
> engine-dependent, and the gap is wide. Electron exposes CDP through
> `webContents.debugger`, giving full metrics, dPR, and touch emulation.
> Android WebView offers little beyond `setUserAgentString` and the
> androidx UA-metadata override — no dPR override, no pointer/hover
> control, no metrics emulation. GeckoView sits between them. Verify
> against the target engine before building strategy on this; treat
> unachievable fields as denied capabilities, the same as any other.

**Apply at the embedder, never by injecting JS.** Embedder-level
overrides land before page script runs and leave no patched property
descriptors. JS overrides are detectable via `toString`, descriptor
inspection, or re-reading the value from a fresh iframe.

The one real tension is `pointer`. Report `fine` and touch targets are
desktop-sized on a touchscreen. Report `coarse` and a desktop UA looks
odd. Resolve by committing to one fictional device and never deviating:

- **Linux tablet** — `pointer: coarse`, `hover: none`,
  `maxTouchPoints: 10`, dPR 2. Internally consistent, correct touch
  targets.
- **2-in-1** — `pointer: fine`, `any-pointer: coarse`. Larger
  population, worse touch ergonomics.

Either is defensible. Inconsistency is not: a Windows UA with dPR 2.625
and a coarse pointer is a population of one, and inconsistency is
precisely what anomaly scoring detects.

### Goal B — blending into the desktop herd

Harder, and the Android build is structurally worse at it than the
postmarketOS build:

| Signal | Problem |
| --- | --- |
| Fonts | An Android build ships Roboto and Noto; a Linux desktop ships DejaVu and Liberation. Enumeration and canvas metrics expose this; no UA setting touches it. The pmOS build wins here for free. |
| WebGL renderer | Adreno or Mali versus Mesa or Intel. Masking it is itself a signal. |
| Network ASN | On cellular you are in a mobile carrier range. Not fixable client-side. |

Position honestly: Goal A is a configuration problem that can be
finished. Goal B is asymptotic — worth pursuing on pmOS where the
substrate genuinely is a Linux box, and on Android accepted as reducing
legibility rather than eliminating it.


---

## Composition

The chrome is itself a web app; real surfaces are composited beneath
and around it. The chrome renders a placeholder, measures it, and emits
`{x, y, w, h, z, clip}`; the host positions the real surface to match.

**Not iframes.** A cross-origin iframe cannot have its own storage
partition under your control, its own network policy, its own JIT
setting, or its own capability scope — and the parent can overlay it.
Separate engine contexts are non-negotiable.

**Linux — direct composition.** The browser instance is a real Wayland
client; `wl_subsurface` positions it relative to the chrome's surface,
and `wl_subsurface.set_sync` gives atomic commit with the parent so the
surface never lags the chrome by a frame. No capture, no encode, no
copy. `wash-display`'s remote capture path is not used on-device.

**Android — view-hierarchy placement.** WebView is an ordinary
hardware-accelerated `View`: it composites in the normal hierarchy with
correct z-order and clipping. No `SurfaceView`, no `TextureView`. The
"compositor" is a `ViewGroup` doing absolute positioning driven by
messages from the chrome WebView — a few hundred lines.

Geometry updates on Android land a frame or more behind the chrome's
own rendering. Rather than fight this, adopt it as a UI constraint:
**surfaces do not scroll or animate.** Fixed panels, discrete
transitions. The design wants nothing else, and the entire jank class
disappears.

---

## The capability model

**No listening socket reachable by content.** Any origin can issue
requests to `127.0.0.1`; CORS restricts reading responses but simple
requests fire regardless. A privileged localhost API is the pattern
behind the Zoom, Razer, and Logitech local-server vulnerabilities.

Instead: at surface creation, the Surface Host injects a
**`MessagePort` bound to that surface** into its JS context. All
privileged calls ride that port. There is no port to guess, no origin
confusion, and a surface that was not launched as an app has no channel
at all.

Flow:

```
surface JS  --MessagePort-->  Surface Host
                                  |  app_msg (router-attested `from`)
                                  v
                              Broker  -- checks grant table
                                      -- mints scoped token (capability,
                                         params, expiry, surface id)
                                  |  app_msg + token
                                  v
                              Provider -- validates token, executes
```

**Providers never check caller identity.** They accept broker-minted
tokens and execute. A compromised provider leaks its own domain and
nothing else; a compromised chrome holds only the grants it was given.

Tokens are scoped, not ambient: not `bluetooth` but "GATT service
`0x180D` on peer `AA:BB:…`"; not `location` but "one coarse fix."

For desktop development ergonomics — building the app layer against a
stub in a plain browser — the defensible fallback is a unix domain
socket, or TCP on a per-boot random port with a per-surface bearer
token plus a mandatory custom header forcing a CORS preflight. Never a
bare localhost HTTP API.

---

## The registry

One row per installed app:

| Field | Purpose |
| --- | --- |
| `id` | Stable identifier |
| `name`, `icon` | What the launcher shows |
| `kind` | `local` \| `web` \| `browsing` |
| `origins[]` | Allowlist. Navigation outside it is ejected, not followed. |
| `capabilities[]` | Granted set, default empty |
| `network` | Per-origin egress policy |
| `viewport` | `mobile` \| `desktop` — per-origin override for sites that degrade mobile web |
| `resident` | Pin in memory (dialer, messages) |

Synced as CRDT state. This is the object "installing Chase" writes.

**The registry and the keychain must share the origin model.** If they
disagree, autofill will offer credentials on a surface the registry
considers foreign, and origin locking becomes decorative.

---

## Origin locking and trust chrome

Binding name → icon → origin once at install, and never again at
launch, is strictly better than a URL bar. The URL bar is a known-failed
UI: shown every time, read almost never. It is also better than a
native app, which routinely renders arbitrary WebViews inside a trusted
brand frame — where much real in-app phishing lives.

Two conditions make it hold:

1. **Surfaces are origin-locked.** Navigation outside the allowlist is
   ejected into a generic browsing surface. Without this, one open
   redirect on the bank's own domain and the binding means nothing.
2. **Chrome content cannot imitate.** A phishing SMS opens a browsing
   surface too. If an installed-app surface and an arbitrary-URL
   surface look identical, the attacker just uses the other door. Three
   visually unmistakable states — local app, origin-locked web app,
   arbitrary browsing — drawn in the host layer.

Combined with origin-scoped WebAuthn credentials, *credential*
phishing is close to structurally dead. Transaction phishing —
persuading a human to send money to the wrong place — survives, as it
does everywhere.

Residual risk sits at install time: typing the URL once collapses N
chances to be fooled into one. A signed catalogue of origin definitions
closes it.

**The known limitation:** origin binding proves the code came from
Chase, not that it is the same code Chase served yesterday. Web
delivery has no trust-on-first-use, so a compromised or compelled
server is undetectable in a way an APK signature would catch. This is
why E2EE messaging stays native or local-signed.

---

## The native list

| Component | Why it cannot be a lazily-loaded surface |
| --- | --- |
| **InCall service** | An incoming call must wake the device, take audio focus, and draw over the lockscreen inside ~1s. A cold-start surface misses it. Emergency calling carries regulatory requirements besides. The dialpad, history, and search are a JS app over `telephony`. |
| **Camera capture** | Owns the ISP pipeline and the viewfinder `SurfaceView`, positioned by the shell under a transparent JS control layer. JS never touches frames. |
| **Keychain** | Autofill service and credential provider registration are native-only; master secret sealed to hardware. |
| **Signal / Molly** | Code delivery, plus registration anchoring. |
| **Navigation** | Background GPS, screen-off operation, voice prompts. |

That is the whole native surface. Everything else is web.

---

## Platform deltas

| | Linux (pmOS / desktop) | Android |
| --- | --- | --- |
| Router | Native binary, unix sockets | Native binary in `nativeLibraryDir`, app uid, unix sockets inside the sandbox |
| Chrome host | Browser or embedded engine | WebView |
| Composition | Wayland subsurfaces | ViewGroup placement |
| Privilege | `wash-priv` | Android permissions; no sudo, no systemd |
| `/proc` visibility | Full | Restricted (hidepid) — kills `top`-style apps |
| Providers | ModemManager, BlueZ, GeoClue, Secret Service, PipeWire | Android framework APIs |

**Engine choice.** Chromium on Android is settled (system WebView =
Vanadium: hardened, JIT-toggleable, OS-patched). On Linux the options
are WebKitGTK (in Alpine, musl-clean, fastest path — but a *different
engine*, splitting fingerprint surface and doubling test burden), CEF
(Chromium everywhere, but a musl build project), or Electron (trivial
on glibc, painful on musl).

Recommended: prototype on Electron/glibc where `WebContentsView` gives
the embedding model for free, and treat the pmOS engine question as a
separate later decision. Nothing in the app layer or capability API
depends on the answer.

---

## Open questions

- ~~Is `--fs-root` confinement per-instance or per-router?~~ **Resolved
  2026-08-09: per-router, and advisory rather than enforcing** — see
  `DECISIONS.md` "Still open". Per-surface isolation comes from the
  engine profile (web) and the `storage` capability (local apps); the
  router's sandbox root was never a boundary.
- Whether the broker's grant table lives in the registry or separately —
  it is synced state, but it is also security-critical state, and those
  may want different durability guarantees.
- Push wake-path on Android: the host holds one Web Push connection and
  wakes guest service workers through doze. Needs measurement, not
  design.
- Whether the desktop-viewport override belongs per-origin (registry)
  or per-surface-instance (user gesture).
