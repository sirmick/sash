# catalogue

One file per identity. A file is a label, some hostnames, an icon and a
permission list — no code, no build knowledge.

```
scripts/mint.py       catalogue/*.json  ->  installable site apps
```

## An entry is a package

Because a package is the identity boundary: it owns a uid, a data directory and
a permission set that cannot be widened afterwards.

**Surfaces within an entry share that package**, so they share a cookie jar.
That is the only way Gmail is already signed in once Google Account is —
Google's own properties are one SSO family, not OAuth clients of each other, so
either they share cookies or you sign in twice. `google.json` carries four
surfaces for that reason and gets four launcher icons.

Each surface still has its own fence. Sharing an identity is not permission to
wander: Gmail shares Google's login and still cannot reach Drive's origins.

## Fields

| | |
| --- | --- |
| `id` | package suffix, `com.loader.<id>` |
| `label` | what Settings calls it |
| `permissions` | `camera`, `microphone`, `location`. **Empty is the default and the point.** |
| `surfaces[].origins` | hostnames, matched host-or-suffix |
| `surfaces[].icon` | a PNG in `icons/` |

`chase.com` already covers `secure.chase.com`; there is nothing to wildcard by
hand. Regular expressions are deliberately not supported — a sloppy one is an
open redirect that no reviewer will catch in a diff.

## Origins are discovered, not guessed

Nobody can write these correctly from documentation. `gds.google.com` is part of
signing in to Google and appears in no docs anywhere; it turned up only when a
real sign-in ejected on it.

So apps record what their fence blocks and offer to propose it. Contributions
should come from that, not from imagination.

## Permissions are the thing to review carefully

A pull request adding `camera` to a bank is a security change, not metadata. It
cannot be undone by the user afterwards either — permissions live in the
manifest, so changing one means reinstalling the app and losing its profile.
