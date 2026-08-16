# The install pack

A phone that is not googlified and not appified, assembled almost entirely from
things that already exist. The only new component is `pane`.

> **Two things here have since been decided differently, and this document is
> kept as written rather than quietly corrected.**
>
> **Passwords.** Layer 2 picks Vaultwarden over "KeePass on Syncthing", and the
> argument is sound: a vault that is *one file* cannot be merged after two
> offline edits. What it does not consider is a vault that is one file *per
> credential*, which makes a conflict a single entry and resolving it a
> comparison of two timestamps — no server, and one sync mechanism for the whole
> phone instead of two. That is `latch/`, and it is what was built. See
> [`VAULT.md`](VAULT.md), whose "Why fork this and not KeePassDX" answers this
> section directly.
>
> **The engine.** Layer 3 assumes one `pane` APK containing every app. A site is
> now its own package — its own uid, data directory and permission ceiling —
> borrowing one shared engine at runtime. See [`probe/`](../../probe/). The
> layers and everything outside them still hold.

The principle: **an app should exist because it must be native, not because a
company wanted an app.** Almost nothing must be native. Banking, mail, shopping,
travel, government — all of it is a website that works, and the app exists to
get an SDK onto your phone.

---

## Layer 0 — the base

**GrapheneOS** on a Pixel. Not required — everything here works on stock
Android — but it is the version where the claims are actually true: no Play
Services unless you ask, hardware attestation, MTE on Pixel 8 and later, and a
vendor whose incentives are not advertising.

**No sandboxed Google Play.** If something genuinely needs it, it goes in a
separate user profile, where it can see nothing.

---

## Layer 1 — what GrapheneOS already gives you

Installed from **Apps** (`app.grapheneos.apps`), their own store. No account, no
Play, signed by the OS vendor.

| | |
| --- | --- |
| **Vanadium** | The browser. Also where `pane` hands off anything outside an app's fence — so the "real browser" in the story is a hardened one |
| **Camera** | Includes a QR scanner, which is the pairing path |
| **PDF Viewer** | pdf.js behind a content provider. This is why `pane` does not need its own viewer: a downloaded statement opens here |
| **Auditor** | Attestation, if you care to check the phone is what it says |

---

## Layer 2 — the sync spine

One always-on box at home. This is the "log in once and away you go" part, and
it is deliberately three small daemons rather than one large platform.

| What | How | Why this one |
| --- | --- | --- |
| Files, photos, notes | **Syncthing** | Peer-to-peer, no account, no cloud. Notes are markdown files; the notes app is a *view* of a folder |
| Passwords, passkeys, TOTP | **Vaultwarden** | One small Rust binary over SQLite. Per-*item* sync, which is the thing a file-sync cannot do for a vault |
| Contacts, calendar | **Radicale** + **DAVx5** on the phone | CardDAV/CalDAV, and DAVx5 publishes into `ContactsContract` so the dialer and Signal see your contacts |

### Why Vaultwarden rather than KeePass

KeePassDX over Syncthing is tempting — one sync mechanism, no server, and the
kdbx format is excellent. It fails on **merge**. A vault is one file, so two
phones that both add a password while offline produce a `.sync-conflict-*` and
no way to reconcile them. You lose credentials, occasionally, and you find out
later.

Vaultwarden syncs per item, so those two additions merge without anyone
noticing. It costs one small server, which you are running anyway for Syncthing.

**And it fills into `pane` with no work from us**: GeckoView implements the
Android autofill framework (`onProvideAutofillVirtualStructure`), so the
Bitwarden client — or KeePassDX, or Proton Pass — fills web forms inside our
apps exactly as it does in any browser. Whatever we build later with Gecko's
`Autocomplete.StorageDelegate` is an improvement on a thing that already works,
not a prerequisite.

**Passkeys**: **Passchain** (`s1m.hwfido2provider`, Apache-2.0) provides FIDO2
without Play Services, via microG's implementation. GeckoView calls the platform
`CredentialManager`, so this is the piece that makes passkeys work at all on a
de-Googled phone.

---

## Layer 3 — pane, and the apps

One APK, many icons. Each app is its own task, its own recents card, its own
jar, fenced to its own domains. Pinned shortcuts put them on whatever launcher
you already use.

**Identity groups (the jar).** Apps sharing a jar share a login:

```
google     Google Account · Gmail · Maps · Drive · Calendar
work       whatever your employer's SSO covers
personal   Fastmail · Standard Notes · anything with its own login
(each bank gets its own, sharing with nothing)
```

**Tier 0 — the providers you live in.** These are the strongest case for a
private jar: you are logged in, they follow you across the web, and they tolerate
unusual clients because their business needs reach.

| | |
| --- | --- |
| Mail | Gmail, or Fastmail / Proton if leaving Google is the point |
| Calendar, Drive, Photos | the web versions — all fine, all fenced |
| Maps | web Maps for search and transit; **Organic Maps** stays native for offline |
| Shopping, travel, government, utilities | one icon each, fenced, no SDK on your phone |

**Banks.** Each in its own jar, sharing with nothing:
Chase, Schwab, Fidelity, Morgan Stanley, First Tech. Verified: Chase renders and
offers its login form in GeckoView with no bot challenge — the same site refuses
an embedded WebView.

---

## Layer 4 — the few that stay native

Honesty about the limits. These are native because they must be:

- **Signal** — code delivery and registration anchoring; a web client would
  break the guarantee.
- **Dialer and SMS** — until someone does the `ROLE_SMS` work, and MMS is where
  SMS apps die.
- **Organic Maps** — offline, and no web map is.
- **Camera**, **Syncthing**, **DAVx5**, **Bitwarden**, **Passchain**.

That is roughly eight native apps for a working phone. The rest is icons.

---

## What is deliberately absent

No Play Services. No Play Store. No advertising ID. No app that exists solely to
hold an SDK. No cloud account you did not choose.

---

## Setup, from a factory-reset phone

The claim is one QR and a few minutes:

1. Flash GrapheneOS, skip everything.
2. Install Syncthing, DAVx5, Bitwarden, Passchain, pane, Signal.
3. **Scan one QR from your home box**: Syncthing device id and folders,
   Vaultwarden URL, CalDAV/CardDAV URL.
4. Unlock the vault. Contacts, calendar and the app set arrive; files stream in
   behind.
5. Every bank and provider is one tap and an autofill away, because the
   credentials came with the vault.

Ordering matters and is not arbitrary: **vault first**. The first thing anyone
does on a new phone is open something that needs a password, and if the vault is
still syncing they are typing it by hand and the magic is gone.

---

## What we actually have to build

Almost nothing, which is the point:

- **pane** — exists, three apps working.
- **The catalogue** — `Apps.java` becomes a document you can add to, plus an
  installer that discovers an app's real origin set by watching one login.
- **The pairing QR** — one screen that carries three URLs and a key.
- **A notes view** over the Syncthing folder, eventually. Obsidian already works.

## Honest gaps

- **SMS 2FA** still means reading a code out of Messages. Fine, but not seamless.
- **Push notifications** for web apps need an endpoint; unsolved here.
- **Passkeys** are architecturally viable but the ceremony is untested, and
  `pane` would need adding to Passchain's privileged-caller allowlist.
- **A bank that hard-requires its app** — check deposit by camera works on the
  web for most, but not all. That one stays native or you use a branch.
