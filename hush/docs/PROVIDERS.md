# hush — the provider layer

Providers are where the shell touches the device. Each is a long-lived
headless service — one process, one permission set, and **no policy
logic whatsoever**.

The process shape already exists as `surface: background`, which does
exactly this. Everything below — the capabilities, the token
discipline, the backends — is new work.

---

## Table of contents

- [The rule](#the-rule)
- [Capability catalogue](#capability-catalogue)
- [Token discipline](#token-discipline)
- [Per-platform backends](#per-platform-backends)
- [The mock backend](#the-mock-backend)
- [Notable providers](#notable-providers)
- [Authoring rules](#authoring-rules)

---

## The rule

> **The broker holds all policy and does no I/O. Providers do all I/O
> and hold no policy.**

Providers never inspect who is calling. They validate a broker-minted
token, check it authorises the exact operation requested, and execute.
This is the classic reference-monitor split, and it is what keeps
enforcement consistent and auditable in one place rather than scattered
across a dozen processes each with its own idea of who is allowed to do
what.

The consequence: a compromised provider leaks its own domain and
nothing else. A compromised chrome holds only the grants it was given.

---

## Capability catalogue

Every capability is independently grantable and defaults to deny.

| Capability | Operations | Notes |
| --- | --- | --- |
| `telephony` | place, answer, hang up, DTMF, call state events | **Bidirectional** — incoming call events push into the surface. Time-critical path stays native (see `SHELL.md`). |
| `messaging` | send, receive, thread list | SMS/RCS only. Not E2EE messengers. |
| `pim.contacts` | read, write, watch | Backed by the shell's own store, not the OS address book. |
| `pim.calendar` | read, write, watch | Same. |
| `media.camera` | capture session, still, video | Frames never cross into JS; the surface draws controls over a native viewfinder. |
| `media.audio` | record, playback, focus | |
| `location.coarse` | one-shot fix | Separate grant from continuous. |
| `location.continuous` | subscribe | Expensive, conspicuous, rarely justified. |
| `peripheral.ble` | scan, connect, GATT read/write/notify | **Scoped per peer address and service UUID.** |
| `peripheral.spp` | Bluetooth Classic serial | Needed for vendor protocols Web Bluetooth cannot reach. |
| `credentials` | Autofill, WebAuthn assertions, seal/unseal | A **Vaultwarden client**, not a vault. hush owns injection and CTAP2 logic; Bitwarden owns the vault, its crypto, and its sync. See [credentials](#credentials). |
| `storage` | per-origin partition, scoped filesystem | Files are plain files; Syncthing handles sync. See [storage](#storage). |
| `notifications` | post, cancel | Default-granted to web surfaces. |
| `push` | register endpoint, receive wake | |
| `power` | wakelock | Rate-limited and audited. |
| `sensors` | accelerometer, gyro, etc. | Rarely granted; gyro calibration offsets are a stable hardware fingerprint. |
| `settings` | read/write shell settings, call native OS settings APIs | How the Settings JS app works. |
| `spawn` | launch another surface | Chrome only. Already exists. |

**Not a capability:** `nfc.hce`. Card emulation requires a secure
element the shell cannot obtain, and the shell does not pretend
otherwise. Tag read/write may be exposed as `peripheral.nfc` later.

---

## Token discipline

Grants are coarse and durable; tokens are fine and ephemeral.

A grant says *this surface may use Bluetooth*. A token says *this
surface may read GATT service `0x180D` on peer `AA:BB:CC:DD:EE:FF`,
expiring in 60 seconds*.

```
grant table  (broker, persistent, synced, user-visible)
     |
     v
token        (broker, in-memory, scoped, short-lived, single provider)
     |
     v
provider     (validates, executes, forgets)
```

Tokens carry: capability, parameter scope, expiry, and the originating
surface id. Providers reject anything else. Nothing about the calling
process's identity is consulted — the router already attested `from`
when the request reached the broker, and that is the only place it
matters.

Scoping rules worth enforcing rather than documenting:

- BLE tokens name a peer and a service. Never "Bluetooth."
- Location tokens name coarse-or-continuous and an expiry. Never
  "location."
- Storage tokens name a partition. Never a path.

---

## Per-platform backends

One capability schema, three implementations. Cap'n Proto is worth
preferring over protobuf here for its object-capability semantics —
capabilities as first-class references with promise pipelining, which
is the actual model rather than an approximation of it.

| Capability | `android` | `linux` | `mock` |
| --- | --- | --- | --- |
| `telephony` | `InCallService`, `TelephonyManager` | ModemManager | Scripted call events |
| `messaging` | Default SMS role | ModemManager | Fixture threads |
| `pim.*` | Shell store + sync | Shell store + sync | Fixture store |
| `media.camera` | Camera2 / CameraX | PipeWire / v4l2 | Static images, synthetic video |
| `media.audio` | AudioManager | PipeWire | Silence / test tones |
| `location.*` | `LocationManager` (GNSS) | GeoClue | Replayable GPX tracks |
| `peripheral.ble` | Android BT stack | BlueZ | Simulated peers, scriptable disconnects |
| `credentials` | StrongBox / KeyMint | TPM 2.0 via tpm2-tss, Secret Service | Software keys, deterministic |
| `credentials` (client + CTAP2) | Same code | Same code | Same code — platform-independent by design |
| `notifications` | NotificationManager | `org.freedesktop.Notifications` | Captured to log |
| `push` | Host-held Web Push connection | Same | Injectable |
| `power` | Wakelocks | logind inhibitors | No-op, counted |

Transport: Binder/AIDL on Android, unix sockets or D-Bus on Linux,
in-process on mock.

**Linux is the easy target** precisely because freedesktop already has
a maintained daemon for nearly every row. The Linux broker is largely a
thin adapter over services that exist and are someone else's problem.

**Capability absence is not an error case.** A surface asking for
`telephony` on a desktop gets the same denial it would get from a user
declining the grant. No special handling anywhere in the app layer.

---

## The mock backend

Mock is a **shipped, maintained backend**, not scaffolding.

What it buys beyond dev speed:

- **Deterministic integration tests.** Fixtures as YAML, same
  every run.
- **CI with no devices in the loop.** The overwhelming majority of the
  suite needs no hardware.
- **Demo mode.** Plausible fake data for screenshots and walkthroughs.
- **Fault injection** — the one thing you cannot get any other way:

  | Fault | What it exercises |
  | --- | --- |
  | Grant denied mid-session | Every capability call site's error path |
  | Provider hangs / crashes | Broker timeout and restart handling |
  | Token expiry during a call | Re-mint flow |
  | Sync conflict | CRDT merge, user-visible resolution |
  | Device revoked remotely | Key rotation, re-pair prompt |
  | Offline restore | First-run passphrase path |
  | BLE peer disconnects mid-write | Peripheral bridge recovery |

These are the paths that break in the field and are untestable on real
hardware.

Development loop: `--broker=mock` and the entire app layer runs in a
browser tab against a full capability API, with sub-second
edit-refresh. Build the app layer, capability schema, registry, and
sync protocol here first; port the broker to Android last.

---

## Notable providers

### `sync`

Two data disciplines, deliberately separated:

- **CRDT state** (Automerge or Yjs) — contacts, calendar, settings,
  bookmarks, registry, grant table. Small, mutable, concurrently
  edited. Multi-writer from day one, because phone and desktop are live
  simultaneously; retrofitting this onto last-writer-wins is expensive.
- **Content-addressed blobs** — photos, call history, message archive.
  Append-only, so nothing conflicts.

Device enrollment by QR pairing from an existing device. Per-device
keypair; the sync key is wrapped to each. Revocation per device. This
is the same linked-device pattern the messengers use, which is a good
sign the shape is right.

Endpoint is user-supplied. Home-hosted behind WireGuard or Tailscale is
the recommended posture — a sync service contacted from every network
you join is otherwise a location trail, which is the thing the product
exists to avoid.

**Out of scope:** message content. Each messenger's linked-device
protocol owns that, and copying identity keys would break it.

### `push`

The host holds **one** Web Push connection (RFC 8030, payloads
encrypted under RFC 8291) and wakes guest service workers. One socket
for every web app on the device.

This matters for power, not convenience: N surfaces with independent
connections multiply RRC idle→connected transitions on the modem, and
that state machine is where standby battery goes.

The relay sees endpoint metadata but not content. That is a real
metadata leak and should be stated, not hidden.

### `peripheral`

One provider covers all of BLE GATT and Bluetooth Classic SPP. Note
that Web Bluetooth would not suffice even on Chromium — it is GATT-only
and cannot reach Classic profiles, which several vendor protocols
require.

Bluetooth Classic audio, HID, HFP, AVRCP, and PBAP need **no provider
at all** — they are host-stack, no app, no attestation. Headphones, car
audio, keyboards, and controllers already work.

Do not virtualise Bluetooth. HCI-level forwarding gives a guest the
entire controller view — every paired device, every scan result, every
address — which is coarser than the per-peer scoping above. A mediating
provider on the host is strictly better than a VM.

### `credentials`

**hush does not implement a vault.** Vaultwarden is bundled and owns
the vault format, its cryptography, and its sync. The provider is a
*client*.

That deletes the three hardest things on the list — vault crypto, vault
sync, and conflict resolution over secrets — and inherits an
established ecosystem, since Bitwarden clients exist on every platform
and work before hush's own UI does.

**What hush still owns:** the injection layer. Extensions were dropped,
so Bitwarden's browser extension isn't available, and autofill into
surfaces has to be hush's. That is the non-cryptographic part, which is
exactly the part worth owning.

```
surface JS
  form fields / navigator.credentials      <- intercepted in the surface
        |  capability port
        v
  broker  -- resolves the surface's registry origin as the RP ID
        |
        v
  credentials provider (Bitwarden API client)
        -- decrypted vault held in memory, unlocked per session
        -- autofill match by origin
        -- CTAP2 assertions from vault-stored passkeys
```

#### Passkeys are synced, not hardware-bound

A deliberate decision, and it follows from the product thesis.

| | Portable | Strength |
| --- | --- | --- |
| **Synced** (chosen) | Yes — private keys live in the vault | As strong as the vault passphrase plus endpoint |
| Hardware-bound | No — re-register per device, per site | Key cannot leave the device |

Apple, Google, and 1Password all chose synced for the same reason.
Hardware-sealed passkeys would break "install hush, sign in, away we
go" for exactly the credentials you most want to carry.

Note this is *not* the same as platform WebAuthn, which both Electron
and Android WebView gate behind domain association to a site your app
owns — unavailable to a browser-shaped app. hush performs the CTAP2
operations itself against vault-held keys.

**hush is unusually well placed to do this correctly.** The hardest
part of being an authenticator is establishing the true RP ID. A
browser derives it from the page; hush has it declared in the registry
and enforced by origin locking.

#### Seal / unseal

Separate from the vault: arbitrary blob encryption bound to device
hardware, for the state-plane key and any local app needing a
device-bound secret. No web API provides this — WebAuthn is
signature-only.

#### Known unknown

Vaultwarden is Rust. Cross-compiling it to `aarch64-linux-android` and
running it inside an app sandbox is **untested** and should be spiked
before Android work is planned around it. On desktop it is a bundled
binary alongside the wash multicall binary.

---

### `storage`

There is no storage provider in the wash sense. **Files are files.**
Syncthing is bundled, driven over its REST API, and the local apps
(Notes, Gallery, Files) are views over directories.

The provider surface reduces to scoped filesystem access plus a
Syncthing supervisor: folder configuration, pairing, network-type
gating, and progress reporting. See `STATE.md`.

---

## Authoring rules

1. **No policy in a provider.** If you are writing an `if` about who is
   calling, it belongs in the broker.
2. **Validate the token against the exact operation**, not the
   capability class. A token for one peer address does not authorise
   another.
3. **One permission set per provider process.** If a provider needs two
   unrelated OS permissions, it is two providers.
4. **Fail closed and fail quietly.** Denials return the same shape as
   an unimplemented capability; the app layer must not distinguish
   "denied" from "absent" except where the UI genuinely needs to prompt.
5. **Every capability gets a mock implementation in the same commit.**
   Non-negotiable — it is what keeps CI device-free.
6. **Providers never call each other.** They talk to the broker, which
   is the only thing holding references. The router-attested `from`
   field on every message is what makes this enforceable rather than
   merely conventional.
