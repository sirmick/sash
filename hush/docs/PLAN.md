# hush — 0.4, Firefox all the way down

**We will drive you as far as we possibly can on good use of Firefox.**

That is the tenet, and it is a constraint rather than a slogan: when something
could be native code or could be a well-used engine capability, it is the engine
capability. Native code exists only where the engine structurally cannot reach —
the trust chrome, composition, device roles, and the vault's seal.

---

## Table of contents

- [What changed](#what-changed)
- [There is no fork](#there-is-no-fork)
- [The architecture](#the-architecture)
- [The vault is first-class](#the-vault-is-first-class)
- [What we keep, what we delete](#what-we-keep-what-we-delete)
- [Phases](#phases)
- [Risks worth naming](#risks-worth-naming)

---

## What changed

Two measurements, both from a single afternoon, and both reversing a decision
that had stood since the design was written.

**WebView has a permanent ceiling.** Every Android WebView embedder sends
`X-Requested-With: <package>` to every site. The API to suppress it is
`@Deprecated` in Chromium's own source — *"disabled since the XRW origin trial
ended"* — so it is not missing from our WebView, it is missing from all of them,
for good. Google refuses a sign-in from an app it does not recognise, and no
amount of care on our side changes what we announce. See `ENGINE.md`.

**GeckoView clears it.** Per-session `contextId` isolates storage completely —
a control run proved cookies cross when the ids match and vanish when they
differ, including the CDN's own. And it sends no such header: it presents as
`Firefox/153.0` on Android, which is a real browser and a large herd. Google
serves it the sign-in form and offers *"Use a Private Window"* — recognised, not
tolerated.

**And wash was overkill.** 19.5 MB of `libwash.so`, a foreground service with a
permanent notification, exec-from-`nativeLibraryDir`, symlink multicall
dispatch, a MessagePort-to-unix-socket bridge and a hand-rolled wire codec — to
deliver a JSON document with get and set. The only other wash app we shipped,
`about`, was never referenced. Most of the bugs of that afternoon lived in that
layer rather than in the product: handlers that dropped shell messages silently,
a state service structurally unable to serve a shell, a declaration racing its
own handler registration, an asset cache serving half a build.

wash remains what it is — a desktop environment, and a good one. hush borrows
its **ideas**: the registry's shape, the trust roles, the discipline about
schema versioning. Not its runtime.

---

## There is no fork

"A minor modification to Firefox" is the one thing this plan refuses, and the
refusal is load-bearing: there is no such thing as a small fork you maintain
forever. Chromium ships every four weeks and Gecko is not far off; a patch set
against either is a team, permanently, and `ENGINE.md` already files the
Chromium version of that idea as not serious.

It is also unnecessary. Everything the pivot needs is a published GeckoView API:

| Need | API |
| --- | --- |
| Profile tiering | `GeckoSessionSettings.contextId` — the mechanism behind Container Tabs |
| Password manager | `Autocomplete.StorageDelegate` — the embedder *is* the store |
| Page-side privileged code | `WebExtensionController.ensureBuiltIn` |
| Storage lifecycle | `StorageController.clearDataForSessionContext` |

**Ship GeckoView as published.** If we ever need something it does not expose,
the answer is an upstream patch, not a private tree.

---

## The architecture

```
Android app (one uid)
  ├── GeckoRuntime                  one, long-lived
  │     ├── GeckoSession ctx=id-google      identity root
  │     ├── GeckoSession ctx=p-gmail        leaf, authenticates through the root
  │     ├── GeckoSession ctx=p-chase        leaf, isolated
  │     └── GeckoSession ctx=p-browse       no allowlist, throwaway
  ├── built-in WebExtension         page-side glue, one place, reviewable
  └── native Kotlin
        ├── trust chrome            drawn where content cannot reach
        ├── composition             surfaces positioned, insets, IME
        ├── device roles            home + browser
        ├── registry                the document, and its schema rules
        └── vault                   sealed to the keystore
```

**Two tiers, no more.** Identity roots hold a federated login; leaves reference
one and never share its jar. `contextId` is flat, so the tiering is ours —
exactly as it is today, and it works.

**The home app is a Gecko surface**, painted to look and behave native. One
engine, one design language, and the launcher becomes a web app we can iterate
on quickly. It is inset above the surfaces rather than covered by them, which
is the composition model we already built for the browser bar.

*Gated on one measurement.* A home screen may not be slow, and the current bar
is **381 ms** from a killed process to a usable grid — helped considerably by
the system WebView already being warm. A bundled `GeckoRuntime` initialises
itself. If a cold Home press cannot get under roughly half a second, the
launcher is native Kotlin instead and the tenet takes its one exception. Measure
before committing.

**Apps are Firefox, wearing a name.** A registry entry is a name, an icon, an
origin allowlist, a context id and a home URL. Opening it is a full-screen
session under a native trust strip. There is no app bundle, no packaging, no
store — installing is typing a URL once, and install-by-navigation learns the
rest of the origin set by watching one login.

---

## The vault is first-class

This is the part the pivot unlocks, and it is better than the plan it replaces.

`STATE.md` had Vaultwarden bundled: a server on loopback, which on Android is
reachable by every app on the device — a problem that document itself flagged as
unresolved and on the critical path. That whole difficulty disappears.

GeckoView lets the embedder *be* the login store:

```java
onLoginFetch(origin) → LoginEntry[]
onLoginSave(LoginEntry)          // origin, formActionOrigin, httpRealm, username, password
onCreditCardFetch() / onAddressFetch()
```

Gecko does form detection, field matching and filling — the part that is
genuinely hard and that every autofill hack gets wrong. We own storage, sealed
to the Android keystore, and we answer per origin. No server, no loopback port,
no injection, and the vault never leaves the process.

It also means the **origin model has one owner**. `SHELL.md` warns that if the
registry and the keychain disagree about origins, autofill will offer
credentials on a surface the registry considers foreign and origin locking
becomes decorative. Here they are the same table.

---

## What we keep, what we delete

**Keeps — the value was never in the plumbing.** Origin locking and
path-scoped matching; the trust strip; link routing and ownership; both device
roles; downloads with per-profile cookies; install-by-navigation and the
ejection card; the identity-root flow; the switcher; and the registry's schema
discipline — versioning, unknown-field preservation, id-keying, device-class
layout, spans.

**Deletes.** `libwash.so` and `libhush.so`; the router and its foreground
service and permanent notification; exec-from-`nativeLibraryDir` and the symlink
dance; `WirePipe` and the wire codec; the whole `BOUNDARY.md` apparatus, which
existed to keep wash clean and is moot once hush does not link it. `Presentation`
mostly deletes itself: Firefox sends no client hints, so there is nothing to
keep consistent with a UA.

---

## Phases

| | | Exit |
| --- | --- | --- |
| **A** | Gecko cold-start measurement | A number, and the home-app decision made on it |
| **B** | Registry to Kotlin, wash removed | Grid renders from a Kotlin-owned document; APK sheds ~25 MB |
| **C** | Surfaces on GeckoView | Origin lock, trust strip, routing and roles working against Gecko |
| **D** | Google signs in, for real | The boss fight, with a real account |
| **E** | Vault via `StorageDelegate` | A password saved on one site, offered on that site and no other |
| **F** | Sync | Registry and vault agree across two devices after a QR scan |

A and B are independent of each other and both are small. D is the one that
validates the pivot, and it needs a human with an account rather than more code.

---

## Risks worth naming

**Cold start** is the one that could force an exception to the tenet. Phase A
exists to find out early rather than after the launcher is written.

**APK size.** The probe built at 514 MB, but that is all ABIs and unstripped
debug; a single-ABI release is ~100 MB. Still a step change from a shell that
shipped no engine, and it is the price of not being gated by Google.

**Mozilla's cadence** becomes ours, and Gecko's security updates are now our
responsibility rather than the OS vendor's. On GrapheneOS this trades Vanadium's
hardening for Gecko's.

**Firefox's fingerprint** is a smaller herd than Chrome's — but a real herd,
where an embedded WebView announcing a package name is a herd of one.

**Kotlin's K2 compiler crashes** on GeckoView's annotated API. The probe is
Java. Establish early whether this is avoidable or whether the Gecko-facing
layer stays Java.
