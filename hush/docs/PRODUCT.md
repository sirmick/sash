# hush — product view

**One private space for the things that should be private, identical on
every device you own, backed by storage you control — installed as an
ordinary app, on the phone you already have.**

---

## Table of contents

- [The problem](#the-problem)
- [What this is](#what-this-is)
- [First run](#first-run)
- [Living with it](#living-with-it)
- [The claim ladder](#the-claim-ladder)
- [Three properties](#three-properties)
- [The migration ladder](#the-migration-ladder)
- [What comes across cleanly](#what-comes-across-cleanly)
- [What doesn't](#what-doesnt)
- [Non-goals](#non-goals)

---

## The problem

Mobile privacy loss is overwhelmingly **not** exploitation. It is
legitimately-granted permissions being monetised: contacts scraped on
first launch, background location sold to brokers, installed-package
enumeration, an advertising ID stitching it together. The delivery
vehicle is the native app and its embedded SDKs.

The existing answer — replace the OS — fails on adoption, not on
merit. GrapheneOS is excellent and demands a discontinuous switch:
unlock the bootloader, flash, lose apps, lose Android Auto, on day
one, before you have experienced a single benefit. Most people who
would benefit never start.

Meanwhile the *desktop* web has quietly solved most of this. Your bank
moves six figures from a Linux laptop with no attestation, no app, and
no SDK in your process. It works because the desktop is ungovernable —
there is no enumerable set of valid client states, so no relying party
could ever build the allowlist, so they built risk engines instead.
Those risk engines carry the bulk of the world's money today.

The strategy follows: **route what matters through the interface
nobody can gate.**

---

## What this is

An application shell. It hosts:

- **Origin-locked web surfaces** for external services. "Installing
  Chase" binds a name, an icon, and an origin allowlist. Launch never
  involves typing or reading a URL. Each surface has its own storage
  partition, its own network policy, and its own capability grants —
  by default, none.
- **Local apps** — contacts, messages, calendar, files, settings,
  gallery — as signed bundles at `app://` origins, talking to the
  device through a capability broker.
- **A short native list** for the handful of things that genuinely
  need it.
- **Sync** of the small, durable, portable state — contacts, settings,
  bookmarks, the installed-app registry — to a backend you host.

It does not replace your OS. It sits on top of one. The native app is
still in your drawer if you want it.

---

## First run

The whole adoption argument dies if setup feels like GrapheneOS. It
must feel like installing any other app.

### 1. Install

Play, F-Droid, sideload, `apk`, `deb`. Ordinary package, ordinary
install. **No permissions requested at install time.**

### 2. One question: new, or restore?

Restore is the *second screen*, not a settings sub-page. It is the
killer feature and it should be visible before anything else exists.

| Path | Flow |
| --- | --- |
| **Restore** | Sync endpoint → passphrase → QR pair from an existing device → everything appears: apps, contacts, layout, settings |
| **New** | Straight to the grid with a starter catalogue |

Sync setup can also be deferred. A shell with no backend still works;
it just doesn't follow you.

### 3. It opens to a grid

Not a wizard, not a tour. A desktop grid with an app drawer behind it.
Icons move, reorder, and delete. Preinstalled:

Browser · Phone · Messages · Contacts · Photos · Settings · **Add app**

### 4. Permissions arrive when they mean something

Nothing is granted up front. Each grant is triggered by an action and
names the surface it is for:

| Action | Prompt |
| --- | --- |
| Open Phone the first time | Become the default dialer |
| Open Messages | Become the default SMS handler |
| Open Photos | Storage access |
| Add a Bluetooth device | Bluetooth, scoped to that device |
| Install a web app that wants notifications | Notifications, for that origin |

**Refusal is graceful and permanent-until-changed.** A denied grant
leaves everything else working and that one feature disabled — the
identical code path to a platform that simply lacks the capability. No
nag, no degraded mode banner, no re-prompting on every launch.

### 5. Adding an app

**Add app** searches a signed catalogue that resolves a name to an
icon and an origin allowlist. That is the entire operation — no
download, no code, no review. Type a URL directly if you'd rather, or
point the shell at somebody else's catalogue, or none.

The catalogue is a phone book, not a gatekeeper.

### 6. Import is explicit

The shell does not read your OS contacts on first run. There is an
Import button, it says what it will copy, and it copies once.

### The risk in this story

An empty shell is a useless shell. The starter catalogue has to be good
enough that the screen after setup already has the user's bank, email,
and two social accounts on it. If the first five minutes require typing
URLs, adoption stops there.

---

## Living with it

**The grid is yours.** Folders, ordering, removal, per-app rename. What
you see is the registry, and the registry syncs — so a rearranged home
screen shows up on your laptop.

**Switching is task-switching, not app-launching.** Surfaces you pin
stay resident; everything else cold-starts. Two pinned surfaces is the
sane default (dialer, messages).

**The trust state is always visible.** Three unmistakable chrome
states — local app, origin-locked web app, arbitrary browsing — drawn
where page content cannot reach. You always know which one you are in.

**Going back is free.** Every native app you haven't moved is still in
your OS drawer. Nothing about installing the shell removes an option.

**Settings is a local app**, not a separate subsystem. Sync endpoint
and pairing, per-app capability review, per-app network policy, theme,
and the import tools. Capability review matters most: one screen
listing every grant, per surface, revocable individually.

---

## The claim ladder

Being precise here matters more than sounding strong. Overclaiming is
what destroys trust in privacy products.

| Where it runs | What it protects against | What it does not |
| --- | --- | --- |
| **Any Android** | Apps and their embedded SDKs. No contact scraping, no background location harvesting, no package enumeration, no ad-ID stitching, no third-party native code in your process. This is where most real-world privacy loss happens. | The OS itself, Play Services, the vendor, the carrier. |
| **GrapheneOS** | All of the above, plus a hardened runtime (MTE, hardened_malloc, JIT disabled by default), no Google in the trusted base, per-app network permission beneath per-origin policy. | A compromised OS. Targeted state-actor exploitation. |
| **postmarketOS** | No Android in the trust chain at all. | Hardening — no MTE, no hardened_malloc, no verified boot to a user key, no secure element. This is the *independence* target, not the *security* target. |
| **Never, anywhere** | — | A compromised operating system. Attestation-gated services. Anything where the counterparty is entitled to refuse you. |

**The honest formulation, per service:** the shell does not make a
counterparty trustworthy. It removes their reach into everything else
on your device. Gmail in a surface is still Google's inbox with all
the server-side exposure that implies — but it cannot read your
contacts, your location, or your installed apps, and no Google SDK
runs in your process. It doesn't make email private; it stops email
from being a sensor.

---

## Three properties

### 1. One space, every device

The same shell runs on phone, laptop, and desktop. Add a contact on
one, it's on all three. Add a bank, it's on all three. The app layer is
identical because it's web; only the provider layer differs by
platform.

Sync scope is deliberately small: contacts, calendar, settings,
bookmarks, the app registry. CRDTs for mutable state, content-addressed
append-only blobs for history. Device enrollment by QR pairing from an
existing device, per-device keys, per-device revocation.

**Message content is explicitly out of scope.** Each messenger has its
own linked-device protocol that gives every device a distinct identity
key. Syncing that would mean copying key material and breaking the
guarantee that makes those apps worth using. Adding a device means
pairing each messenger separately. That seam is load-bearing.

### 2. Gradual migration

Install it on the phone you own. Use it for banking only. Add email
when you're ready. Keep every native app you haven't moved yet.

There is no point at which you must commit. The OS swap is an optional
later upgrade, not an entry fee.

### 3. Escalating security

Security scales with effort invested, and each step is independently
valuable:

| Step | Effort | Gain |
| --- | --- | --- |
| Install the shell | Minutes | App/SDK containment, per-origin isolation |
| Move themes across | Days, spread out | Fewer native apps with standing grants |
| Self-host sync | An afternoon | No third-party holds your durable state |
| Move to GrapheneOS | A weekend | Hardened runtime, no Google in the base |
| Move to postmarketOS | A project | No Android at all |

Stopping at step one is a real win. Nothing above is required to make
the step below worthwhile.

---

## The migration ladder

Suggested order, by value-per-friction:

1. **Money** — bank and brokerage sites plus passkeys. Switch cost is
   *negative*: bigger forms, better session control, no SDK.
2. **Email** — webmail. Zero switch cost, largest containment gain,
   because the mail app is among the greediest things on a normal
   phone.
3. **Social** — full web everywhere. Real friction from install nags
   and interstitials; the largest absolute reduction in data flow.
4. **Home** — Home Assistant replaces a dozen vendor cloud accounts and
   is the right Matter/Thread hub anyway. Straight upgrade.
5. **Photos** — camera writes to your storage, your blob sync carries
   it, a local gallery reads it. Better than the cloud original.
6. **Messaging** — costs nothing; Signal stays native and that is the
   correct architecture, not a concession.
7. **Navigation** — last, and optional. This is the only genuinely
   painful trade in the whole list.

---

## What comes across cleanly

Three kinds, not three tiers. "Needs native" and "is a hole" are
different things — several themes resolve to a native app you would
choose anyway on privacy grounds.

### Kind 1 — web is equal or better

| Theme | Notes |
| --- | --- |
| Banking, brokerage, payments | Full web. Better than the apps. |
| Email | Gmail, Proton, Fastmail, Outlook — all complete. |
| Social | Facebook, Instagram, X, Reddit, LinkedIn, Bluesky, Mastodon. |
| Messaging (non-E2EE) | Telegram, Discord, Slack, Element, Teams, Google Messages web. |
| Shopping and delivery | Amazon, eBay, Instacart, DoorDash, Uber Eats. |
| Productivity | Workspace, Notion, GitHub, Todoist. |
| Smart home | Home Assistant. Outright better than vendor apps. |
| Government and health portals | IRS, DMV, MyChart. |
| Travel booking and check-in | Booking, Airbnb, airline sites. |
| Photos | *If* you use your own camera + blob sync. Google Photos as a surface is degraded. |

### Kind 2 — native is correct, and it's a privacy win

| Theme | App | Why native |
| --- | --- | --- |
| Private messaging | Signal / Molly | Web *delivery* breaks E2EE's threat model — the server can ship targeted code on any load. Not a capability gap. |
| Navigation | Organic Maps / OsmAnd | Background GPS, screen-off, voice. Offline-first and telemetry-free — a better outcome than the alternative. |
| Secrets | Keychain | Autofill provider + hardware-backed key storage are native-only. |
| Wearables and sensors | Gadgetbridge + BLE bridge | One app, large device catalogue, no vendor cloud. |
| Phone and camera | Split native/JS | Sub-second wake-and-draw for incoming calls; ISP pipeline for capture. |

Every Kind 2 theme resolves to exactly one well-maintained open app
that improves your position. That is four or five native apps total.

### Kind 3 — degraded but usable

| Service | Degradation |
| --- | --- |
| Netflix, Disney+, Prime, Max | Widevine L3 — 480–720p. Same as Firefox on Linux. |
| Spotify, Apple Music | Web player fine; no offline downloads; background playback needs a pinned surface. |
| Uber, Lyft | Booking works; live tracking and arrival push deliberately crippled. |
| Kindle | Cloud Reader; no offline. |
| Airlines | Static QR boarding passes fine; rotating ones no. |
| Transit | Highly variable by city. |

---

## What doesn't

One theme has no answer, and it is coherent: **being identified.**

Tap-to-pay, transit cards, hotel keys, CCC digital car keys, mobile
driver's licences, rotating event tickets. Here attestation is not
policy theatre — the entire value proposition is "hard to clone," so
the security claim is real. There is no architecture that fixes this.

Mitigations are physical (plastic cards, RFID fobs, printed passes,
collecting a key at the desk) or simply keeping the native app on the
same device, which the layering model permits. The real exposure is
where the physical fallback has already been retired — some transit
systems, some venues.

Also permanently out: Android Auto (solve with a separate dashboard
device), anti-cheat games, MDM-managed work apps, and remote check
deposit at most banks.

---

## Non-goals

- **Replacing your OS.** It layers. Going back is always available.
- **Defeating attestation.** Not attempted. Where a counterparty
  demands a device signal, they win, and the shell says so plainly.
- **Being a browser.** The generic browsing surface exists but carries
  no capabilities and no origin binding.
- **An app store.** The registry binds names to origins. It has no
  power over what code runs — a phone book, not a gatekeeper. Users can
  add entries by hand, run someone else's catalogue, or run none.
- **Protecting you from a compromised OS.** Out of scope at every tier.
