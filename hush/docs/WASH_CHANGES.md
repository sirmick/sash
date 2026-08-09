# hush — required changes

What has to change in the existing codebase to host the shell, what
deliberately doesn't, and the order to do it in.

The headline: **the core changes are three, two of them flags.** Almost
everything else is new apps on unmodified machinery — and rather less
even than that, since the Android host was built and run against an
*unmodified* wash (see `ANDROID.md`). Verify before you build: several
flags this document assumed were missing already exist.

---

## Goals

1. **Keep the router a transport.** Policy goes in an app, not the
   thing that moves bytes. This is the property worth protecting
   through every other change.
2. **Make the process boundary safe for hostile content.** Web surfaces
   render code from parties you don't trust, inside the same device as
   the capability API.
3. **One wire, three languages.** Go, TypeScript, and later Kotlin
   implementations that agree, because the host process must be a wash
   app on every platform.
4. **No forking.** The phone is a different catalogue and a different
   `desktop` app on the same router and SDK.

---

## Core changes

### 1. `--no-listen` — smaller than written

Today the router serves the shell bundle and a WebSocket on
`0.0.0.0:11000`. For the shell that listener must not exist.

**Half of this already ships.** `--listen-raw unix:<path>` serves the
shell wire as raw length-prefixed frames with no HTTP and no WebSocket,
and `--transport=virtio-console|serial|fd` exist for the v86 demo. So
the wire half is done. What is missing is only the asset half: a mode
that does not bind `--listen` while the host loads the chrome bundle
from disk. On Android that is `WebViewAssetLoader`, which is the host's
job, not the router's — so this may reduce to "do not bind `--listen`".

Any origin can issue requests to a localhost port; CORS restricts
reading responses but simple requests fire regardless. On Android, any
installed app can connect too. A listening socket puts the entire
capability API within reach of every page and every app on the device.

**Change.** A mode where the router binds a unix socket only: no HTTP
server, no WebSocket listener, no shell serving. The host process loads
the chrome bundle from disk and connects over the unix socket.

**Non-negotiable before any web surface renders.**

### 2. Reserved ids become a set

`com.wash.priv` is currently the single special case — a reserved id
served only from a trusted path. The broker needs identical treatment.

**Change.** Generalise the special case into a list, with the same
provenance requirement (uid-0-owned binary, or a declared trusted
directory).

### 3. TypeScript wire client for Node

The real work item, and it unblocks everything downstream: Electron
main must **be** a wash app to own the surfaces.

**What exists.** `web/shell/src` already speaks the wire in TypeScript
over WebSocket.

**What's missing.** A unix-socket transport for Node, plus the app-side
handshake — `identity` → `identity.ack`, channel binding, `app_msg`
dispatch. A port of the transport layer and the app half of
`internal/sdk`, not a from-scratch implementation.

Worth doing carefully: Kotlin needs the same thing later, and three
implementations that disagree is a long-term tax. Golden frame vectors
in a shared `testdata/` directory, with a conformance test in each
language, are what keep them honest — the Go conformance harness is
`internal/wiretest` and is unreachable from hush by design.

**Later than this document implies.** A wire client is *not* needed to
close the listener: `--listen-raw` carries the same frames, so the host
can pipe bytes between a MessagePort and the unix socket without
understanding them. A real client is needed only when the host language
becomes an *app* — the Kotlin SMS and contacts providers.

### 4. ~~Verify `--fs-root` is per-instance~~ — resolved, and dropped

**It is per-router, and advisory rather than enforcing.** Promoting it
to per-instance would not help, because it was never a boundary: apps
opt into `wfs.New(c.Session().Root)` and nothing stops a direct
`os.Open`. Per-surface isolation comes from the engine profile and the
`storage` capability instead. See `DECISIONS.md` "Still open".

### A fourth change that was needed: the promotion to `pkg/` — **done**

Not in the original list, and unavoidable the moment hush has Go code of
its own, since a foreign module cannot import `internal/`.

Landed 2026-08-09:

```
internal/wire            -> pkg/wire
internal/sdk             -> pkg/sdk
internal/apps/registry   -> pkg/apps/registry
```

Package names were kept, so 182 files changed import paths only and no
call site moved. Build, vet, and the full unit suite green. `pkg/sdk`
continues to import `internal/fs` and `internal/fswatchproto`, which is
legal within the module and invisible to external callers because
neither type appears in its exported API.

This is the **first and so far only** modification to wash. See
`BOUNDARY.md`.

Note that **reusing** wash's catalogue apps needs no change at all:
`apps/<x>/be` is already outside `internal/`, so hush links them today.

---

## What does not change

**The wire.** Frame format, channel disciplines, and QoS classes are
fine as they are.

**Transport purity.** Policy lands in the broker, which is an app. The
router still never parses payloads.

**Multi-window-per-instance.** This looks like a blocker and isn't.
Rather than teaching one app instance to own N windows, **open one
router connection per surface from the host process**. Each gets its
own `instance_id` and `window_id` naturally, WM state needs no new
concepts, and the taskbar treats a surface exactly like a terminal. N
connections from one process is just N sockets.

**The remote relay.** Peer wire splicing already exists for SSH hosts.
If the chrome ever needs to reach the router through the host process
rather than directly, the mechanism is there.

---

## New apps

| App | Surface | Purpose |
| --- | --- | --- |
| `broker` | `background` | Reserved id. Grant table, token minting, registry, intra-shell dispatch. The entire policy surface. |
| `surface` | — | The host process as a wash app. One connection per surface. Positions views, relays MessagePort traffic to the broker with the surface id stamped. Rewritten per platform; five operations. |
| phone chrome | `desktop` | New, not `wash-session`. Grid and drawer instead of taskbar and floating windows. Coexists — different catalogue. |
| `credentials` | `background` | **Vaultwarden client** plus the injection layer. The load-bearing one. |
| `state` | `background` | CRDT documents for registry, settings, PIM, layout. |
| `files` | `background` | Syncthing supervisor: REST driving, pairing, network gating, progress. |
| `settings`, `contacts` | `window` | Local JS apps. The end-to-end proof of the capability path. |

Two binaries are bundled beside the wash multicall binary:
**Vaultwarden** (Rust) and **Syncthing** (Go).

---

## Order

| Step | Why here |
| --- | --- |
| 1. TS wire client | Nothing else moves without it |
| 2. `--no-listen` + reserved-id set | An afternoon between them |
| 3. `surface` + broker with one hardcoded grant | **Proves the architecture** — one surface rendering origin-locked |
| 4. Phone chrome, registry, add-app flow | The product becomes demonstrable |
| 5. Vaultwarden bundled + autofill into surfaces | **Proves the capability path** end to end, on the load-bearing plane |
| 6. Syncthing + state sync + QR pairing | **Proves portability** — the actual thesis |

Steps 3, 5, and 6 are the ones that de-risk the thesis. Before them is
plumbing; after them is volume. See `ROADMAP.md` for the full staging.

---

## Risks to check first

**Does anything assume one process equals one connection?** Process-keyed
state, control-socket assumptions, `/proc`-based session discovery. If
N connections from one pid causes trouble, that is a core change and it
is much better found in week one than week six.

**Does `--fs-root` confine per instance?** See core change 4.

**Does the WM model tolerate a `desktop` app that isn't
`wash-session`?** It should — the surface type is generic — but the
phone chrome is the first thing to test it.

---

## Deliberately deferred

- **Compositor work.** Android composites in its own view hierarchy;
  no wlroots, no seat management, no `text-input-v3`. This is a
  postmarketOS requirement, not an architectural one.
- **`wash-display` on-device.** Its capture-and-forward path is the
  remote case. Direct subsurface composition is new work, needed only
  for pmOS.
- **Kotlin wire client.** After the TS one is stable and two providers
  exist — then spike `surface` on Android before adding features.
