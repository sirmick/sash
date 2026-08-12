# e2e

Maestro flows. `make e2e` runs the gating ones; `make e2e-live` runs the rest.

## Why these exist

Every flow here encodes a bug that shipped, or nearly did:

| | |
| --- | --- |
| `01-vault-lifecycle` | Saving persisted the credential but never left the editor, so a second tap made a duplicate. `assertNotVisible: "Cancel"` is the half that catches it. |
| `02-wrong-passphrase` | The sealed check value in `meta.json` exists so an empty vault cannot accept any passphrase. |
| `03-editing-survives-sync` | The sync poller recomputed the screen every second and tore down whatever the user had opened. |

Two of the three are Compose state bugs that no amount of unit testing the vault
would have found — the vault was behaving perfectly in all of them.

## Gating versus live

`e2e/` needs no network and must pass before a commit.

`e2e/live/` talks to real sites. It fails for reasons that are not our fault —
network, redesigns, bot detection — so it never blocks anything. It is still the
only test of the thing that matters most, so it is kept and run deliberately.

## Running

```
make e2e         # gating
make e2e-live    # real sites, run on purpose
```

Maestro drives the device over adb, so a Cuttlefish instance or a phone must be
attached. `make cf-start` in `../pane` boots one.
