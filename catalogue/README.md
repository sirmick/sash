# catalogue

One file per identity. A file is a label, some hostnames, an icon and a
permission list — no code, no build knowledge.

```
scripts/mint.py       catalogue/*.json  ->  installable site apps
```

The generated output is checked in, so a catalogue change means re-running it:

```
python3 scripts/mint.py            regenerate
python3 scripts/mint.py --check    verify what is checked in is current
./scripts/test.sh catalogue        the above, plus what an entry may say
```

## An entry is a package

Because a package is the identity boundary: it owns a uid, a data directory and
a permission set that cannot be widened afterwards.

**Surfaces within an entry share that package**, so they share a cookie jar.
That is the only way Gmail is already signed in once Google Account is —
Google's own properties are one SSO family, not OAuth clients of each other, so
either they share cookies or you sign in twice. `google.json` carries four
surfaces for that reason and gets four launcher icons.

Each surface still carries its own fence, and for most entries that is the whole
point of surfaces. But it is a fence against the *outside*, not against a
sibling — because a jar cannot be fenced from itself. Sharing a package means
sharing cookies, so a compromised Gmail page already holds Drive's session
whatever the allowlists say, and fencing the four Google surfaces from each
other would be theatre with a maintenance cost. `google.json` therefore gives
all four the whole of `google.com`.

Where surfaces have genuinely separate origins, the fence between them is real
and enforced. The rule is: **a fence is worth writing between things that do not
already share a cookie jar.** Between things that do, the separation that
matters is the one to every other package.

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

An entry declaring any permission must carry a `note` saying why, and minting
refuses one that does not. The point is not the note; it is that the diff cannot
be small.

## What minting refuses

An entry is reviewed by reading it, so the checks are for what reading misses.

| | |
| --- | --- |
| An origin that is not a bare hostname | `*.bank.com`, `https://bank.com`, `bank.com/` — patterns are refused by design, and a URL that looks like a hostname reads as one |
| A `home` outside its own origins | The app would eject itself on launch, and the bug report is "it opens to a dialog" |
| `home` over http | An icon is a promise about where it goes; cleartext cannot keep it |
| A surface id that is a Java keyword, or duplicated | It becomes a class name, an icon and a taskAffinity |
| An `id` that is not the filename | The filename is what makes ids unique without a registry |
| A missing icon | |

Every problem with an entry is reported at once, not one per round trip.
