# sash

A phone that is not googlified and not appified, assembled almost entirely from
things that already exist.

The principle: **an app should exist because it must be native, not because a
company wanted an app.** Almost nothing must be native. Banking, mail, shopping,
travel, government — all of it is a website that works, and the app exists to
get an SDK onto your phone.

| | |
| --- | --- |
| [`pane/`](pane/) | Web apps that behave like apps. One Activity, one jar, one fence — GeckoView, origin-locked, with its own recents card and home icon. Now also **the engine**: the one copy of GeckoView on the disk. |
| [`probe/`](probe/) | How a site app gets to be 15 KB. A tiny APK borrows pane's engine and runs it under its own uid, with its own permissions and its own profile — and `manager` installs them. |
| [`catalogue/`](catalogue/) | One JSON file per identity. A label, some hostnames, an icon, a permission list. No code. |
| [`latch/`](latch/) | The vault. A fork of [Passchain](https://codeberg.org/s1m/hw-fido2-provider) that keeps its hardware FIDO2 and adds passwords, stored one encrypted file per credential in a folder Syncthing carries. |
| [`scripts/`](scripts/) | `mint.py` turns the catalogue into apps, `provision.sh` puts the whole system on a device in one command, `test.sh` runs what can be checked without one. |
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
- **A 15 KB app renders a page using another package's engine**, in its own
  process, under its own uid. Two apps from one source tree, one engine, and
  different permission ceilings — `pm grant CAMERA` on the one that never asked
  for it reports success and does nothing. See [`probe/`](probe/).
- **A whole device provisions in one command.** `scripts/provision.sh`, from
  built APKs to latch wired in as the autofill and credential provider.

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
├── catalogue/     one JSON file per identity — the input to everything
├── scripts/       mint.py, provision.sh, test.sh
├── pane/          the app shell, and the engine every site app borrows
├── probe/         the shared-engine loader, and the installer
├── latch/         the vault — a fork of Passchain
└── hush/docs/     the archived predecessor's design record
```

Nothing links anything. pane, probe and latch are deliberately independent
projects: a site app finds the engine at runtime by package name, and meets the
vault only through Android's autofill framework. That is what makes each of them
replaceable on its own.

**The catalogue is the source of truth for every site.** `scripts/mint.py`
generates the loader's build flavours *and* the manager's list from it, and the
generated output is checked in. Change a catalogue entry and re-run it:

```
python3 scripts/mint.py            regenerate
python3 scripts/mint.py --check    verify what is checked in is current
```

## Tests

```
./scripts/test.sh
```

Everything checkable without a device: the catalogue and its minting, the origin
fence, and the vault's storage, crypto and conflict resolution. Runs offline in
about fifteen seconds.

What it does *not* cover is most of what this project claims. Isolation is
enforced by the kernel and measured on a phone; `scripts/inventory.sh` is how
that gets checked, and `probe/README.md` records the measurements.

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
| [`probe/README.md`](probe/README.md) | How a 15 KB app borrows a browser engine: the four walls in the order they appeared, the permission and profile measurements, and why not to just use the system WebView. Also the clearest lesson here — seven hypotheses eliminated about an app when the variable was the page. |
| [`catalogue/README.md`](catalogue/README.md) | What an entry is, why an entry is a package, and why origins are discovered rather than guessed. |
| [`pane/docs/VAULT.md`](pane/docs/VAULT.md) | The vault: storage, crypto, conflict resolution, sync, and the box at home. Includes the two Android seccomp walls and why they are x86_64 artifacts. |
| [`pane/docs/PACK.md`](pane/docs/PACK.md) | The install pack — what a whole phone assembled this way looks like, layer by layer. Predates latch; see the note at its head. |
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
