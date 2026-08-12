# latch — the vault

*(working name; the product name is yours to pick)*

A fork of **Passchain** (`s1m/hw-fido2-provider`, Apache-2.0) that keeps its
hardware FIDO2 and adds passwords, stored as one encrypted file per credential
in a folder **Syncthing** carries.

Hardware keys where they matter, synced files for the long tail, and one sync
mechanism for the whole phone.

---

## Why fork this and not KeePassDX

Passchain is **1,831 lines of Kotlin in 15 files**, and `Store.kt` is 26 of them
— it holds three SharedPreferences keys naming which authenticator you picked.
It has no credential storage at all, because its keys live in hardware. So there
is nothing to work around, only something to add — and the part nobody wants to
write, the `CredentialProviderService` contract and its ceremony activities,
is already done and working.

KeePassDX would have meant a merge engine, an auto-merge daemon, and
snapshot-before-merge discipline, because a kdbx is one file and two devices
editing it produce something only a merge algorithm can reconcile. **One file per
credential deletes that entire problem**: a conflict is one credential, edited
in two places, and resolving it is comparing two timestamps.

Apache-2.0 rather than GPL-3 is a smaller reason, but a real one.

---

## Storage

```
vault/                          ← a Syncthing folder
  meta.json                     kdf params, salt, schema version — not secret
  entries/
    <uuid>.bin                  one credential, AEAD-encrypted
```

**Filenames are random UUIDs, never derived from the site.** `pass` is fairly
criticised for the opposite: deterministic names leak which sites you have
accounts on, and let anyone holding the folder test guesses. Random names cost
us a full read on unlock — a few hundred tiny files — and leak nothing. Doing it
from the first commit is free; retrofitting it means rewriting every filename on
every device at once.

**Each entry decrypts to:**

```json
{ "id": "…", "origin": "chase.com", "username": "…", "password": "…",
  "notes": "…", "created": …, "modified": …, "deleted": false }
```

**Deletion is a tombstone, not a delete.** Syncthing propagates real deletions
fine, but a delete on one device racing an edit on another can resurrect the
entry — and a resurrected password is worse than a stale one. Soft-delete with a
timestamp, garbage-collect after 30 days.

`origin` is the join to pane's registry: the same origin table decides which app
may go where *and* which credential is offered. `SHELL.md`'s warning applies —
if the vault and the app model disagree about origins, autofill offers
credentials on a surface the fence considers foreign, and the fence becomes
decorative.

---

## Crypto

The one part where we are genuinely writing security code, so it stays small and
uses libraries designed to prevent misuse.

| | |
| --- | --- |
| Key derivation | **Argon2id** from the passphrase + salt in `meta.json` (Bouncy Castle 1.83) |
| Per-entry | **AEAD via Tink** (`tink-android` 1.23.0), fresh nonce per write |
| Daily unlock | derived key wrapped in the **Android Keystore**, released by biometric |

Bouncy Castle rather than the native `argon2kt`, which this document originally
specified: BC is **already on the classpath** (transitively, via
`indispensable-cosef`) and is pure Java, so the vault's tests run on the JVM in
a fifth of a second instead of needing a device. Adding a JNI dependency to
speed up an operation that happens **once per device** — every later unlock
comes from the Keystore — buys nothing and costs the fast test loop.

**KDF parameters live in `meta.json`, not in the code.** Hard-coding them means
the day we raise the cost, every existing vault becomes unopenable.

`meta.json` also holds a **sealed check value**. Without it an empty vault would
accept any passphrase, because there would be nothing to fail to decrypt.

**The entry id is the AEAD's associated data**, so a ciphertext copied over a
different filename fails to authenticate rather than silently impersonating that
credential.

Two unlock paths on purpose: the **passphrase** is what makes a new device work
with nothing but the sync folder, and the **Keystore + biometric** is what makes
daily use bearable. Type the passphrase once per device; touch the sensor after
that.

The passphrase is the "log into your home sync" moment — one secret brings the
vault, and Syncthing brings everything else.

---

## Conflicts

**No integration with Syncthing.** Conflict handling is entirely filesystem
level: `moveForConflict()` renames the losing copy to
`<uuid>.sync-conflict-<YYYYMMDD>-<HHMMSS>-<device>.bin` and leaves it in the
folder. We scan `entries/` on unlock; anything matching `.sync-conflict-` is
work to do. No API, no hook, no plugin.

Conflict copies are ordinary files and **propagate to every device** — the
upstream docs are explicit that this is deliberate, *"it's just as much of a
conflict everywhere else."* So resolution happens wherever the user next
unlocks, and the result syncs out from there.

**Syncthing already detects genuine concurrency, so we do not.** A conflict copy
is made only when `file.InConflictWith(curFile)` — which consults **version
vectors** via `Concurrent()`. Causally ordered edits (phone edits, laptop pulls
that, laptop edits) simply overwrite, correctly and silently. The *existence* of
a conflict file is therefore exactly the "these were truly concurrent" signal,
derived causally rather than from clocks. Version vectors beat anything we could
stamp ourselves, so the entry's own clock is left with only two jobs: picking a
winner and ordering history.

### Resolving

1. Decrypt the conflict file and the live `<uuid>.bin`.
2. Higher `modified` becomes current.
3. Union both `history` arrays plus the loser's current password; dedup, sort.
4. **Write only if the merged _plaintext_ differs from the live entry's.**
5. Delete the conflict file; report the credential by name.

Step 4 is not an optimisation. AEAD uses a random nonce, so re-encrypting
identical plaintext yields different ciphertext — two devices both resolving and
both rewriting would manufacture a fresh conflict out of agreement. Comparing
plaintext makes resolution idempotent and the loop terminates.

That matters because of a rule worth knowing:

```go
if isConflict(name) {
    f.sl.Info("Conflict on existing conflict copy; not copying again...")
    f.mtimefs.Remove(name)
```

**Syncthing will not make a conflict of a conflict — it deletes the old copy.**
An unresolved conflict file is not safe indefinitely. Resolve on unlock, never
lazily.

### Two settings we must get right

**`maxConflicts` must never be 0.** Zero discards the losing version outright,
which is precisely the credential loss this design exists to prevent. The default
of 10 is fine, but we set it explicitly over the REST API since we drive the
config anyway.

**Never put `sync-conflict` in `.stignore`.** A common tidiness reflex, and here
it silently throws away passwords.

Delete-versus-edit races never arise, because tombstones mean we never issue a
real delete for Syncthing to race against.

---

## Serving credentials

Three surfaces, and pane needs the second:

| | |
| --- | --- |
| `CredentialProviderService` — passkeys | **already works**, untouched |
| `CredentialProviderService` — passwords | new: `BeginCreatePasswordCredentialRequest`, `BeginGetPasswordOption` |
| `AutofillService` | new, and **this is the one pane uses** — GeckoView implements the Android autofill framework, so filling into a pane app is the same path as filling into any browser |

Hardware FIDO2 stays exactly as it is. Nothing about the passkey path changes.

---

## Build phases

| | | Exit |
| --- | --- | --- |
| **1** | Fork, build, install unchanged | The APK runs and still does passkeys — toolchain proven before anything is changed |
| **2** | Storage: model, crypto, files | Unit tests: round-trip, wrong passphrase fails, tamper detected, tombstone survives |
| **3** | Conflict resolution | A fabricated `.sync-conflict-*` resolves to the newer entry with the older kept |
| **4** | Password paths in the provider | Save and retrieve a password through Credential Manager |
| **5** | `AutofillService` | **A password fills into a pane app** — the end-to-end proof |
| **6** | Unlock UX | Passphrase once, biometric after, lock on timeout |
| **7** | Syncthing on the phone | Two devices, one folder: a credential saved on one is offered on the other |

### Phase 7 as measured

Syncthing is **bundled and driven over its REST API**, never shown. It ships as
`libsyncthing.so` and is executed from `nativeLibraryDir`, because since API 29
an app may not exec anything from its own data directory.

**The API listens on a unix socket in `filesDir`, not `127.0.0.1:8384`.**
Verified on device: `srwx------ u0_a126 syncthing.sock`, and nothing bound on
8384. This matters because Android has no per-uid loopback isolation — a server
on loopback is reachable by every app installed. Syncthing's own Android client
binds TCP, which would put an API that can add sync folders and read the device
id behind no boundary at all.

**Bundling upstream Syncthing is not the drop-in this document assumed.**
Android's seccomp filter rejects syscalls it uses, and the failures are precise:

| | |
| --- | --- |
| **2.x** | `SIGSYS` on `lstat` (6), from `modernc.org/sqlite` through `modernc.org/libc`. Syncthing 2 replaced LevelDB with SQLite machine-translated into Go, which drags in musl machine-translated into Go — and musl uses `lstat` where bionic uses `fstatat`. |
| **1.30** | Starts, generates its identity, binds the socket — then `SIGSYS` on `epoll_wait` (232), from `golang.org/x/sys/unix.EpollWait`. bionic uses `epoll_pwait`. |

**Both faults are x86_64 only, and for the same reason.** Android's seccomp
allowlist mirrors the syscalls bionic actually issues. These libraries bypass
bionic and call the kernel directly, so on amd64 they reach for the legacy
syscalls and are killed. arm64 Linux has no `stat`, `lstat` or `epoll_wait` at
all — its syscall table was defined fresh without them — so the same source is
*forced* onto the modern equivalents, which are exactly the permitted ones:

```
modernc/libc, one function, two architectures:
  amd64:  X__syscall2(SYS_stat, ...)          legacy, blocked
  arm64:  X__syscall4(SYS_newfstatat, ...)    modern, allowed
```

So **2.x is not necessarily blocked at all** — it is blocked on the emulator we
happen to have. The Makefile pins v1.30.0, the last LevelDB release, and that
pin should be revisited the moment there is an arm64 device to test on. It costs
something: 1.x is being retired ("Version 1.x will soon be replaced by version
2.x"), and v2-to-v2 multiple connections do not apply to a v1 peer. It survives
the wait because 2.x "remains protocol compatible with Syncthing 1", so a phone
on 1.30 still syncs with a 2.x home server.

`net.Interfaces()` is separately restricted on Android 11+, but fails as an
error rather than a fault.

This still corrects the claim that a CGO-free Go binary runs on Android
unmodified. The wash router did, because it never made these calls; a network
daemon does, which is why `syncthing-android` is a real project rather than a
wrapper. But the correction is narrower than it first looked: the constraint is
*legacy syscalls on x86_64*, not Go binaries on Android.

Phase 2 and 3 are where the consequences live and are worth over-testing. Phases
4–6 are mechanical against an API that already works in the code we forked.

---

## Risks

**We are writing crypto.** Small, library-backed, and conventional — but ours.
It deserves a second pair of eyes before anyone trusts real credentials to it,
and until then it holds test data only.

**The passphrase is the whole vault.** Losing it loses everything, so a printed
recovery code is not optional; `STATE.md` was right about that and it applies
unchanged.

**Syncthing's Android app is archived upstream** (Dec 2024). The maintained
continuation is the community fork on F-Droid; pin its exact coordinates and
watch it, because the sync spine resting on an abandoned app would be a poor
surprise.

**Forking is a maintenance commitment**, even at 1,831 lines. Passchain tracks
microG's FIDO2 and Android's credential APIs, both of which move. Rebasing is
the cost of the fork, and it is small only while the fork stays small.
