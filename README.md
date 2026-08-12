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

## Layout

```
sash/
├── pane/          the app shell — web apps that behave like apps
├── latch/         the vault — a fork of Passchain
└── hush/docs/     the archived predecessor's design record
```

Two APKs and one archive. pane and latch are deliberately independent — neither
links the other. They meet only through Android's autofill framework, which is
what makes either one replaceable.

### `pane/`

```
Makefile              apk / install / run / open APP=<id> / logs / shot / cf-start
android/…/com/pane/
  App.java            what an app *is*: id, home, contextId, origins
  Apps.java           the three hardcoded apps, and sibling lookup
  Origins.java        the fence: allowed(), upgrade(), hostOf()
  Sessions.java       one GeckoSession per app, outliving activities
  AppActivity.java    one app, one task, one recents card, the origin lock
  LauncherActivity.java
scripts/install-cuttlefish.sh
```

Six Java files. The two ideas it exists to keep apart — `contextId` is **who you
are**, `origins` is **where an icon may go** — are one field each.

### `latch/`

A vendored fork, so what matters is which files are ours.

**Entirely ours:** everything under `…/hwfido2provider/vault/` — the store
(`Vault`, `VaultManager`, `VaultStore`), the model and merge (`Entry`, `Hlc`,
`Conflicts`), crypto (`Crypto`, `Keystore`), serving credentials (`FormFields`,
`PasswordEntries`), sync (`SyncEngine`, `SyncApi`, `SyncSetup`, `Pairing`) and
the tests. Plus four new top-level files: `VaultActivity`, `PasswordActivity`,
`LatchAutofillService`, `SyncService`.

**Upstream files touched at all — nine, most of them a few lines:**
`ProviderService.kt` (3 insertions; the passkey path is untouched),
`ui/MainUi.kt`, `ui/MainViewModel.kt`, `AndroidManifest.xml`,
`res/xml/credentialprovider.xml`, and four build files.

That is the design rather than an accident: **every upstream file left alone is
a file `git subtree pull` cannot conflict on.** Same reason the package
namespace is still `s1m.hwfido2provider` — renaming it would conflict on every
file, forever, for cosmetics.

```
git subtree pull --prefix=latch --squash \
  https://codeberg.org/s1m/hw-fido2-provider.git main
```

### Known warts

- **`pane/docs/VAULT.md` documents `latch/`.** Historical: pane existed first.
- **No root Gradle build.** Two independent projects, each built from its own
  directory.
- **`latch/.woodpecker/main.yml`** is upstream's Codeberg CI. Inert here, and
  not ours.

## The documents

Design notes first, because they are worth more than the code and most exist
because something was measured and contradicted an assumption.

| | |
| --- | --- |
| [`pane/docs/VAULT.md`](pane/docs/VAULT.md) | The vault: storage, crypto, conflict resolution, sync, and the box at home. Includes the two Android seccomp walls and why they are x86_64 artifacts. |
| [`pane/docs/PACK.md`](pane/docs/PACK.md) | The install pack — what a whole phone assembled this way looks like, layer by layer. |
| [`pane/README.md`](pane/README.md) | The jar and the fence, and why they are separate. |
| [`latch/README.md`](latch/README.md) | Upstream Passchain's own README. |

The archive, in its intended reading order — see
[`hush/docs/README.md`](hush/docs/README.md):

| | |
| --- | --- |
| [`SALVAGE.md`](hush/docs/SALVAGE.md) | What survived the previous attempt and what to discard. Written to be read *before* writing anything. |
| [`ENGINE.md`](hush/docs/ENGINE.md) | Why an engine is embedded rather than a browser driven — and the measurement that ended the WebView approach. |
| [`PRODUCT.md`](hush/docs/PRODUCT.md) | The strategy and the claim ladder: what it protects against at each tier, and what it does not. |
| [`DECISIONS.md`](hush/docs/DECISIONS.md) | Each entry records what would reverse it. Several have since *been* reversed, which is the format working. |
| [`STATE.md`](hush/docs/STATE.md) | Sync design: three planes, scope, merge discipline, the one-minute onboarding bar. Intact and still largely unbuilt. |
| [`ANDROID.md`](hush/docs/ANDROID.md) | The Android host as actually built: verified constraints, fingerprint measurements, the Cuttlefish environment. |
| [`SHELL.md`](hush/docs/SHELL.md) | Trust roles, surfaces, identity roots, origin locking, composition. |
| [`PROVIDERS.md`](hush/docs/PROVIDERS.md) · [`CHROME.md`](hush/docs/CHROME.md) · [`PLAN.md`](hush/docs/PLAN.md) · [`ROADMAP.md`](hush/docs/ROADMAP.md) · [`BOUNDARY.md`](hush/docs/BOUNDARY.md) · [`WASH_CHANGES.md`](hush/docs/WASH_CHANGES.md) | Provider layer, the 0.3 and 0.4 plans, status, and how hush depended on wash without becoming part of it. |

Where a document still says something building has not tested, treat it as
design intent rather than fact — the archive labels these.

## Licence

`latch/` is a derivative of Passchain and is **Apache-2.0**; its `LICENSE` is
retained unchanged and that is not optional.

The rest of this repository has no licence yet, which means default copyright.
Pick one before expecting anyone else to use it.
