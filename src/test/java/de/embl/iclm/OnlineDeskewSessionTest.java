package de.embl.iclm;

import static org.junit.Assert.assertEquals;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * The order the session checks a folder's files in. Headless; the window itself is
 * {@link OnlineDeskewSessionGuiTest}.
 */
public class OnlineDeskewSessionTest {

	private static int next(int shown, int count, Integer... pending) {
		final Set<Integer> left = new HashSet<Integer>(java.util.Arrays.asList(pending));
		return OnlineDeskewSession.nextToCheck(shown, count, left::contains);
	}

	@Test
	public void theShownTimePointIsCheckedBeforeEveryFileInFrontOfIt() {
		assertEquals(10, next(10, 50, 3, 4, 10, 11));
	}

	@Test
	public void thenTheOnesAfterItBecausePlaybackRunsForward() {
		assertEquals(11, next(10, 50, 3, 4, 11, 12));
		assertEquals(3, next(10, 50, 3, 4));		// and only then the ones before it
		assertEquals(0, next(49, 50, 0, 7));		// wrapping round past the last
	}

	@Test
	public void nothingLeftIsMinusOne() {
		assertEquals(-1, next(5, 50));
		assertEquals(-1, next(0, 0));
		assertEquals(2, next(-1, 3, 2));			// no time point shown yet counts as the first
	}
}
