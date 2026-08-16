package com.loader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The fence, as a rule rather than as an app.
 *
 * This tests {@link Origins#matches} and not {@code allowed}, on purpose:
 * android.net.Uri is a stub under a JVM unit test and returns null for
 * everything, so a test of {@code allowed} would pass without deciding
 * anything. Parsing is Android's; the decision is ours, and this is it.
 *
 * The same cases are asserted against scripts/mint.py's `covers` in
 * scripts/test_mint.py, because the rule is written in both places and the
 * catalogue is validated with the Python one at mint time.
 */
public class OriginsTest {

    private static final String[] CHASE = {"chase.com"};

    private static boolean go(String host, String... origins) {
        return Origins.matches(host, "/", origins);
    }

    // ----- host-or-suffix ---------------------------------------------------

    @Test public void hostMatchesItself() {
        assertTrue(go("chase.com", CHASE));
    }

    @Test public void hostMatchesItsSubdomains() {
        // The claim catalogue/README.md makes: chase.com already covers
        // secure.chase.com, so there is nothing to wildcard by hand.
        assertTrue(go("secure.chase.com", CHASE));
        assertTrue(go("a.b.secure.chase.com", CHASE));
    }

    @Test public void suffixIsNotSubstring() {
        // The near-miss the leading dot exists to refuse. Without it this is a
        // registerable domain that inherits a bank's icon.
        assertFalse(go("notchase.com", CHASE));
        assertFalse(go("evilchase.com", CHASE));
    }

    @Test public void aTrailingDomainDoesNotCapture() {
        assertFalse(go("chase.com.evil.example", CHASE));
    }

    @Test public void aParentIsNotCoveredByItsChild() {
        // Fencing an app to secure.chase.com must not let it reach chase.com.
        assertFalse(go("chase.com", "secure.chase.com"));
    }

    @Test public void emptyFenceAllowsNothing() {
        assertFalse(go("chase.com"));
    }

    @Test public void anyOriginInTheListIsEnough() {
        // morganstanley: sign-in moves between two unrelated hosts.
        String[] ms = {"stockplanconnect.com", "morganstanley.com"};
        assertTrue(go("www.stockplanconnect.com", ms));
        assertTrue(go("login.morganstanley.com", ms));
        assertFalse(go("morganstanley.co.uk", ms));
    }

    // ----- the path half ----------------------------------------------------

    @Test public void aPathPrefixNarrowsAHost() {
        // One host serves several products — www.google.com is search, the
        // account pages and Maps — so a bare-host allowlist for one claims all.
        String[] fence = {"www.google.com/maps"};
        assertTrue(Origins.matches("www.google.com", "/maps", fence));
        assertTrue(Origins.matches("www.google.com", "/maps/place/x", fence));
        assertFalse(Origins.matches("www.google.com", "/search", fence));
        assertFalse(Origins.matches("www.google.com", "/", fence));
    }

    @Test public void aPathPrefixStillRequiresTheHost() {
        assertFalse(Origins.matches("evil.example", "/maps", new String[]{"www.google.com/maps"}));
    }

    @Test public void aBareHostIgnoresThePath() {
        assertTrue(Origins.matches("chase.com", "/anything/at/all", CHASE));
    }

    // ----- the scheme -------------------------------------------------------

    @Test public void upgradeRewritesOnlyTheScheme() {
        // Live redirect chains still emit http hops. Refusing them produces a
        // blank page, which reads as the site being broken rather than as us.
        org.junit.Assert.assertEquals(
                "https://chase.com/x", Origins.upgrade("http://chase.com/x"));
        org.junit.Assert.assertEquals(
                "https://chase.com/x", Origins.upgrade("https://chase.com/x"));
    }

    @Test public void upgradeLeavesOtherSchemesAlone() {
        // These are not ours to rewrite, and they must still fail the scheme
        // check in allowed() rather than become https.
        for (String url : new String[]{
                "intent://chase.com", "javascript:alert(1)", "file:///etc/hosts",
                "data:text/html,x", "about:blank"}) {
            org.junit.Assert.assertEquals(url, Origins.upgrade(url));
        }
    }

    @Test public void upgradeDoesNotRewriteAHostThatMerelyStartsWithHttp() {
        org.junit.Assert.assertEquals(
                "https://http.example/", Origins.upgrade("https://http.example/"));
    }
}
