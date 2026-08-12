# sash

A phone that is not googlified and not appified, assembled almost entirely from
things that already exist.

The principle: **an app should exist because it must be native, not because a
company wanted an app.** Almost nothing must be native. Banking, mail, shopping,
travel, government — all of it is a website that works, and the app exists to
get an SDK onto your phone.

| | |
| --- | --- |
| [`pane/`](pane/) | Web apps that behave like apps. One Activity, one jar, one fence — GeckoView, origin-locked, with its own recents card and home icon. |
| [`latch/`](latch/) | The vault. A fork of [Passchain](https://codeberg.org/s1m/hw-fido2-provider) that keeps its hardware FIDO2 and adds passwords, stored one encrypted file per credential in a folder Syncthing carries. |
| [`hush/docs/`](hush/docs/) | The archived predecessor. Kept because it is the record of *why* — see [`SALVAGE.md`](hush/docs/SALVAGE.md). |

## What works

Verified on a device, not asserted:

- **A password fills into a sandboxed web app.** Real chase.com inside pane's
  origin fence, matched by web domain — never by package name, because every
  pane app reports `com.pane`.
- **Two devices, one vault.** Including a full restore: wipe the phone to
  nothing, pair with the box at home, and the passphrase alone brings the
  credentials back.
- **The box cannot read what it stores.** `strings` over the synced entries
  finds no site, no username, no password.
- **Passkeys are untouched.** The fork is additive; upstream's hardware FIDO2
  path is exactly as it was.

## What does not, yet

- **No export.** A vault you cannot leave is a vault you cannot trust. This is
  the next thing.
- **No recovery code.** Losing the passphrase loses everything, everywhere, at
  once.
- Passwords through `CredentialManager` are built and registered but unexercised
  — they need a native caller, not a web form.
- Everything has been tested on **x86_64** only. Two of the constraints
  documented here are legacy-syscall artifacts of that architecture and are
  expected to vanish on arm64. Nothing confirms that yet.

## Why it is arranged this way

The design notes are worth more than the code, and most of them exist because
something was measured and contradicted an assumption:

- [`pane/docs/VAULT.md`](pane/docs/VAULT.md) — the vault: storage, crypto,
  conflicts, sync, and the box at home.
- [`pane/docs/PACK.md`](pane/docs/PACK.md) — the install pack: what a whole
  phone looks like built this way.
- [`hush/docs/SALVAGE.md`](hush/docs/SALVAGE.md) — what survived the previous
  attempt and what to discard, written to be read before writing anything.
- [`hush/docs/ENGINE.md`](hush/docs/ENGINE.md) — why an engine is embedded
  rather than a browser driven, and the measurement that ended the WebView
  approach: Android's WebView announces its package name to every site,
  permanently, and Google refuses sign-in because of it.

## Licence

`latch/` is a derivative of Passchain and is **Apache-2.0**; its `LICENSE` is
retained unchanged and that is not optional.

The rest of this repository has no licence yet, which means default copyright.
Pick one before expecting anyone else to use it.
