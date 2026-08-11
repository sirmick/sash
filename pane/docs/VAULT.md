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
| Key derivation | **Argon2id** from the passphrase + salt in `meta.json` (`argon2kt` 1.6.0) |
| Per-entry | **AEAD via Tink** (`tink-android` 1.23.0), fresh nonce per write |
| Daily unlock | derived key wrapped in the **Android Keystore**, released by biometric |

Two unlock paths on purpose: the **passphrase** is what makes a new device work
with nothing but the sync folder, and the **Keystore + biometric** is what makes
daily use bearable. Type the passphrase once per device; touch the sensor after
that.

The passphrase is the "log into your home sync" moment — one secret brings the
vault, and Syncthing brings everything else.

---

## Conflicts

Syncthing writes `<uuid>.sync-conflict-<date>-<device>.bin`, which means one
credential was edited in two places.

1. Decrypt both, compare `modified`.
2. Keep the newer as the entry.
3. Keep the older's password in the entry's history, never discard it silently.
4. Delete the conflict file, and tell the user which credential it was.

No merge algorithm, because there is nothing to merge — one file, one
credential, two versions.

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
