package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Test;

/**
 * Which computers the window theme's rule runs on.
 *
 * <p>The rule is tested on invented computers and invented lists - an account, a DNS suffix, an
 * address from the documentation range 192.0.2.0/24 - never on the shipped list or on the
 * computer running the suite, whose answers would make the test depend on where it runs. What is
 * read from a real computer is tested as parsing, on output in the shape one gives.
 */
public class DebugSiteTest {

	private static final List<String> NONE = Collections.<String> emptyList();
	private static final List<String> EMBL = Arrays.asList("embl.de");
	private static final List<String> ELSEWHERE = Arrays.asList("192.0.2.200");

	private static final Set<String> USERS = digests("alice", "bob");
	private static final Set<String> ADDRESSES = digests("192.0.2.7", "198.51.100.3");

	@After
	public void backToAuto() {
		Debug.Theme.forget();
	}

	private static Set<String> digests(String... values) {
		Set<String> digests = new HashSet<String>();
		for (String value : values) digests.add(Debug.Site.digest(value));
		return digests;
	}

	private static boolean eligible(List<String> suffixes, String user, List<String> addresses) {
		return Debug.Site.eligible(suffixes, user, addresses, USERS, ADDRESSES);
	}


	// ---- the digests ---------------------------------------------------------------------

	/** Salted SHA-256, lower-case hex: a value checked against an independent implementation. */
	@Test
	public void aDigestIsTheSaltedSha256OfTheValue() {
		assertEquals("4073d526daa4f8bad48aed72d20a5cc3c8d29d3e47476401dc61a3578cebba75",
				Debug.Site.digest("alice"));
		assertEquals("03784b402f0c19ab198cf3ef37f62fec4216b56f076083896cb2cf2827ef371f",
				Debug.Site.digest("192.0.2.7"));
	}

	/** The shipped lists hold digests only, and as many as were asked for. */
	@Test
	public void theShippedListsAreDigests() {
		assertEquals(5, Debug.Site.USERS.size());
		assertEquals(4, Debug.Site.ADDRESSES.size());
		for (String entry : Debug.Site.USERS) assertTrue(entry, entry.matches("[0-9a-f]{64}"));
		for (String entry : Debug.Site.ADDRESSES) assertTrue(entry, entry.matches("[0-9a-f]{64}"));
		assertFalse("an unlisted account on an EMBL network",
				Debug.Site.eligible(EMBL, "someone", ELSEWHERE));
	}


	// ---- the rule ------------------------------------------------------------------------

	@Test
	public void aListedAccountOnAnEmblNetworkQualifies() {
		assertTrue(eligible(EMBL, "alice", ELSEWHERE));
		assertTrue(eligible(EMBL, "bob", NONE));
	}

	@Test
	public void anyOtherAccountOnTheSameNetworkDoesNot() {
		assertFalse(eligible(EMBL, "carol", ELSEWHERE));
		assertFalse("no account at all", eligible(EMBL, null, ELSEWHERE));
		assertFalse("nor a name that merely contains a listed one", eligible(EMBL, "alice2", ELSEWHERE));
	}

	@Test
	public void aListedAccountOffAnEmblNetworkDoesNot() {
		assertFalse(eligible(Arrays.asList("home.lan"), "alice", ELSEWHERE));
		assertFalse("no suffix at all", eligible(NONE, "alice", ELSEWHERE));
	}

	@Test
	public void anySuffixContainingTheKeywordIsAnEmblNetwork() {
		assertTrue(eligible(Arrays.asList("wlan.embl.de"), "alice", NONE));
		assertTrue(eligible(Arrays.asList("EMBL.DE"), "alice", NONE));
		assertTrue("one of several is enough",
				eligible(Arrays.asList("home.lan", "embl-hamburg.de"), "alice", NONE));
	}

	@Test
	public void aListedAddressQualifiesWhoeverIsLoggedOn() {
		assertTrue(eligible(NONE, "microscope", Arrays.asList("192.0.2.7")));
		assertTrue("on any network, beside other addresses",
				eligible(Arrays.asList("home.lan"), null, Arrays.asList("192.0.2.200", " 198.51.100.3 ")));
		assertFalse("a neighbouring address is not a listed one",
				eligible(NONE, "microscope", Arrays.asList("192.0.2.8")));
	}

	@Test
	public void nothingKnownIsNotEligible() {
		assertFalse(eligible(null, null, null));
		assertFalse(Debug.Site.eligible((Debug.Site.Facts) null));
	}

	@Test
	public void theAccountIsComparedWithoutCaseOrDomain() {
		assertEquals("alice", Debug.Site.accountName("Alice"));
		assertEquals("alice", Debug.Site.accountName("EMBL\\alice"));
		assertEquals("alice", Debug.Site.accountName(" alice@embl.de "));
		assertNull(Debug.Site.accountName("  "));
		assertTrue(eligible(EMBL, "EMBL\\ALICE", NONE));
	}


	// ---- what a computer says about itself ------------------------------------------------

	/** The shape of {@code ipconfig /all} on an English Windows 11. */
	private static final String IPCONFIG_ENGLISH = String.join("\r\n",
			"",
			"Windows IP Configuration",
			"",
			"   Host Name . . . . . . . . . . . . : LAPTOP-01",
			"   Primary Dns Suffix  . . . . . . . : embl.de",
			"   Node Type . . . . . . . . . . . . : Hybrid",
			"   IP Routing Enabled. . . . . . . . : No",
			"   DNS Suffix Search List. . . . . . : embl.de",
			"                                       embl-hamburg.de",
			"",
			"Ethernet adapter Ethernet:",
			"",
			"   Connection-specific DNS Suffix  . : ",
			"   Description . . . . . . . . . . . : Intel(R) Ethernet Connection",
			"   IPv4 Address. . . . . . . . . . . : 192.0.2.200(Preferred)",
			"   DNS Servers . . . . . . . . . . . : 192.0.2.53",
			"                                       fec0:0:0:ffff::1%1",
			"",
			"Wireless LAN adapter Wi-Fi:",
			"",
			"   Connection-specific DNS Suffix  . : wlan.embl.de",
			"   Link-local IPv6 Address . . . . . : fe80::1c2b:3d4e:5f60:7182%12(Preferred)",
			"");

	/** The same layout with German labels, where a long label fills the dotted column. */
	private static final String IPCONFIG_GERMAN = String.join("\r\n",
			"Windows-IP-Konfiguration",
			"",
			"   Hostname  . . . . . . . . . . . . : PC01",
			"   Primäres DNS-Suffix . . . . . . . : ",
			"   Knotentyp . . . . . . . . . . . . : Hybrid",
			"",
			"Ethernet-Adapter Ethernet:",
			"",
			"   Verbindungsspezifisches DNS-Suffix: embl.de",
			"   Beschreibung. . . . . . . . . . . : Intel(R) Ethernet",
			"   IPv4-Adresse  . . . . . . . . . . : 192.0.2.201(Bevorzugt)",
			"");

	@Test
	public void ipconfigGivesEverySuffixItNames() {
		Set<String> suffixes = Debug.Site.dnsSuffixesFromIpconfig(IPCONFIG_ENGLISH);
		assertTrue(suffixes.toString(), suffixes.contains("embl.de"));
		assertTrue("the second entry of the search list", suffixes.contains("embl-hamburg.de"));
		assertTrue("a connection-specific suffix", suffixes.contains("wlan.embl.de"));
		assertFalse("an empty suffix is not a suffix", suffixes.contains(""));
		assertFalse("nor is the host name", suffixes.contains("LAPTOP-01"));
		assertTrue(eligible(new java.util.ArrayList<String>(suffixes), "alice", NONE));
	}

	@Test
	public void ipconfigIsReadInAnotherLanguage() {
		Set<String> suffixes = Debug.Site.dnsSuffixesFromIpconfig(IPCONFIG_GERMAN);
		assertEquals(Collections.singleton("embl.de"), suffixes);
	}

	@Test
	public void resolvConfGivesItsSearchAndDomainLines() {
		Set<String> suffixes = Debug.Site.dnsSuffixesFromResolvConf(String.join("\n",
				"# Generated by NetworkManager",
				"search embl.de embl-hamburg.de",
				"domain embl.de",
				"nameserver 192.0.2.53"));
		assertEquals(Arrays.asList("embl.de", "embl-hamburg.de"), Arrays.asList(suffixes.toArray()));
	}

	@Test
	public void scutilGivesItsDomainLines() {
		Set<String> suffixes = Debug.Site.dnsSuffixesFromScutil(String.join("\n",
				"DNS configuration",
				"",
				"resolver #1",
				"  search domain[0] : embl.de",
				"  nameserver[0] : 192.0.2.53",
				"  flags    : Request A records",
				"",
				"resolver #2",
				"  domain   : local"));
		assertEquals(Arrays.asList("embl.de", "local"), Arrays.asList(suffixes.toArray()));
	}

	@Test
	public void nothingToReadGivesNothing() {
		assertTrue(Debug.Site.dnsSuffixesFromIpconfig(null).isEmpty());
		assertTrue(Debug.Site.dnsSuffixesFromResolvConf(null).isEmpty());
		assertTrue(Debug.Site.dnsSuffixesFromScutil(null).isEmpty());
	}

	/** Reading this computer completes and names an account; whether it qualifies is its own business. */
	@Test
	public void thisComputerCanBeRead() {
		Debug.Site.Facts facts = Debug.Site.Facts.probe();
		assertNotNull(facts.toString(), Debug.Site.accountName(facts.userName));
		for (String address : facts.addresses)
			assertFalse("the loopback is left out", address.startsWith("127."));
	}


	// ---- the gate in front of the schedule -----------------------------------------------

	/** A Saturday noon: outside office hours, where the schedule alone always says yes. */
	private static long saturdayNoon() {
		return LocalDateTime.of(2026, 9, 19, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	@Test
	public void theRuleNeverShowsTheThemeOnAComputerThatDoesNotQualify() {
		Debug.Theme.assumeEligible(false);
		assertFalse("not even at the weekend", Debug.Theme.ruleSays(saturdayNoon()));
		Debug.Theme.assumeEligible(true);
		assertTrue("where the schedule decides as before", Debug.Theme.ruleSays(saturdayNoon()));
	}

	@Test
	public void untilTheComputerHasBeenLookedAtTheAnswerIsNo() {
		Debug.Theme.assumeEligible(null);
		assertNull(Debug.Theme.eligibility());
		assertFalse(Debug.Theme.ruleSays(saturdayNoon()));
	}

	@Test
	public void askingOutrightStillWorksOnAnyComputer() {
		Debug.Theme.assumeEligible(false);
		Debug.party_mode("on");
		assertTrue("an explicit request is absolute", Debug.Theme.isThemedNow());
		Debug.party_mode("auto");
		assertFalse(Debug.Theme.isThemedNow());
	}

	@Test
	public void askingSaysWhenThisComputerIsNotOneItIsShownOn() {
		Debug.Theme.assumeEligible(false);
		assertTrue(Debug.party_mode(), Debug.party_mode().contains("not one the rule shows it on"));
		Debug.Theme.assumeEligible(true);
		assertFalse(Debug.party_mode(), Debug.party_mode().contains("not one"));
	}
}
