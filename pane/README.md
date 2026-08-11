# pane

Web apps that behave like apps: **one Activity, one jar, one fence.**

Three apps, hardcoded, because three is the smallest set that tests the whole
idea — a base identity, an app that inherits it, and an app that shares nothing
with either.

| | jar | fence |
| --- | --- | --- |
| Google Account | `google` | accounts.google.com, myaccount.google.com |
| Gmail | `google` — *inherits* | mail.google.com |
| Chase | `chase` | chase.com |

## The two ideas, kept apart

**The jar (`contextId`) is who you are.** Apps sharing one share cookies and
storage, so signing in to Google Account signs in Gmail. For Google's own
properties this is the only thing that works — Gmail is not an OAuth client of
Google, it is a first-party surface of the same SSO family, so there is no token
dance to run.

**The fence (`origins`) is where an icon may go.** Per app, regardless of jar.
Gmail shares Google's cookies and still cannot wander to YouTube. Without it an
icon is just a bookmark, and one open redirect puts somebody else's page under
your bank's name.

When a blocked navigation belongs to a **sibling in the same jar**, the sibling
opens instead of the user seeing a refusal — signed out, Gmail bounces to
`accounts.google.com` and Google Account opens. Deliberately restricted to the
same jar: handing a blocked navigation to an app in a *different* one would move
a session across the isolation boundary by redirect.

## Why an Activity per app

Android's task system then gives for free what a single-activity compositor
makes you build by hand: **a recents card per app**, switching between them, and
a back stack that behaves. Nothing composites over anything, so the trust bar is
just a view above the content. Sessions outlive their activity — otherwise every
rotation would reload your bank page.

## Status

Verified on Cuttlefish / Android 17:

- **Chase renders and offers its login form.** No bot challenge, no unsupported
  browser — GeckoView presents as Firefox, which banks support.
- **Google serves its sign-in form** and recognises Firefox.
- Four separate recents tasks; the fence ejects; the sibling hand-over works.

Not yet done: a real sign-in with real credentials (the point of the whole
exercise), downloads, file chooser, biometric unlock, and sync.

**Known gap:** recents cards take the app's colour but not its label — AOSP's
recents shows the package name regardless of `TaskDescription`. Worth checking
on a real device before spending time on it.

## Running it

```
make install          build and install
make run              open the launcher
make open APP=chase   open one app straight into its own task
make logs             follow
make shot             screenshot
```

Toolchain and Cuttlefish setup are inherited from the archived `hush/` branch —
see `../hush/docs/SALVAGE.md` for what else carried over and `../hush/docs/PLAN.md`
for why this exists.
