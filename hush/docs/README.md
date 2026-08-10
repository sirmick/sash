# hush — documentation

**One private space for the things that should be private, identical on every
device you own, backed by storage you control — installed as an ordinary app, on
the phone you already have.**

---

## Read in this order

| | | |
| --- | --- | --- |
| 1 | [`PRODUCT.md`](PRODUCT.md) | What this is and who it is for. The problem, first run, the **claim ladder** — what it protects against at each tier and what it does not. Start here. |
| 2 | [`DECISIONS.md`](DECISIONS.md) | Why the design is what it is. Each entry records the alternatives and **what would reverse it**; a decision without a reversal condition is a belief. |
| 3 | [`SHELL.md`](SHELL.md) | The architecture. Trust roles, surfaces, profiles and identity roots, composition, the capability model, the registry, origin locking. |
| 4 | [`PROVIDERS.md`](PROVIDERS.md) | The provider layer — the capability catalogue, token discipline, per-platform backends, and the mock backend that keeps CI device-free. |
| 5 | [`STATE.md`](STATE.md) | State and storage. Three planes, sync scope, merge discipline, and the one-minute onboarding bar everything is derived backwards from. |
| 6 | [`ANDROID.md`](ANDROID.md) | The Android host as actually built. Process model, verified constraints, the dev shortcut and its expiry, the development environment. |
| 7 | [`BOUNDARY.md`](BOUNDARY.md) | How hush depends on wash without becoming part of it, and why the module path is deliberately foreign. |
| 8 | [`WASH_CHANGES.md`](WASH_CHANGES.md) | What has to change in wash, what deliberately does not, and in what order. |
| 9 | [`ROADMAP.md`](ROADMAP.md) | Status, the near-term step sequence, and the milestones. |
| 10 | [`ENGINE.md`](ENGINE.md) | Why hush embeds an engine rather than driving a browser: the kludges that costs, what being the embedder buys, and the one unverified question that would reopen it. |
| 11 | [`CHROME.md`](CHROME.md) | The 0.3 implementation plan — hush's own home screen, the registry, and the first five real apps. What is being built right now. |

## The short version

**The strategy.** Mobile privacy loss is overwhelmingly legitimately-granted
permissions being monetised, not exploitation. The desktop web already solved
this by being ungovernable — no chokepoint, so no allowlist, so risk engines
instead. Route what matters through the interface nobody can gate. Attestation is
never fought, only routed around.

**The architecture.** A chrome that is a web app and holds no OS capabilities;
surfaces that are origin-locked web contexts with their own profiles; a broker
that holds all policy and does no I/O; providers that do all I/O and hold no
policy. Because the UI is unprivileged, it is replaceable.

**The adoption model.** It layers rather than replaces. Install it on the phone
you own, move one theme across, keep every native app you have not moved.
GrapheneOS is an optional later upgrade, never an entry fee.

**Where it is.** The substrate works: the wash router runs inside an Android app
sandbox and spawns apps, and a WebView renders a shell — against an unmodified
wash. The product does not exist yet: no chrome of its own, no origin-locked
surface, no broker, no mediated capability.

## Provenance

These documents were designed in conversation before implementation began, and
have been corrected where building contradicted them. Corrections so far:

- `--fs-root` is **per-router and advisory**, not per-instance and enforcing, so
  per-surface isolation cannot rest on it (`DECISIONS.md`, `SHELL.md`).
- The MessagePort terminates at the Surface Host; **the router needs no new
  transport** (`DECISIONS.md` C2, which contradicted `SHELL.md`; SHELL was right).
- wash core changes drop from four to three, and the survivors are smaller than
  written — `--listen-raw` and several other flags already exist
  (`WASH_CHANGES.md`).
- The Kotlin wire client moves much later: it is not needed to close the
  listener (`ROADMAP.md`).
- WebView capability availability is **no longer an assumption** — every feature
  the design needs is present on stock WebView 133 and profile cookie isolation
  was verified to hold (`ANDROID.md`). Vanadium remains untested.

Where a document still says something building has not yet tested, treat it as a
design intention rather than a fact.
