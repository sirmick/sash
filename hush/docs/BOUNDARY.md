# hush — the wash boundary

hush is built on wash's router, SDK, and wire, and reuses catalogue apps from it.
It will eventually be its own repository. Until then it lives inside the wash
checkout, and the whole point of this document is that living there costs
nothing later.

---

## The mechanism

`hush/go.mod` declares the module path of the repository hush will *become*:

```
module gitlab.com/sirmick/hush
```

Not a path under `github.com/sirmick/wash`. That is deliberate and it is the
entire enforcement mechanism, because Go's `internal/` rule is applied by module
path prefix:

| hush's module path | can import `wash/internal/…`? |
| --- | --- |
| `github.com/sirmick/wash/hush` | **yes** — nested paths inherit the privilege |
| `gitlab.com/sirmick/hush` | **no** — `use of internal package … not allowed` |

Both were tested; the second is the one we want. The nested form looks tidier
and buys nothing. **Do not "fix" the module path.**

A `replace github.com/sirmick/wash => ../` for local development does not weaken
this — `replace` redirects resolution, it does not grant internal access.

## What hush may depend on

| Surface | Status |
| --- | --- |
| `github.com/sirmick/wash/apps/<x>/be` | **Available today.** Outside `internal/`, so hush can link wash's catalogue apps as-is. This is how `about` and `session` ship in the APK. |
| `github.com/sirmick/wash/pkg/wire` | **Available.** Frame format and transports. |
| `github.com/sirmick/wash/pkg/sdk` | **Available.** The app half — bus, channel, window, persist, state service. |
| `github.com/sirmick/wash/pkg/apps/registry` | **Available.** `registry.App` and `Register`, the app-authoring contract. |
| `github.com/sirmick/wash/cmd/wash` | Built as a *binary artefact*, not imported. `go build` of a main package in a dependency module is legal, and the `tool` directive exists for exactly this. |
| Anything else | No. |

The compiler enforces the `internal/` half. `make check-boundary` catches the
softer drift it permits — reaching into `cmd/`, `tools/`, or escaping the tree
with relative paths — in the same family as wash's own `check-imports`,
`check-design`, and `check-pkg-binaries` guards.

## Rules

- **Nothing in hush is added to wash's `BINS`, `FE_APPS`, or `IMPORT_APPS`.**
  hush has its own roster, derived from wash's generated multicall imports so a
  new app appearing in wash never gets silently linked in.
- **hush has its own `VERSION`, tests, and make targets.** It is not part of
  wash's `make test` or `make all-test`. Neither project's green gate should be
  hostage to the other's flakes.
- **Never mix a wash-core change and a hush change in one commit.** Commits that
  straddle are what make the eventual split messy.
- **Minimise the `@wash/*` frontend packages hush consumes.** Go modules make
  cross-repo trivial; pnpm does not. In-repo a workspace link is free; after the
  split every package hush imports becomes a publish obligation. Ideally that
  list is `@wash/wire` and nothing else.
- **The altitude test for any wash change:** *would you ship this if hush did not
  exist?* If not, it belongs in hush. The router stays a transport; policy lives
  in the broker, which is an app.

So far every wash change hush needs passes that test — `--no-listen` is useful
for any embedded use, reserved-ids-as-a-set generalises an existing special case,
and the TypeScript wire client serves `web/shell` too.

## The split

```
git filter-repo --path hush/
```

Then delete the `replace` directive and pin a published wash version. Nothing
else changes, because the compiler has been preventing anything else from
mattering.

## Current state

**The promotion is done** (2026-08-09), and it is the first and so far only
change to wash:

```
internal/wire            -> pkg/wire
internal/sdk             -> pkg/sdk
internal/apps/registry   -> pkg/apps/registry
```

Package *names* were kept, so 182 files changed only their import paths and no
call site moved. `pkg/sdk` still imports `internal/fs` and
`internal/fswatchproto` — legal, because those imports live inside the wash
module — and neither type appears in its exported API, so external callers never
need to name them. Build, vet, and the full unit suite are green.

Both halves of the boundary were then verified from hush rather than assumed:

```
import "github.com/sirmick/wash/pkg/wire"          -> builds
import "github.com/sirmick/wash/internal/version"  -> use of internal package
                                                      … not allowed
```

Steps 1–3 of the Android host predate this and were built against a completely
unmodified wash, using only binaries and flags that already existed.
