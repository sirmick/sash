# hush — what survives the pivot

This branch is archived. It built a working shell on wash + WebView, and then
measured two things that ended that approach: WebView announces its package name
to every site permanently, and wash was 19.5 MB of router delivering a JSON
document. See `PLAN.md` for where it goes next and `ENGINE.md` for the evidence.

This file is the inventory for whoever starts the new repository. It is written
to be read *before* writing anything, because most of what was expensive here
was not code.

---

## Keep: the documents

The largest asset, and the most portable. Roughly four thousand lines, and the
parts that cost the most were the corrections — every place a document says
"measured" or "observed, not anticipated" is a thing that would otherwise be
learned again the hard way.

| | Status for the new repo |
| --- | --- |
| `PRODUCT.md` | Intact. Strategy and claim ladder are engine-independent. |
| `STATE.md` | **Intact and unused.** The sync design — three planes, scope table, merge discipline, the one-minute onboarding bar. Nothing was built; nothing needs revising. |
| `ENGINE.md` | Intact, and now the *reason* for the new repo. The kludge inventory, the X-Requested-With finding, the GeckoView probe results. |
| `ANDROID.md` | Mostly intact. Verified platform constraints, the fingerprint measurements, the Cuttlefish setup. Revise the WebView-capability sections. |
| `SHELL.md` | Concepts survive: trust roles, surfaces, identity roots, origin locking, composition. Revise the capability-port and engine specifics. |
| `DECISIONS.md` | Keep the format — every entry records what would reverse it. Several entries now *have* been reversed, which is the format working. |
| `PROVIDERS.md` | Untested design. Keep as intent. |
| `CHROME.md` | Historical. Its trust-state and surface-versus-handoff reasoning survives; its phases do not. |

**Correct these before reuse**, because the branch disproved them: the chrome is
not meaningfully unprivileged (it calls the host to open surfaces and claim
device roles — `SHELL.md` C5 should be dropped rather than restated); autofill is
not an injection problem (GeckoView hands the embedder the login store); and
Vaultwarden-on-loopback is not needed at all.

---

## Keep: the registry model and its rules

`apps/registry/be/{model,store,seed}.go` plus its tests — about 700 lines of Go
that become roughly 300 of Kotlin. **The rules are the value, not the code:**

- A schema version on every document, and refusal to open one newer than the
  build understands.
- **Unknown fields round-tripped, never dropped.** Cheap now, impossible later:
  without it the older of two devices silently deletes the newer one's data
  every time it writes.
- Everything keyed by stable id. No positional arrays — position is what does
  not merge.
- Layout keyed by **device class** first, with `cw`/`ch` spans present from the
  first version, because a pinned surface at a larger size is how widgets work
  and a span added after layouts sync is a migration.
- `home` (an entry URL) is separate from `origins` (an allowlist). Deriving one
  from the other broke on the first real app.
- An origin entry is `host` or `host/path-prefix`. One host serves search, the
  account pages and Maps; a bare-host allowlist claims all three.
- `launch` is per app — `surface` or `handoff` — because compositing buys
  unlinkability, not security, and a bank has no identity left to withhold.
- An identity root may back **at most one app**, and that app may not itself
  authenticate through a root.
- Atomic writes: temp file, fsync, rename. A corrupt or newer-schema document is
  refused, never replaced by an empty one whose next write destroys it.
- Snapshots are deep copies. Subscribers outlive the lock.

The tests encode all of it and port with the model.

---

## Keep: the policy logic

Language-independent, and the part that took the most iteration to get right.

**Origin locking.** Enforce at *two* points — before navigation and again at
commit — because server-side redirects never reach the pre-navigation hook, and
one arrived with somebody else's page rendering under an installed app's name.
Subframes are never ejected; federated login runs in iframes. https only, and
upgrade `http` links rather than refusing them, or a live redirect chain becomes
a blank page.

**Ejection is a UI event, not a silent block.** A blocked navigation with no
explanation reads as the site being broken. Route first — a link to an origin
another app owns was never a lock failure — then offer browse-separately before
add-to-this-app.

**Link routing.** hush holds the browser role; every link is matched against the
registry and opened in the app that owns that origin, in that app's jar, at the
requested path. Unowned links open in a throwaway browsing context, marked as
such. Beware: handing off to "the browser" when you *are* the browser is an
infinite loop.

**Raising is not navigating.** A tap on the grid finds the page where it was
left; a routed link must actually go to the link.

**The identity-root flow.** Sign-in runs top-level in the root's own context and
completes **only when a real app owns the destination** — otherwise a provider
bouncing to its marketing site pushes an unowned page into the child's identity.

**Downloads.** Fetch with the *asking surface's* cookies, read on the UI thread,
and sanitise the filename — `Content-Disposition` is attacker-influenced.

**Trust is two mechanisms.** A tint on the grid sets expectation before the tap;
a native strip above the surface carries the guarantee, because a full-screen
surface covers anything the chrome painted. Four states: local, origin-locked,
browsing, handoff.

---

## Keep: the Android host mechanics

Engine-independent, and directly portable:

- Trust strip in an overlay layer above surfaces, inset below the status bar.
- Composition: chrome / surfaces / overlay ordering, with the **chrome emitting
  the rect** a surface may occupy — which is what makes a persistent browser bar
  possible at all.
- Home and browser roles: `CATEGORY_HOME`, `ROLE_HOME`, `ROLE_BROWSER`, back
  that does nothing at the grid, Home that closes a surface, `stateNotNeeded`
  and `configChanges`.
- Cold-start discipline. **381 ms** from a killed process to a usable grid, and
  the 2.5 s it replaced was an artificial delay rather than a real cost.
- `DeviceApps` listing through a `<queries>` declaration — the sanctioned
  launcher route, not `QUERY_ALL_PACKAGES`.
- Custom Tabs handoff, resolved to an explicit package that is not us.
- `FileProvider` scoped to the downloads directory only.

## Keep: the development environment

Hard-won, immediately reusable, and independent of everything above:
`scripts/install-cuttlefish.sh` and the `cf-*` Makefile targets — including
`EGL_PLATFORM=surfaceless` (without which the guest boots to nothing and
Cuttlefish blames your GPU), `cvd create` versus `cvd start`, the release-branch
image pin, and `CF_CONFIG=tablet`.

## Keep: the probe

`host/android/geckoprobe` is the seed of the new thing — a separate module, two
sessions, two context ids, and the measurements that justified the pivot.

---

## Abandon

The wash integration, entirely: `WirePipe`, the wire codec, `RouterService`, the
multicall/symlink exec dance, `cmd/hush`, the Go app wrapper, and the whole
`BOUNDARY.md` apparatus — the foreign module path, `check-boundary.sh`,
`no-app-tags.sh` — which existed to keep wash clean and is moot once nothing
links it.

`Presentation.kt`'s client-hint work goes too: Firefox does not send client
hints, so there is nothing to keep consistent with a user agent. Keep only the
principle — **change the platform story, never the engine version** — and the
measurement method.

The WebView-specific surface engine, its `SurfacePolicy` callbacks and the
`MULTI_PROFILE`/`ProfileStore` usage are replaced wholesale by GeckoView's
delegates and `contextId`.

## One loose end in wash itself

**wash was never modified for hush** — one commit in the whole effort, `cdc71e5`,
which added a MessagePort transport to `web/shell`. hush no longer uses it. It
stands on its own as a sibling to the virtio and relay transports and it has
tests, so it can stay; but it was added for a consumer that left, and that is
worth someone deciding deliberately rather than by neglect.
