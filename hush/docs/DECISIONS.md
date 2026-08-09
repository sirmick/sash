# hush — decisions

Why the design is what it is. Each entry records the decision, the
alternatives considered, and **what would reverse it** — the last part
being the point. A decision without a stated reversal condition is a
belief, not a decision.

---

## Table of contents

- [Strategy](#strategy)
- [Application model](#application-model)
- [Security architecture](#security-architecture)
- [Data](#data)
- [Implementation](#implementation)
- [Rejected outright](#rejected-outright)
- [Still open](#still-open)

---

## Strategy

### S1 — Don't fight attestation

**Decision.** Where a relying party demands a device integrity signal,
they win. hush does not spoof, bypass, or lobby for inclusion.

**Why.** Attestation on mobile is viable only because two vendors
control the measurement space. Getting added to an allowlist produces
the set {Google, hush} — still an allowlist, still fatal to the next
project and to your own next hardware generation. The winning position
is being indistinguishable from an unbounded set, not being entry
number two.

**Rejected.** Play Integrity accommodation (GrapheneOS has pursued this
for years without success). Extracted certificates (revocable,
legally exposed). Arm CCA compliance compartments — technically the
most coherent path, since Realm attestation is independent of the host
hypervisor, but phone-class RME silicon isn't on any public roadmap.

**Reverses if.** Regulatory action forces relying parties to accept
third-party OS attestation, or CCA reaches phone silicon *and* relying
parties accept Realm tokens. The second requires the first anyway.

### S2 — The desktop web's ungovernability is the strategic asset

**Decision.** Route everything possible through the interface nobody
can gate.

**Why.** Remote attestation needs a verifier able to enumerate valid
states. Windows × macOS × N distros × M kernels defeats that, so nobody
tried, so risk engines got built instead — and those now carry the bulk
of the world's money. The same bank that refuses GrapheneOS moves six
figures from an unattested Linux laptop.

**Reverses if.** Browser attestation lands as a deployed standard.
Watch age-verification legislation — it gives every site a
compliance-shaped reason to demand a device signal, and it is the most
likely vector.

### S3 — Layer, don't replace

**Decision.** hush is an ordinary app on an OS the user already has.
Every native app they haven't moved stays in the drawer.

**Why.** Privacy tools fail on adoption, not merit. GrapheneOS demands
a discontinuous switch before any benefit is experienced. A continuous
path — install, move one theme, keep the rest — converts people the
cliff turns away.

**Consequence.** hush must be good on a stock phone with Play Services
running beside it, and must never lecture. The claim ladder in
`PRODUCT.md` exists to keep this honest.

### S4 — Android Auto is a hardware problem

**Decision.** Out of scope. Solve with a separate dashboard device.

**Why.** Not a policy gate like banking apps — a closed protocol with
mutual TLS between two physical devices. Every open implementation
(aasdk, OpenAuto, OpenAutoLink) is *head-unit* side, because that's
where the certificate leaked. No open phone-side sender exists.

**Reverses if.** Never, realistically. It's the one category where the
counterparty holds a credential you cannot obtain.

---

## Application model

### A1 — Origin-locked surfaces, not iframes

**Decision.** Each app surface is a separate engine context with its
own profile.

**Why.** High-value sites send `X-Frame-Options: DENY` — they simply do
not render in a frame, and stripping the header overrides a security
assertion the site is deliberately making. Beyond that, a frame can't
have its own cookie jar, network policy, UA, or capability scope, and
the parent can overlay it.

**Reverses if.** Nothing. This one is structural.

### A2 — Local apps are signed bundles at `app://`

**Decision.** Contacts, messages, settings, gallery ship as signed
bundles, not hosted pages.

**Why.** The objection to web-delivered crypto is *delivery*, not web
technology. Hosted JS is re-fetched every load, so a compromised or
compelled server can ship a targeted key-exfiltrating build,
undetectably. Signed bundles restore the pinning property that makes
E2EE clients safe — which is why Signal ships Electron and has never
shipped a web client.

**Consequence.** E2EE messaging as a local JS app is architecturally
sound, given a hardware-backed sealing capability.

### A3 — The native list is decided by physical presence

**Decision.** Native access is for things requiring the device to be
physically present, plus background sensor operation. Everything else
is web.

**Why.** The web replaced device-to-server completely and has no story
for device-to-device. Every irreducible phone advantage over a laptop
is a proximity advantage.

**The list.** InCall service (sub-second wake-and-draw, plus emergency
calling regulation), camera capture (ISP pipeline), keychain (autofill
provider registration), Signal/Molly (A2 plus registration anchoring),
navigation (background GPS, screen-off, voice).

**Note.** Most of these are apps you'd choose on privacy grounds
anyway. Organic Maps beats Google Maps on telemetry; Gadgetbridge beats
every vendor wearable app. Native here is a win, not a concession.

### A4 — Cross-surface open lands on the entry path

**Decision.** A link crossing into another app's origin opens that
app at its entry point, not at the link's path, unless the user
confirms.

**Why.** Otherwise a phishing email deep-links
`bank.example/transfer?to=…` into your authenticated session and the
link author has driven your logged-in surface. Firefox Multi-Account
Containers proved the confirm-with-remember UX.

**Consequence.** The dispatcher lives in the broker. A surface asking
to open a URL is issuing a *request*.

---

## Security architecture

### C1 — Broker holds policy, providers hold mechanism

**Decision.** Reference-monitor split. Providers never inspect caller
identity; they validate a broker-minted token and execute.

**Why.** Policy scattered across a dozen processes gives inconsistent
enforcement and no auditable centre. A compromised provider then leaks
its own domain and nothing else.

**Consequence.** Grants are coarse and durable; tokens are fine and
ephemeral. Not "Bluetooth" but "GATT service `0x180D` on peer `AA:BB:…`,
expiring in 60s."

### C2 — No localhost API

**Decision.** Capability access is via a `MessagePort` injected at
surface creation. No HTTP or WebSocket listener a page can address.

**Why.** Any origin can issue requests to `127.0.0.1`; CORS restricts
reading responses but simple requests fire regardless. This is the
pattern behind the Zoom, Razer, and Logitech local-server
vulnerabilities. On Android, any installed app can reach it too.

**Consequence.** The MessagePort terminates at the Surface Host, which
relays ordinary `app_msg` onward — the router never sees a MessagePort
and needs no new transport. (An earlier draft of this entry claimed a
browser↔router MessagePort transport was required; `SHELL.md`'s flow is
the correct one.) The wire itself is already transport-agnostic:
`--listen-raw unix:<path>` serves the same length-prefixed frames the
WebSocket carries, so the host can be a byte pipe between the port and
that socket without parsing anything.

**Reverses if.** Never for production. A unix socket, or TCP on a
per-boot random port with a per-surface bearer token and a mandatory
custom header forcing preflight, is acceptable for desktop development
only.

### C3 — Identity roots share the flow, not the jar

**Decision.** Federated login runs top-level in an identity-root
profile; only the code or ID token returns to the child. Third-party
IdP resources stay unauthenticated and partitioned everywhere.

**Why.** Top-level federated login tells the IdP you signed into that
site — inherent to using it at all. A shared cookie jar lets
*third-party* IdP resources (captcha, analytics, fonts, embedded video)
link every child profile into one browsing profile. That second thing
is the actual leak, and naive "inherit the cookies" hands it over.

**Consequence.** Sites doing silent re-auth via hidden iframes will
break and fall back to visible sign-in. Correct behaviour; expect bug
reports.

### C4 — Virtual authenticator, not platform WebAuthn

**Decision.** hush implements the authenticator itself — intercept
`navigator.credentials`, route to the broker, perform CTAP2 operations
with keys sealed to StrongBox / TPM / Secure Enclave.

**Why.** Both platform routes require domain association. Electron's
native polyfills need Team ID and Bundle ID in the RP's hosted
association file; Android WebView's `setWebAuthenticationSupport()`
requires digital asset linking to a site *your app owns*. Both APIs
assume "my app embeds my company's site," not "my app is a browser."
No bank will asset-link you.

**Why it's better anyway.** The hardest part of being an authenticator
is establishing the true RP ID. A browser derives it from the page;
hush has it declared in the registry and enforced by origin locking.
And it's identical across Electron, WebView, and GeckoView — the one
credential component that survives every engine change.

**Cost.** Security-critical crypto you own, plus injection robust
across iframes, SPA re-renders, and shadow DOM.

### C5 — The UI is unprivileged

**Decision.** The chrome holds no OS capabilities and calls the broker
like anything else.

**Why.** Most code lives in the UI; none of it should be dangerous. The
side effect is that the chrome becomes **replaceable** — a third party
can write an alternative shell UI without being trusted with anything.
Healthier than plugins inside a privileged process.

---

## Data

### D1 — Sync scope is small and deliberate

**Decision.** CRDTs for contacts, calendar, settings, bookmarks,
registry, grant table. Content-addressed append-only blobs for photos
and history.

**Why.** Multi-writer from day one, because phone and desktop are live
simultaneously. Retrofitting concurrency onto last-writer-wins is
expensive. Append-only data never conflicts, so it doesn't need the
machinery.

### D2 — Message content is out of scope

**Decision.** hush never syncs messenger content or identity keys.

**Why.** Each messenger's linked-device protocol deliberately gives
every device a distinct identity key. Copying it breaks the guarantee
that makes those apps worth using.

**Consequence.** Adding a device means pairing each messenger
separately. That seam is load-bearing, not a gap.

### D3 — Self-hosted, and not on a hyperscaler

**Decision.** Home-hosted behind WireGuard or Tailscale is the
recommended posture.

**Why.** Encryption protects content, not the account. A sync endpoint
contacted from every network you join is a location trail — source IPs
plus timestamps reconstruct your movements. That is precisely the
surveillance the product exists to avoid, rebuilt with your own hands.
An identity-bound account at a US provider adds subpoena exposure on
top.

**Note.** The clean-device border pattern works for a real legal
reason: the border search exception attaches to the device, and CBP
Directive 3340-049B (January 2026) states officers may not intentionally
reach cloud-only data.

### D4 — Bundle Vaultwarden rather than build a vault

**Decision.** Vaultwarden ships with hush. It owns the vault format,
its cryptography, and its sync. hush's `credentials` provider is a
client.

**Why.** It deletes the three hardest items on the list — vault crypto,
vault sync, conflict resolution over secrets. Single binary over
SQLite, AGPL like wash, and the localhost-default-then-promote model is
already how people run it: promotion is copying a data directory and
changing a URL. Bitwarden clients exist everywhere, so users have
working tooling before hush's own UI does.

**What hush still owns.** The injection layer, because extensions were
dropped (I5) and Bitwarden's browser extension isn't available. That is
the non-cryptographic part.

**Reverses if.** Never for the vault itself. The injection layer is
permanently hush's.

**Known unknown.** Vaultwarden is Rust; running it cross-compiled to
`aarch64-linux-android` inside an app sandbox is untested. Spike before
planning Android around it.

### D5 — Passkeys are synced, not hardware-bound

**Decision.** Private keys live in the vault, encrypted under the vault
key. Hardware protects the vault key, not each credential.

**Why.** Hardware-sealed passkeys don't travel, which breaks "install
hush, sign in, away we go" for exactly the credentials most worth
carrying. Apple, Google, and 1Password all chose synced for the same
reason.

**Cost.** A passkey is as strong as the vault passphrase plus endpoint
security, rather than being device-bound.

**Reverses if.** A specific high-value credential needs device binding
— then it's a per-credential exception, not a policy change.

### D6 — Files are files; bundle Syncthing

**Decision.** No content-addressed store, no namespace layer, no
replication classes, no cache eviction, no rclone. Directories synced
by Syncthing.

**Why.** The CAS design was infrastructure for a problem Syncthing had
already solved. It's Go, so same toolchain and bundling story. And it
needs **no server at all** — peer-to-peer — which drops the backend
story to "one small optional server, and nothing else."

The knock-on simplification is larger than the direct one: local apps
lose their backends. Notes is markdown files in a directory; Gallery is
a directory view; the camera writes to a folder. `fm` and `edit`
already work on real files.

**Rejected.** Content-addressed store plus CRDT namespace (git's model)
over rclone. Correct, more capable — free dedup, thumbnails-here-
originals-there — and unjustified given that phones hold the archive
fine.

**Costs, accepted.** No dedup. No partial sync within a folder, so
selective sync is folder structure rather than a feature. Notes editing
conflicts become visible `.sync-conflict-*` files rather than merging
per field.

**Configuration is not optional.** Global discovery and relays are on
by default and leak device IDs and addresses to third-party
infrastructure. Off, with static addresses over WireGuard or Tailscale.

### D7 — One-minute onboarding is the sync requirement

**Decision.** "Onboard a new device in one minute and not be
frustrated" is the specification, not an aspiration. Everything in
`STATE.md` is derived backwards from it.

**Consequences that follow directly.** Users never see Syncthing —
hush drives it over REST, and there is exactly one pairing artifact
carrying all three planes. Pairing order is vault, then state, then
files, because the user's next action after pairing is opening their
bank. "Usable" and "complete" are separated: state lands in seconds,
files stream for hours, and nothing in the UI blocks on file sync.

**The asymmetry to state honestly.** The bar applies to devices 2..N.
Device one must hash the whole archive to index it — hours, and a hot
phone.

---

## Implementation

### I1 — Composition by view hierarchy, never pixel forwarding

**Decision.** Android composites sibling WebViews in the normal view
hierarchy. Linux uses Wayland subsurfaces with `set_sync`. Neither
captures and re-encodes.

**Why pixel forwarding fails.** Soft-keyboard focus lands in the shell
page, so IME composition, autocorrect, swipe typing, and CJK all break
— disqualifying for a product whose flagship uses are banking and
messaging. Platform autofill and WebAuthn target the focused window and
have nothing to bind to. Protected media refuses to render into
capturable surfaces, so DRM shows black. And there's no zero-copy path
into a browser tab, so every frame costs readback plus encode.

**Where it survives.** As a `stream` presenter for development and
remote access, where you're at a laptop with a hardware keyboard and
none of the above matters.

### I2 — The compositor is a pmOS requirement, not architectural

**Decision.** Android ships with no compositor at all.

**Why.** Android's view system already does z-order, clipping, input
routing, IME, and autofill correctly. Writing wlroots seat management
and `text-input-v3` for Android would be inventing a problem.

**Consequence.** Substantially reordered work. The compositor is
deferred until postmarketOS.

### I3 — Surfaces don't scroll or animate

**Decision.** Fixed panels, discrete transitions.

**Why.** Geometry updates on Android land a frame or more behind the
chrome's own rendering. Adopting the constraint eliminates the entire
jank class, and the design wanted nothing else anyway.

### I4 — Multi-engine, one at a time, specified to the intersection

**Decision.** Support several engines; run one, chosen at install.
Specify the `SurfaceEngine` interface to what they all have —
create-with-profile, navigate, set bounds, set UA/viewport, open
message port — and feature-probe the rest.

**Why now possible.** Every embedding API has the profile primitive:
`ProfileStore` (Android WebView), `contextId` (GeckoView),
`WKWebsiteDataStore` identifiers, `CoreWebView2ControllerOptions`,
CEF request contexts.

**Consequence to document.** Profiles are not portable across engines.
Switching means signing in everywhere again — an install-time decision
presented as such, not a settings toggle.

### I5 — Extensions dropped, which changes the engine calculus

**Decision.** uBlock Origin and Violentmonkey are not requirements.

**Why it matters.** Extensions were the *only* thing forcing Gecko —
MV3 killed full uBO on Chromium, and Vanadium has no extension support
at all. Dropping them puts Android System WebView back in play, and
with it Vanadium's hardening and JIT toggle on GrapheneOS.

**Reverses if.** Content blocking becomes a product requirement. Then
GeckoView returns, and with it the loss of Vanadium hardening — a
stated trade, not a drift.

### I6 — Electron is scaffolding beside Android, not a path toward it

**Decision.** Build 0.5 on Electron. It is not a migration route.

**Why.** Nothing in 0.5 is Android-specific — chrome, surfaces,
profiles, origin locking, broker, registry, sync, mock providers. The
Android surface host is a few hundred lines; the expensive part is
providers, and that's true whenever you start.

**Guard.** Preload establishes the MessagePort and does nothing else —
there is no Node on Android. Name profiles explicitly in the registry
rather than deriving them from origin strings. Design surface
save-and-restore early: Electron never gets killed, Android routinely
will.

**Trigger, not a date.** Once the capability API is stable and two real
providers exist, spike `com.hush.surface` on Android before adding
another feature.

---

## Rejected outright

| Idea | Why it died |
| --- | --- |
| **Per-app VMs** | Power and memory. Two kernels is survivable; eight is not. No KSM either — page dedup across trust domains is a side channel, so you eat the duplication. |
| **Android in a VM as an attestation escape** | Guest KeyMint is software-backed. The banking app that refuses GrapheneOS refuses a guest *more* decisively. VMs buy capability, never compliance. |
| **VM for BLE peripherals** | Bluetooth virtualises at HCI level, giving the guest every paired device, scan result, and address — coarser than a per-peer scoped native bridge. Strictly worse than no VM. |
| **Stripping GrapheneOS "down to basics"** | Inverts the value. What you'd strip is the framework, which is where the privacy features live; what you can't strip is the vendor HAL swamp and power management. |
| **Chrome as a plain browser tab hosting surfaces** | `X-Frame-Options` ends it before isolation is discussed. See A1. |
| **Forking Firefox chrome** | Justified only by per-surface fixed memory overhead, which is a desktop artifact. Android can't duplicate parent processes anyway, and desktop has RAM. Sustaining a fork against a four-week release train is the dominant cost for everyone who does it. |
| **CEF offscreen rendering** | `OnPaint` hands back CPU pixel buffers — loses the GPU path, mangles video, non-starter on a phone. |
| **Localhost HTTP capability API** | See C2. |

---

## Still open

- ~~Whether `--fs-root` confinement is per-instance or per-router.~~
  **Resolved 2026-08-09: per-router, and advisory rather than enforcing.**
  `Router.handshakeSession()` ships one `wire.Session{Root: cfg.FSRoot}`
  to every app in the IdentityAck, and apps opt in by constructing
  `wfs.New(c.Session().Root)`. Nothing prevents a direct `os.Open`.
  Per-surface isolation therefore cannot rest on it: web surfaces are
  isolated by the engine's own profile, and local apps must be isolated
  by the `storage` capability. See `ANDROID.md` — on Android every hush
  process shares one uid anyway, so capability mediation is not one
  option among several, it is the only one.
- Whether **Vanadium's** WebView exposes the profile and UA-override
  features. Feature availability tracks the WebView APK version.
  **Partially resolved 2026-08-09:** on stock WebView 133 every feature
  the design needs is present — `MULTI_PROFILE`, `DOCUMENT_START_SCRIPT`,
  `WEB_MESSAGE_LISTENER`, `CREATE_WEB_MESSAGE_CHANNEL`, `POST_WEB_MESSAGE`,
  `USER_AGENT_METADATA` — and cookie isolation between profiles was
  verified rather than assumed. `Probe.kt` answers the Vanadium half in
  one launch on a Pixel. Note that `deleteProfile` throws once a profile
  is in use, so profiles are cheap to create and awkward to reclaim.
- What viewport emulation is actually achievable on Android WebView.
  CDP-grade metrics override is an Electron capability and may not
  port — see the caveat in `SHELL.md`.
- Whether the grant table belongs in the registry or separately. It is
  synced state and security-critical state, which may want different
  durability guarantees.
- Push wake-path behaviour under doze. Needs measurement, not design.
- Live-surface memory ceiling on a real phone with three surfaces.
