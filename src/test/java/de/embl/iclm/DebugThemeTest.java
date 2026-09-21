package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.Button;
import java.awt.Checkbox;
import java.awt.Choice;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Label;
import java.awt.TextField;
import java.awt.image.BufferedImage;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.border.Border;

import org.junit.After;
import org.junit.Test;

/**
 * What the window theme touches, and what it leaves exactly as it was.
 *
 * <p>Two things here matter more than the look. The first is that <b>nothing changes without a
 * Fiji desktop</b>: a headless batch, a conversion or any of the other tests in this suite must
 * see the ordinary blue, because a background that depends on the hour would make every other
 * test a coin toss. The second is that <b>the rim costs no layout</b>: the space it needs is
 * reserved when the window is built and kept whether the theme is on or off, so a window cannot
 * resize under the hand when the clock passes five.
 */
public class DebugThemeTest {

	/**
	 * Give the mode back, whatever a test asked for.
	 *
	 * <p>{@link Debug.Theme} is static, and surefire runs the suite in one JVM: a forced mode left
	 * behind would follow every test that ran after it into a different answer.
	 */
	@After
	public void backToAuto () {
		Debug.Theme.forget();
	}

	/** With no ImageJ instance the theme is off, whatever the day and hour happen to be. */
	@Test
	public void withoutAFijiDesktopNothingIsEverTheThemedColour () {
		assertFalse ( "a headless run is never themed", Debug.Theme.isThemedNow() );
		assertEquals ( "and the shared background is the usual one",
				Parameter.frameColor, Debug.Theme.background() );
		assertEquals ( "including for a section heading",
				Color.BLACK, Debug.Theme.heading ( Color.BLACK ) );
	}

	/** The margin is wide enough for the tube, in both kinds of window. */
	@Test
	public void theReservedMarginFitsTheTube () {
		assertTrue ( "the rim has to fit in the margin",
				Debug.Theme.RIM_MARGIN >= (int) Math.ceil ( Debug.Rim.RIM ) );
		assertEquals ( "a GenericDialog's own 10 px plus what the subclass adds",
				Debug.Theme.RIM_MARGIN,
				Debug.Theme.DialogRim.GENERIC_DIALOG_MARGIN + Debug.Theme.DialogRim.EXTRA );
	}

	/** A Swing component with no border of its own is padded out to the full margin. */
	@Test
	public void reservingTheRimPadsAComponentThatHasNoPadding () {
		JPanel content = new JPanel();
		Debug.Theme.reserveRim ( content );
		Insets insets = content.getBorder().getBorderInsets ( content );
		assertEquals ( "top", Debug.Theme.RIM_MARGIN, insets.top );
		assertEquals ( "left", Debug.Theme.RIM_MARGIN, insets.left );
		assertEquals ( "bottom", Debug.Theme.RIM_MARGIN, insets.bottom );
		assertEquals ( "right", Debug.Theme.RIM_MARGIN, insets.right );
	}

	/** And one that already has more than enough keeps exactly what it had. */
	@Test
	public void reservingTheRimLeavesAWiderPaddingAlone () {
		JPanel content = new JPanel();
		content.setBorder ( BorderFactory.createEmptyBorder ( 20, 20, 20, 20 ) );
		Debug.Theme.reserveRim ( content );
		Insets insets = content.getBorder().getBorderInsets ( content );
		assertEquals ( 20, insets.top );
		assertEquals ( 20, insets.left );
	}

	/**
	 * The rim border itself asks for no space.
	 *
	 * <p>It is compounded <i>outside</i> the padding, so it is handed the whole component to
	 * paint on while the children stay inside the padding. An inset of its own would move
	 * every field the moment the theme was installed, which is the bug this pins.
	 */
	@Test
	public void theRimBorderTakesNoSpaceOfItsOwn () {
		JPanel bare = new JPanel();
		bare.setBorder ( BorderFactory.createEmptyBorder ( 30, 30, 30, 30 ) );
		Insets before = bare.getBorder().getBorderInsets ( bare );

		JPanel decorated = new JPanel();
		decorated.setBorder ( BorderFactory.createEmptyBorder ( 30, 30, 30, 30 ) );
		Debug.Theme.reserveRim ( decorated );
		Insets after = decorated.getBorder().getBorderInsets ( decorated );

		assertEquals ( "the rim adds no inset", before.top, after.top );
		assertEquals ( before.left, after.left );
	}

	/** Labels, check boxes and panels carry the background; fields and buttons do not. */
	@Test
	public void onlyTheComponentsThatCarryTheBackgroundAreRecoloured () {
		assertTrue ( "AWT label", Debug.Theme.takesTheBackground ( new Label ( "save to" ) ) );
		assertTrue ( "AWT checkbox", Debug.Theme.takesTheBackground ( new Checkbox ( "along Z" ) ) );
		assertTrue ( "Swing label", Debug.Theme.takesTheBackground ( new JLabel ( "save to" ) ) );
		assertTrue ( "Swing checkbox", Debug.Theme.takesTheBackground ( new JCheckBox ( "along Z" ) ) );
		assertTrue ( "panel", Debug.Theme.takesTheBackground ( new JPanel() ) );

		assertFalse ( "AWT text field", Debug.Theme.takesTheBackground ( new TextField ( "E:\\OPM" ) ) );
		assertFalse ( "AWT choice", Debug.Theme.takesTheBackground ( new Choice() ) );
		assertFalse ( "AWT button", Debug.Theme.takesTheBackground ( new Button ( "Browse..." ) ) );
		assertFalse ( "Swing button", Debug.Theme.takesTheBackground ( new JButton ( "Scan" ) ) );
		assertFalse ( "text area", Debug.Theme.takesTheBackground ( new JTextArea() ) );
	}

	/**
	 * A field the toolset coloured itself is kept in step all the same.
	 *
	 * <p>{@code Live2}'s status panel is a {@code JTextArea} painted in the frame colour. Left
	 * out of the sweep it would keep the old colour in a recoloured window, which is why the rule is "the
	 * components that carry the background, plus anything already wearing the theme".
	 */
	@Test
	public void aFieldTheToolsetPaintedItselfFollowsTheTheme () {
		JPanel content = new JPanel();
		JTextArea status = new JTextArea ( 3, 20 );
		status.setBackground ( Parameter.frameColor );
		JTextArea untouched = new JTextArea ( 3, 20 );
		Color white = untouched.getBackground();
		content.add ( status );
		content.add ( untouched );

		Debug.Theme.recolour ( content, Parameter.frameColor, Debug.Theme.THEMED_BACKGROUND );
		assertEquals ( "the status panel follows", Debug.Theme.THEMED_BACKGROUND, status.getBackground() );
		assertEquals ( "an ordinary field does not", white, untouched.getBackground() );

		Debug.Theme.recolour ( content, Debug.Theme.THEMED_BACKGROUND, Parameter.frameColor );
		assertEquals ( "and it comes back", Parameter.frameColor, status.getBackground() );
	}

	/** The cheap sweep only touches what is wearing the other theme's colour. */
	@Test
	public void healingOnlyTouchesWhatIsOutOfStep () {
		JPanel content = new JPanel();
		JLabel added = new JLabel ( "a channel row added later" );
		added.setBackground ( Parameter.frameColor );
		JLabel white = new JLabel ( "not ours" );
		white.setBackground ( Color.WHITE );
		content.add ( added );
		content.add ( white );

		Debug.Theme.healColours ( content, Parameter.frameColor, Debug.Theme.THEMED_BACKGROUND );
		assertEquals ( Debug.Theme.THEMED_BACKGROUND, added.getBackground() );
		assertEquals ( "anything else is left alone", Color.WHITE, white.getBackground() );
	}


	// ---- the scriptable switch --------------------------------------------------------

	/**
	 * {@code Debug.party_mode("on")} is the whole API, and it is absolute.
	 *
	 * <p>Forced on it does not ask the clock and does not ask whether there is a Fiji desktop -
	 * somebody who typed it into the script editor meant it. That is also what makes it
	 * checkable here.
	 */
	@Test
	public void aScriptCanForceTheThemeOnAndOffAndBack () {
		Debug.party_mode ( "on" );
		assertTrue ( "forced on", Debug.Theme.isThemedNow() );
		assertEquals ( Debug.Theme.THEMED_BACKGROUND, Debug.Theme.background() );
		assertEquals ( "and the headings with it",
				Debug.Theme.THEMED_HEADING, Debug.Theme.heading ( Color.BLACK ) );

		Debug.party_mode ( "off" );
		assertFalse ( "forced off", Debug.Theme.isThemedNow() );
		assertEquals ( Parameter.frameColor, Debug.Theme.background() );

		Debug.party_mode ( "auto" );
		assertEquals ( Debug.Theme.Mode.AUTO, Debug.Theme.getMode() );
		assertFalse ( "back to the rule, which is blue with no desktop", Debug.Theme.isThemedNow() );
	}

	/** Typed by hand, so the case and the spacing are not the user's problem. */
	@Test
	public void theRequestIsReadLeniently () {
		Debug.party_mode ( "  ON  " );
		assertEquals ( Debug.Theme.Mode.ON, Debug.Theme.getMode() );
		Debug.party_mode ( "Off" );
		assertEquals ( Debug.Theme.Mode.OFF, Debug.Theme.getMode() );
		Debug.party_mode ( null );
		assertEquals ( "null asks without changing anything", Debug.Theme.Mode.OFF, Debug.Theme.getMode() );
	}

	/** Anything else is refused rather than quietly taken for one of the three. */
	@Test
	public void anUnknownRequestIsRefused () {
		try {
			Debug.party_mode ( "yes please" );
			fail ( "should have refused" );
		} catch (IllegalArgumentException expected) {
			assertTrue ( "the message should say what is accepted: " + expected.getMessage(),
					expected.getMessage().contains ( "auto" ) );
		}
		assertEquals ( "and nothing changed", Debug.Theme.Mode.AUTO, Debug.Theme.getMode() );
	}

	/** Asking prints what is showing, and changes nothing. */
	@Test
	public void askingWithoutArgumentsOnlyReports () {
		String reported = Debug.party_mode();
		assertTrue ( "should mention the mode: " + reported, reported.contains ( "auto" ) );
		assertEquals ( Debug.Theme.Mode.AUTO, Debug.Theme.getMode() );
	}

	/**
	 * The environment report is the script switch, silently.
	 *
	 * <p>Any run of it, not only the session's first, does exactly what
	 * {@code Debug.party_mode("off")} does - so {@code getMode} reports it, {@code "on"} and
	 * {@code "auto"} both undo it, and nothing is printed or logged on the way.
	 */
	@Test
	public void theEnvironmentReportSwitchesTheModeOff () {
		Debug.party_mode ( "on" );
		assertEquals ( Debug.Theme.Mode.ON, Debug.Theme.getMode() );

		Debug.Theme.commandStarted ( Debug.Theme.ENVIRONMENT_REPORT );
		assertEquals ( "the report is the same switch", Debug.Theme.Mode.OFF, Debug.Theme.getMode() );
		assertFalse ( Debug.Theme.isThemedNow() );
		assertFalse ( "and it is not the schedule's flag, which is the OFF_KEY preference",
				Debug.Theme.schedule().disabled() );

		Debug.party_mode ( "auto" );
		assertEquals ( "auto undoes it, because it is one switch", Debug.Theme.Mode.AUTO, Debug.Theme.getMode() );

		// Not only the first command of the session: a second run switches it off again.
		Debug.Theme.commandStarted ( "Batch Processing > Deskew" );
		Debug.Theme.commandStarted ( Debug.Theme.ENVIRONMENT_REPORT );
		assertEquals ( Debug.Theme.Mode.OFF, Debug.Theme.getMode() );
	}


	// ---- the rim ---------------------------------------------------------------------

	/**
	 * The rim draws in the margin and nowhere else.
	 *
	 * <p>{@code Debug.Rim} keeps a whole image of the client area but copies out only the four
	 * margin strips, which is what lets it be painted over a live dialog without covering a
	 * field. The sentinel fill is how that is checked: whatever is inside the margin has to
	 * come back untouched.
	 */
	@Test
	public void theRimPaintsTheMarginAndLeavesTheInsideUntouched () {
		int w = 220;
		int h = 160;
		int margin = Debug.Theme.RIM_MARGIN;
		Debug.Rim rim = new Debug.Rim();
		rim.layout ( w, h, margin, 1d, Debug.Theme.THEMED_BACKGROUND );
		rim.render ( 0.4d );

		BufferedImage canvas = new BufferedImage ( w, h, BufferedImage.TYPE_INT_RGB );
		Graphics2D g = canvas.createGraphics();
		try {
			g.setColor ( Color.BLACK );			// the sentinel: nothing inside may be drawn over
			g.fillRect ( 0, 0, w, h );
			rim.paintOnto ( g, 0, 0 );
		} finally {
			g.dispose();
		}

		assertEquals ( "the middle of the dialog is not drawn on",
				Color.BLACK.getRGB(), canvas.getRGB ( w / 2, h / 2 ) );
		assertEquals ( "nor a row just inside the margin",
				Color.BLACK.getRGB(), canvas.getRGB ( w / 2, margin + 2 ) );

		// the tube's centre line runs at half its thickness in from the edge
		int centre = (int) ( Debug.Rim.RIM / 2d );
		int top = canvas.getRGB ( w / 2, centre );
		assertNotEquals ( "the tube is drawn along the top edge",
				Color.BLACK.getRGB(), top );
		assertNotEquals ( "and it is not just the background",
				Debug.Theme.THEMED_BACKGROUND.getRGB(), top );
		assertNotEquals ( "nor down the left edge",
				Debug.Theme.THEMED_BACKGROUND.getRGB(), canvas.getRGB ( centre, h / 2 ) );
	}

	/** A second layout of the same size is a no-op, which is what makes 20 fps affordable. */
	@Test
	public void relayingOutTheSameWindowCostsNothing () {
		Debug.Rim rim = new Debug.Rim();
		rim.layout ( 300, 200, Debug.Theme.RIM_MARGIN, 1d, Debug.Theme.THEMED_BACKGROUND );
		rim.render ( 0d );
		BufferedImage first = snapshot ( rim, 300, 200 );

		rim.layout ( 300, 200, Debug.Theme.RIM_MARGIN, 1d, Debug.Theme.THEMED_BACKGROUND );
		rim.render ( 0d );
		BufferedImage again = snapshot ( rim, 300, 200 );

		for (int y = 0; y < 200; y += 7)
			for (int x = 0; x < 300; x += 7)
				assertEquals ( "x=" + x + " y=" + y,
						first.getRGB ( x, y ), again.getRGB ( x, y ) );
	}

	private static BufferedImage snapshot (Debug.Rim rim, int w, int h) {
		BufferedImage canvas = new BufferedImage ( w, h, BufferedImage.TYPE_INT_RGB );
		Graphics2D g = canvas.createGraphics();
		try { rim.paintOnto ( g, 0, 0 ); } finally { g.dispose(); }
		return canvas;
	}

	/**
	 * The Swing path end to end: the border draws the tube on the padding, over nothing else.
	 *
	 * <p>{@link Debug.Theme#reserveRim} is what a Swing window gets instead of the
	 * {@code GenericDialog} subclasses, and this is the whole of it - the border laid on the
	 * component, handed the component's full bounds, drawing inside the padding it reserved.
	 * A child sits in the middle of the sentinel fill to stand for the form underneath.
	 */
	@Test
	public void theRimBorderDrawsOnThePaddingAndNotOnTheForm () {
		JPanel content = new JPanel();
		content.setBackground ( Debug.Theme.THEMED_BACKGROUND );
		Debug.Theme.reserveRim ( content );
		content.setSize ( 240, 170 );

		Debug.Theme.RimBorder border = Debug.Theme.rimBorderOf ( content );
		assertNotNull ( "reserveRim should have installed the rim border", border );

		BufferedImage canvas = new BufferedImage ( 240, 170, BufferedImage.TYPE_INT_RGB );
		Graphics2D g = canvas.createGraphics();
		try {
			g.setColor ( Color.BLACK );
			g.fillRect ( 0, 0, 240, 170 );
			border.paintRim ( content, g, 0, 0, 240, 170 );
		} finally {
			g.dispose();
		}

		int centre = (int) ( Debug.Rim.RIM / 2d );
		assertNotEquals ( "the tube is on the top padding",
				Color.BLACK.getRGB(), canvas.getRGB ( 120, centre ) );
		assertNotEquals ( "and on the bottom padding",
				Color.BLACK.getRGB(), canvas.getRGB ( 120, 169 - centre ) );
		assertEquals ( "the form itself is untouched",
				Color.BLACK.getRGB(), canvas.getRGB ( 120, 85 ) );
		assertEquals ( "including the first row inside the margin",
				Color.BLACK.getRGB(), canvas.getRGB ( 120, Debug.Theme.RIM_MARGIN + 1 ) );
	}

	/** Belt and braces: the reserved border really is the compound the theme expects. */
	@Test
	public void theReservedBorderKeepsTheComponentsOwnBorderInside () {
		JPanel content = new JPanel();
		Border own = BorderFactory.createTitledBorder ( "Dataset metadata" );
		content.setBorder ( own );
		Debug.Theme.reserveRim ( content );
		assertTrue ( "still a compound border",
				content.getBorder() instanceof javax.swing.border.CompoundBorder );
		Insets insets = content.getBorder().getBorderInsets ( content );
		assertTrue ( "at least the margin on every side", insets.top >= Debug.Theme.RIM_MARGIN
				&& insets.left >= Debug.Theme.RIM_MARGIN && insets.bottom >= Debug.Theme.RIM_MARGIN
				&& insets.right >= Debug.Theme.RIM_MARGIN );
	}
}
