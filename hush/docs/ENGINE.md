# hush — the engine question

Everything hush does to a web page it does as an **embedder**. This document is
the honest account of what that costs, what it buys, and what it would take to
use a real browser instead.

Written after building enough to have evidence rather than opinions: every
kludge below was hit, and most were hit the hard way.

---

## Table of contents

- [The one-sentence version](#the-one-sentence-version)
- [Kludges we carry](#kludges-we-carry)
- [What being the embedder buys](#what-being-the-embedder-buys)
- [Using a real browser](#using-a-real-browser)
- [The question that decides it](#the-question-that-decides-it)

---

## The one-sentence version

**Profile control is a property of being the embedder**, and every other path
trades it away — so the choice is not "WebView or a browser", it is "which
engine do we embed".

---

## Kludges we carry

Things a browser does for its users that hush must build, work around, or do
without. Ordered by how much they cost.

### Browser features we are reimplementing

| | Status |
| --- | --- |
| **Downloads** | Built. WebView hands over a URL and nothing else: no file, no notification, no error. We wrote cookie lookup against the right profile, `Content-Disposition` parsing, filename sanitising, progress, storage and FileProvider sharing. |
| **PDF viewing** | Not built. WebView renders no PDF; the plan is to vendor pdf.js — a viewer every browser already ships. |
| **Error pages** | Not built. A failed load is a blank screen. We hit this repeatedly and mistook it for our own bug more than once. |
| **Navigation UI** | Not built. No back/forward/reload/stop, no find-in-page, no zoom, no reader. |
| **Certificate warnings** | Absent. `onReceivedSslError` defaults to cancel, which is right, and shows the user nothing, which is not. |
| **`target=_blank`** | Kludged. `onCreateWindow` does not tell us the destination URL — the browser would just open a tab. |
| **Renderer death** | Handled, and unhandled it **crashes the whole app**. A browser shows "Aw, snap" for one tab. |
| **Devtools / console** | Wired by hand. Console messages are forwarded to logcat because a page that stops working is otherwise invisible on a device. |

### Fingerprint and identity

| | |
| --- | --- |
| **`X-Requested-With`** | Sent to every site, carrying our package name — the least ambiguous "not a browser" signal there is. The suppression API exists in androidx and is **absent on AOSP WebView 145**. |
| **Client hints** | Hand-forged. A browser's UA and `Sec-CH-UA` agree by construction; ours agree because `Presentation` builds them together, and will drift the moment someone edits one. |
| **`pointer` / `hover` / `maxTouchPoints`** | Not overridable at all. A CDP-driven browser can emulate them; this is the hard ceiling on the fictional-device story, and why only the Linux-tablet variant is available. |
| **`devicePixelRatio`** | Not overridable. |

### Engine mechanics

| | |
| --- | --- |
| **Profile deletion** | Not symmetric with creation. `deleteProfile` throws once a profile has been touched, so profiles are cheap to make and effectively impossible to reclaim. |
| **One data directory per process** | Any multi-process design needs `setDataDirectorySuffix` per process, or WebView throws. |
| **Asset caching** | We had to force `LOAD_NO_CACHE`, because the WebView served a stale copy of our own chrome and an old build ran against new Kotlin. |
| **UI-thread APIs** | `ProfileStore` throws off the UI thread — and the throw is catchable, so a download went out unauthenticated instead of failing. |
| **No extensions** | No content blocking, no user scripts. (Vanadium does not have these either.) |
| **Per-origin network policy** | `shouldInterceptRequest` is a policy layer with holes; real enforcement would be `VpnService`, which filters per-uid and so cannot separate one surface from another. |

---

## What being the embedder buys

Every one of these is load-bearing, and the first is the product.

**Per-surface profiles.** `MULTI_PROFILE` is Chrome's own profile mechanism
exposed to embedders — the API Custom Tabs deliberately withholds. Verified to
isolate in the engine, not merely at the API. No delegated browser offers this
at any price, and without it hush is a bookmark folder.

**Composition.** A surface is a `View` in our hierarchy. We position it, draw
the trust chrome in a layer above it that content cannot reach, and touch
routing falls out of the view tree for free.

**The capability port.** A `MessagePort` injected per surface, scoped to that
surface's origins, with no socket anywhere. Impossible to hand to a browser we
do not embed.

**Origin locking.** We see and may refuse every main-frame navigation, which is
what makes the name→icon→origin binding mean anything.

**Per-app presentation.** UA and client hints per surface, so a bank can be
stock mobile Chrome while everything else is a Linux desktop.

**No bundle cost, and someone else's security updates.** The engine is the
system's: zero MB in the APK, patched by the OS vendor — Vanadium on
GrapheneOS. Cold start to a usable grid is 381 ms partly because we initialise
no engine of our own.

**The renderer sandbox, free.** Page code runs in Android *isolated* uids under
Chromium's site isolation. The strong boundary is already where hostile content
lives.

---

## Using a real browser

### Unmodified, via intents or Custom Tabs

Zero profile control, by design — the browser protects the user *from* the
embedding app. Fine for a deliberate handoff, which is what hush does with it.
Useless for surfaces.

### A modified Vanadium (or any Chromium fork)

You would be maintaining a Chromium fork with an added embedding API. Chromium
ships every four weeks with continuous security patches; GrapheneOS's Vanadium
is a hardening patch set and a substantial ongoing effort *without* exposing an
embedder API. This is a team, indefinitely, to obtain something WebView already
gives us.

Not a serious option, and worth saying so plainly so it stops coming up.

### GeckoView

The only genuine alternative. Mozilla ships it as a **supported embedding API**
— Firefox for Android is built on it — so unlike a Chromium fork there is no
patch set to carry.

What it would hand back, from the kludge list above: a built-in PDF viewer
(pdf.js, upstream), real downloads, error pages, no `X-Requested-With`, and
independence from whatever Google does to WebView next. It is also a second
engine, which splits fingerprint surface and doubles the test burden — the same
trade `SHELL.md` weighs for the pmOS target.

What it costs: 70–100 MB of APK, Mozilla's release cadence to track, a
Firefox-shaped fingerprint instead of a Chrome-shaped one, and on GrapheneOS
the loss of Vanadium's hardening in exchange for Gecko's.

---

## The question that decides it

**Does GeckoView expose per-session storage isolation to the embedder, as
cleanly as WebView's `MULTI_PROFILE`?**

Gecko has the machinery — Firefox's Container Tabs are built on `userContextId`
— but whether GeckoView surfaces it per `GeckoSession`, and whether cookies,
storage, cache and service workers all partition along it, is **unverified**.
That is the first thing to establish, and it is a day's work with the probe
approach used everywhere else here: create two sessions, set a cookie in one,
read it from the other.

- **If yes:** GeckoView is a real option, and the trade is 100 MB and an update
  treadmill against roughly a third of the kludge list plus engine
  independence.
- **If no:** the question is settled permanently. `MULTI_PROFILE` is
  irreplaceable, WebView is the only engine on Android that gives an embedder
  the thing hush is built on, and every kludge above is simply the price.

Until that probe runs, treat this document as the argument and not the answer.
