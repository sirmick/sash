#!/usr/bin/env python3
"""
Tests for the catalogue and the minting of it.

    python3 scripts/test_mint.py

Two kinds of test here, and the split is deliberate.

The first kind runs against the **real catalogue**: every entry is valid, and
the generated output checked in beside it is current. That is the one that
would have caught the manager listing three sites the catalogue had stopped
describing.

The second kind runs against **fabricated entries**, and every case is a
mistake someone could plausibly make in a pull request. A catalogue entry is
reviewed by reading it, so the checks exist to catch what reading does not:
an origin that looks like a hostname and is a pattern, a home page one
redirect outside its own fence, a permission on a bank.
"""
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import mint  # noqa: E402

ROOT = mint.ROOT

ICON = (
    # The smallest valid PNG. Tests need a file that exists, not an image.
    b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08"
    b"\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\nIDATx\x9cc\x00\x01\x00\x00"
    b"\x05\x00\x01\r\n-\xb4\x00\x00\x00\x00IEND\xaeB`\x82"
)


def entry(**over):
    """A minimal valid entry, for a test to break one field of."""
    e = {
        "id": "bank",
        "label": "Bank",
        "permissions": [],
        "surfaces": [{
            "id": "bank", "label": "Bank", "icon": "bank.png",
            "home": "https://www.bank.com/", "origins": ["bank.com"],
        }],
    }
    e.update(over)
    return e


class Fixture(unittest.TestCase):
    """Loads fabricated entries against a scratch catalogue directory."""

    def setUp(self):
        self.dir = pathlib.Path(tempfile.mkdtemp())
        (self.dir / "icons").mkdir()
        (self.dir / "icons" / "bank.png").write_bytes(ICON)
        self._real = mint.CATALOGUE
        mint.CATALOGUE = self.dir
        self.addCleanup(self._restore)

    def _restore(self):
        mint.CATALOGUE = self._real
        shutil.rmtree(self.dir, ignore_errors=True)

    def load(self, e, name=None):
        path = self.dir / f"{name or e.get('id', 'bank')}.json"
        path.write_text(json.dumps(e))
        return mint.load(path)

    def refuses(self, e, because, name=None):
        with self.assertRaises(mint.CatalogueError) as caught:
            self.load(e, name)
        joined = "; ".join(caught.exception.problems)
        self.assertIn(because, joined, f"expected {because!r} in: {joined}")
        return caught.exception


class TheRealCatalogue(unittest.TestCase):
    """The entries actually shipped, and the output generated from them."""

    def test_every_entry_is_valid(self):
        paths = mint.entries()
        self.assertTrue(paths, "no catalogue entries found")
        for path in paths:
            with self.subTest(entry=path.name):
                mint.load(path)

    def test_generated_output_is_current(self):
        # The drift guard. Anything naming a site — the loader's flavours, the
        # manager's list, the manifest's package-visibility queries — is
        # generated, so a catalogue change that was not re-minted is a bug that
        # otherwise only shows up on a device.
        result = subprocess.run(
            [sys.executable, str(ROOT / "scripts" / "mint.py"), "--check"],
            capture_output=True, text=True,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_no_entry_grants_a_permission_unremarked(self):
        # Not a rule against permissions — a rule that one is never silent.
        # Adding `camera` to a bank is a security change, and it cannot be
        # undone without reinstalling the app and losing its profile.
        for path in mint.entries():
            e = mint.load(path)
            if e.get("permissions"):
                with self.subTest(entry=path.name):
                    self.assertIn(
                        "note", e,
                        f"{path.name} declares {e['permissions']} and explains nothing",
                    )


class TheFenceRule(unittest.TestCase):
    """`covers` mirrors Origins.allowed, and the mirror is the point: minting
    checks a home page against the same rule the device will enforce."""

    def test_host_matches_itself(self):
        self.assertTrue(mint.covers(["chase.com"], "chase.com"))

    def test_host_matches_its_subdomains(self):
        self.assertTrue(mint.covers(["chase.com"], "secure.chase.com"))
        self.assertTrue(mint.covers(["chase.com"], "a.b.chase.com"))

    def test_suffix_is_not_substring(self):
        # The near-miss the whole rule exists to refuse. `endswith` alone would
        # let this through, and it is a hostname anyone can register.
        self.assertFalse(mint.covers(["chase.com"], "notchase.com"))
        self.assertFalse(mint.covers(["chase.com"], "chase.com.evil.example"))

    def test_a_parent_is_not_covered_by_its_child(self):
        self.assertFalse(mint.covers(["secure.chase.com"], "chase.com"))


class Validation(Fixture):
    """Each of these is a plausible pull request."""

    def test_a_valid_entry_loads(self):
        self.assertEqual(self.load(entry())["id"], "bank")

    def test_home_outside_its_own_fence(self):
        # The failure this prevents reports as "the app opens to a dialog",
        # which is a long way from "the allowlist is missing a host".
        self.refuses(
            entry(surfaces=[dict(entry()["surfaces"][0], home="https://login.bank.co.uk/")]),
            "outside its own origins",
        )

    def test_home_must_be_https(self):
        self.refuses(
            entry(surfaces=[dict(entry()["surfaces"][0], home="http://www.bank.com/")]),
            "home must be an https:// URL",
        )

    def test_an_origin_may_not_be_a_url(self):
        self.refuses(
            entry(surfaces=[dict(entry()["surfaces"][0], origins=["https://bank.com"])]),
            "bare lowercase hostname",
        )

    def test_an_origin_may_not_be_a_pattern(self):
        # Regular expressions are refused by design: a sloppy one is an open
        # redirect that no reviewer catches in a diff.
        for bad in ["*.bank.com", "bank.*", ".*bank.com", "bank.com/"]:
            with self.subTest(origin=bad):
                self.refuses(
                    entry(surfaces=[dict(entry()["surfaces"][0], origins=[bad])]),
                    "bare lowercase hostname",
                )

    def test_an_origin_must_have_a_dot(self):
        self.refuses(
            entry(surfaces=[dict(entry()["surfaces"][0], origins=["localhost"])]),
            "bare lowercase hostname",
        )

    def test_unknown_permission(self):
        self.refuses(entry(permissions=["contacts"], note="why"), "unknown permission")

    def test_known_permissions_are_accepted(self):
        e = self.load(entry(permissions=["camera", "microphone", "location"],
                            note="Check deposit needs a camera."))
        self.assertEqual(len(e["permissions"]), 3)

    def test_a_permission_without_a_note_is_refused(self):
        # The point is not the note. It is that a pull request widening what a
        # package can ever reach cannot be a one-word diff.
        self.refuses(entry(permissions=["camera"]), "must carry a note")
        self.refuses(entry(permissions=["camera"], note="   "), "must carry a note")

    def test_no_permissions_needs_no_note(self):
        self.load(entry())

    def test_id_must_match_filename(self):
        # The filename is what makes ids unique without a registry.
        self.refuses(entry(id="bank"), "does not match filename", name="banc")

    def test_id_must_be_a_legal_package_suffix(self):
        for bad in ["My-Bank", "1bank", "my.bank", "my_bank", ""]:
            with self.subTest(id=bad):
                self.refuses(entry(id=bad), "must match", name=bad or "empty")

    def test_duplicate_surface_ids(self):
        s = entry()["surfaces"][0]
        self.refuses(entry(surfaces=[s, dict(s)]), "duplicate surface id")

    def test_surface_id_may_not_be_a_java_keyword(self):
        # It becomes a class name. `class.java` does not compile.
        self.refuses(
            entry(surfaces=[dict(entry()["surfaces"][0], id="switch")]),
            "Java keyword",
        )

    def test_missing_icon(self):
        self.refuses(
            entry(surfaces=[dict(entry()["surfaces"][0], icon="nope.png")]),
            "missing icon",
        )

    def test_surfaces_may_not_be_empty(self):
        self.refuses(entry(surfaces=[]), "non-empty list")

    def test_every_problem_is_reported_at_once(self):
        # A contributor fixing one typo per round trip gives up.
        e = self.refuses(
            entry(permissions=["contacts"], note="why",
                  surfaces=[dict(entry()["surfaces"][0], icon="nope.png", origins=["*.bank.com"])]),
            "unknown permission",
        )
        self.assertGreaterEqual(len(e.problems), 3, e.problems)


class Minting(Fixture):
    """What lands in the flavour source set."""

    def setUp(self):
        super().setUp()
        self.out = self.dir / "out"

    def mint(self, e):
        mint.mint_loader(self.load(e), self.out)
        return self.out

    def test_the_entry_travels_verbatim(self):
        # The app cannot drift from the description of it, because they are the
        # same bytes. A test that compares parsed JSON would not say that.
        e = entry()
        out = self.mint(e)
        self.assertEqual(json.loads((out / "assets" / "site.json").read_text()), e)

    def test_no_permissions_means_no_uses_permission(self):
        manifest = (self.mint(entry()) / "AndroidManifest.xml").read_text()
        self.assertNotIn("uses-permission", manifest)

    def test_a_declared_permission_reaches_the_manifest(self):
        # The manifest is the guarantee: a package that never requested a
        # permission cannot be granted it later.
        e = entry(permissions=["camera"], note="Check deposit.")
        manifest = (self.mint(e) / "AndroidManifest.xml").read_text()
        self.assertIn("android.permission.CAMERA", manifest)
        self.assertNotIn("RECORD_AUDIO", manifest)

    def test_one_activity_class_and_icon_per_surface(self):
        s = entry()["surfaces"][0]
        out = self.mint(entry(surfaces=[
            s,
            dict(s, id="statements", label="Statements", home="https://statements.bank.com/"),
        ]))
        manifest = (out / "AndroidManifest.xml").read_text()
        for sid in ("bank", "statements"):
            with self.subTest(surface=sid):
                self.assertIn(f'android:name="com.loader.surface.{sid}"', manifest)
                # Distinct tasks, so tapping the second icon does not surface
                # the first — which is what activity-alias did.
                self.assertIn(f'android:taskAffinity="com.loader.surface.{sid}"', manifest)
                self.assertTrue((out / "java" / "com" / "loader" / "surface" / f"{sid}.java").exists())
                self.assertTrue((out / "res" / "mipmap-xxhdpi" / f"ic_{sid}.png").exists())

    def test_surfaces_share_the_one_package(self):
        # The whole reason Gmail is already signed in once Google Account is:
        # one manifest, therefore one uid, therefore one cookie jar.
        s = entry()["surfaces"][0]
        out = self.mint(entry(surfaces=[s, dict(s, id="statements", label="Statements",
                                                home="https://statements.bank.com/")]))
        self.assertEqual(len(list(out.rglob("AndroidManifest.xml"))), 1)


class ManagerCatalogue(Fixture):
    """What the installer shows before you install anything."""

    def render(self, entries):
        loaded = [self.load(e, e["id"]) for e in entries]
        return {p.name: t for p, t in mint.manager_files(loaded).items()}

    def test_permissions_are_stated_not_implied(self):
        files = self.render([entry()])
        self.assertIn('"network only"', files["Catalogue.java"])

    def test_a_permission_is_named_in_the_list(self):
        # The catalogue says so *before* you install, which is the only moment
        # the choice exists.
        files = self.render([entry(permissions=["camera", "microphone"], note="Video calls.")])
        self.assertIn('"network, camera, microphone"', files["Catalogue.java"])

    def test_every_origin_is_listed(self):
        s = dict(entry()["surfaces"][0], origins=["bank.com", "bankonline.com"])
        files = self.render([entry(surfaces=[s])])
        self.assertIn("bank.com, bankonline.com", files["Catalogue.java"])

    def test_origins_are_deduped_across_surfaces(self):
        s = entry()["surfaces"][0]
        files = self.render([entry(surfaces=[s, dict(s, id="statements", label="Statements",
                                                     home="https://statements.bank.com/")])])
        self.assertIn('"bank.com"', files["Catalogue.java"])
        self.assertIn(", 2)", files["Catalogue.java"])  # two icons, said out loud

    def test_package_visibility_names_every_site(self):
        # Android 11+ hides other packages and the list cannot be wildcarded,
        # so a site missing here is a site the manager reports as not installed
        # forever after.
        files = self.render([entry(), entry(id="broker", label="Broker", surfaces=[
            dict(entry()["surfaces"][0], id="broker", label="Broker",
                 home="https://www.broker.com/", origins=["broker.com"])])])
        manifest = files["AndroidManifest.xml"]
        self.assertIn('<package android:name="com.loader.bank" />', manifest)
        self.assertIn('<package android:name="com.loader.broker" />', manifest)
        self.assertIn('<package android:name="com.pane" />', manifest)


if __name__ == "__main__":
    unittest.main(verbosity=2)
