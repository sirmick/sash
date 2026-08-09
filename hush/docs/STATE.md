# hush — state and storage

Portability is the product. A familiar shell over your own stuff, on
whatever device you're holding.

**The bar: onboard a new device in one minute and not be frustrated.**
Everything below is designed backwards from that sentence.

---

## Table of contents

- [Three planes](#three-planes)
- [Scope is the core idea](#scope-is-the-core-idea)
- [Sync inventory](#sync-inventory)
- [Settings, and Android's limits](#settings-and-androids-limits)
- [Why grants never sync](#why-grants-never-sync)
- [Merge discipline](#merge-discipline)
- [Onboarding](#onboarding)
- [Endpoint and metadata](#endpoint-and-metadata)
- [Schema versioning](#schema-versioning)
- [Deferred](#deferred)

---

## Three planes

hush writes almost none of its own sync. Three planes, two of them
bundled third-party software, each solving a problem with genuinely
different requirements.

| Plane | Carries | Mechanism | Merge |
| --- | --- | --- | --- |
| **Secrets** | Passwords, passkeys, TOTP | **Vaultwarden** (bundled) | Bitwarden's |
| **State** | Registry, contacts, calendar, settings, layout, bookmarks | CRDT documents | Per-field |
| **Files** | Photos, downloads, notes, documents | **Syncthing** (bundled) | Conflict-by-copy |

**Secrets are load-bearing.** Logging in per-app per-device is
acceptable *only* because the vault makes it one tap. If vault sync is
broken the product is unusable; if contacts sync is broken it is
annoying. That asymmetry decides build order.

**Files are just files.** No content-addressed store, no namespace
layer, no replication policy, no cache eviction. Photos are files in a
directory; the Gallery is a view of that directory; the camera writes
to it. "Local or synced" is *which folder*, which users can reason
about in a way replication classes never allowed.

**Only the state plane is hush's own**, and it is kilobytes.

### Option under consideration

Carry the state plane *over Syncthing too* — CRDT documents are files.
When Syncthing produces a `.sync-conflict-*` file, a small daemon
merges and deletes it, which is exactly what CRDTs are for. hush would
then write no sync protocol at all: one transport, one thing to
configure, one thing to explain.

The fiddly part is conflict-file handling and write atomicity. The
merge itself is easy, because the payloads are CRDTs.

### What this buys

No server is required for files at all — Syncthing is peer-to-peer.
Vaultwarden is the single exception, because vault sync genuinely needs
server logic, and it is a single binary over SQLite that runs on
localhost by default and promotes by copying a data directory.

**The whole backend story: one small optional server, and nothing
else.**

---

## Scope is the core idea

Every piece of state carries a scope. Getting this wrong produces the
classic sync bug — your laptop's volume changes because you muted your
phone — and retrofitting it after people have devices is painful.

| Scope | Travels to | Examples |
| --- | --- | --- |
| `user` | Every device | Contacts, calendar, bookmarks, registry entries, theme, locale |
| `device-class` | Like devices only (all phones, all desktops) | Grid layout, pinned surfaces, ring profile preferences |
| `device` | Nowhere | Volume, brightness, window geometry, last-focused surface |
| `never` | Not in the sync store at all | Grants, session cookies, auth tokens, private keys |

`never` is not a weaker form of `device` — it means the data is not
written to the sync store under any circumstances, even encrypted.

---

## Sync inventory

### Registry — `user`

Everything except grants:

`id`, `name`, `icon_ref`, `kind`, `origins[]`, `network`, `viewport`,
`identity_root`, `handles`, `open_policy`

Note `icon_ref` rather than `icon`. Icons are content-addressed blobs;
binary payloads do not belong in a CRDT document.

`capabilities[]` is **excluded** — see
[Why grants never sync](#why-grants-never-sync).

### Layout — `device-class`

Grid positions, folders, ordering, drawer state, pinned surfaces. A
phone layout has no meaning on a 27" monitor, so classes stay separate
rather than trying to transform between them.

### PIM — `user`

Contacts and calendar as CRDT documents.

### Files — Syncthing

Not part of the state plane. Directories, synced wholesale:

| Folder | Notes |
| --- | --- |
| `photos/` | Camera output and imported roll. Pull order **Newest First** |
| `downloads/` | Browser downloads |
| `notes/` | Markdown files. The Notes app is a view over this directory |
| `documents/` | Everything else |

Selective sync is a nice-to-have, not a requirement — modern phones
hold the archive. Where it is wanted, it is folder structure
(`photos/recent` versus `photos/archive`), not a hush feature.

### Settings — mixed scope

Per-key scope, per the table in the next section.

### Not synced, deliberately

| | Why |
| --- | --- |
| Capability grants | Would make the backend an escalation path |
| Cookies and session storage | Engine-specific formats; not portable even in principle. Per-device login is an accepted cost, paid for by the vault |
| Message content and messenger identity keys | Each messenger's linked-device protocol owns these; copying identity keys breaks the guarantee |
| Browser history | Deferred — see [Deferred](#deferred) |

---

## Settings, and Android's limits

A hush Settings app is **not** a replacement for Android Settings, and
the UI must be graceful about that rather than presenting broken
controls. Three visually distinct categories:

| Category | Presentation |
| --- | --- |
| hush's own settings | Full control, inline |
| Device settings hush can write | Full control, inline; some behind a one-time grant |
| Everything else | An obvious hand-off — "Open in Android Settings" |

### What's actually writable

| Setting | Android reality | Sync scope |
| --- | --- | --- |
| Stream volumes (ring, media, alarm) | `AudioManager` — free | `device` |
| Silent / vibrate mode | Needs notification policy access | `device` |
| Do Not Disturb | Needs notification policy access | `device-class` (schedule), `device` (current state) |
| Per-window brightness | `WindowManager.LayoutParams` — free | `device` |
| System brightness | `Settings.System` → needs `WRITE_SETTINGS` (an appop, granted on a dedicated system screen) | `device` |
| Default ringtone | Needs `WRITE_SETTINGS` | `device-class` |
| Alarms | Your own via `AlarmManager`; exact alarms need `SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM` | `user` |
| Wi-Fi enable/disable | **Not available** to apps since Android 10 — panel intents and network suggestions only | — |
| Bluetooth enable | **Not available** — intent only | — |
| Location master toggle, app permissions | **Not available** — `Settings.Secure` / `Global` are signature-level | — |

The pattern: `Settings.System` is reachable behind a grant;
`Settings.Secure` and `Settings.Global` are not reachable at all.

There is an escalation path — hush as a privileged system app on
GrapheneOS would unlock `WRITE_SECURE_SETTINGS` — but it costs the
"it's just an app" property the adoption story rests on. Not planned.

---

## Why grants never sync

Three reasons, and the first is decisive.

**It would make the sync backend an escalation path.** If grants
travel, then compromising the backend — or compelling its operator —
grants capabilities on every device you own. The backend must never be
able to increase authority.

**Half of them are meaningless cross-platform.** A `telephony` grant on
a desktop is not a grant, it's a category error.

**A grant is a decision about a device, not about a user.** Consenting
to contacts access on your phone is not consenting on the laptop you
use at work.

**Cost:** first launch of each app on a new device re-prompts. That is
a handful of taps once per device, and it is the correct trade.

---

## Merge discipline

| Data | Structure | Conflict |
| --- | --- | --- |
| Settings | CRDT map | Per-key LWW |
| Contacts, calendar | CRDT map with per-field registers | Per-field, not per-record |
| Layout | CRDT map keyed by app id | Per-key LWW |
| Notes, files, photos | Content-addressed blobs | None — immutable |

**Use logical clocks, never wall time.** Phone and laptop clocks drift,
and wall-clock LWW silently loses edits when they do. Hybrid logical
clocks give causal ordering with a human-readable component.

**Per-field, not per-record, for contacts.** Editing a phone number on
your phone while editing an address on your laptop should merge, not
pick a winner.

**Plan compaction now.** CRDT documents retain operation history and
grow without bound. Periodic snapshot-and-truncate, with a retained
tail long enough that any device that's been offline for a reasonable
period can still merge.

---

## Onboarding

The one-minute bar, worked backwards.

### One pairing artifact

Stock Syncthing pairing fails the bar on its own: copy a 56-character
device ID, add it on the other device, accept on both, share each
folder, accept each share. Five-plus interactions.

**Users never see Syncthing.** hush drives it over its REST API. There
is exactly one pairing artifact — a QR from an existing device carrying
the Vaultwarden URL, the state-plane key, and the Syncthing device ID
and address. Scan once, all three planes configured. Syncthing's
*introducer* flag then propagates the remaining devices and folder set
automatically; the feature exists for precisely this.

### Sequence matters

Pair the cheapest and most load-bearing plane first:

```
1. Vaultwarden   -> vault unlocked
2. State         -> grid, apps, contacts, settings, layout
3. Syncthing     -> files begin arriving in the background
```

After pairing, the user's next action is opening their bank. If the
vault is up, that is one tap and the product feels like magic. If the
vault is still syncing, they are typing a password on a phone keyboard
and the promise deflates. **This ordering is a requirement, not an
implementation detail.**

### Usable is not complete

| | What's there |
| --- | --- |
| ~10s | Grid, apps, contacts, settings, layout, vault |
| minutes | Recent photos and downloads |
| hours | The full archive |

Nothing in the UI may block on file sync. The Gallery shows what has
arrived and fills in.

### The asymmetry

The one-minute bar applies to devices 2..N. **Device one must hash the
entire archive to build its index** — hours, and a hot phone. Frame it
honestly as one-time setup and prefer doing it on a desktop.

### Defaults that decide whether "not frustrated" holds

- **WiFi-only and charging-only** for the initial pull. Otherwise a new
  phone burns a data plan and cooks itself in the first hour. Syncthing
  has rate limits; network-type gating is the supervisor's job.
- **Global discovery and relays off.** Both are on by default and leak
  device IDs and addresses to third-party infrastructure. Static
  addresses over WireGuard or Tailscale instead.
- **Progress visible, never modal.** An indicator, not a setup screen.
- **File Pull Order: Newest First** on `photos/`. The difference
  between recent photos in two minutes and an alphabetical crawl from
  2019.

### Keys

Vaultwarden owns the vault key — Bitwarden's KDF, and **set Argon2id
explicitly**; the historical iteration defaults were weak.

Syncthing owns device identity and transport encryption.

hush owns only the state-plane key: generated at first run, sealed to
hardware, wrapped to each device's keypair at pairing and to a
**printed recovery code**. The recovery code is required in v0 — without
it, losing every device loses everything, and that will happen to
someone in the first month.

Revocation is forward-only. Removing a device stops future access, not
past. Say so in the UI.

---

## Endpoint and metadata

**Files need no endpoint.** Syncthing is peer-to-peer; devices talk
directly.

**Vaultwarden is the one server**, and it should sit behind WireGuard or
Tailscale. It sees encrypted vault blobs and connection metadata.

The residual leak is the same one as ever: **anything contacted from
every network you join is a location trail.** Source IPs plus
timestamps reconstruct movement — precisely the surveillance the
product exists to avoid, rebuilt by hand. Running everything over your
own overlay network is what collapses that to a single exit point, and
it is the recommended posture rather than an advanced option.

---

## Schema versioning

Devices will run different hush versions against one store. Two rules:

1. Every document carries a schema version.
2. **Unknown fields are preserved, never dropped.** A v1 device must
   round-trip v2 fields it doesn't understand. Without this rule, one
   old device silently deletes newer data every time it syncs.

The second rule is cheap now and impossible to add retroactively.

---

## Deferred

**Browser history.** Not in v0, and it needs a decision rather than a
default when it lands. It is more sensitive than contacts, unbounded in
growth (a poor CRDT fit), and it leaks through the envelope — update
size and frequency reveal browsing intensity, timing reveals when.

Worse, it fights the architecture: per-surface profiles exist so that
one surface knows nothing about another, and a merged synced history
rebuilds exactly that linkage in your own store.

When it lands: **per-profile, default-off, retention-capped.**

**Per-field merge for notes.** Notes as Syncthing files means two
devices editing offline produce `note.sync-conflict-*.md`. Obsidian
users live with this daily, so it is acceptable — but it is a real
regression from CRDT merge and a stated trade, not an oversight.
