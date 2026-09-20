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
 * What the party theme touches, and what it leaves exactly as it was.
 *
 * <p>Two things here matter more than the look. The first is that <b>nothing changes without a
 * Fiji desktop</b>: a headless batch, a conversion or any of the other tests in this suite must
 * see the ordinary blue, because a background that depends on the hour would make every other
 * test a coin toss. The second is that <b>the rim costs no layout</b>: the space it needs is
 * reserved when the window is built and kept whether the theme is on or off, so a window cannot
 * resize under the hand when the clock passes five.
 */
public class PartyThemeTest {

	/**
	 * Give the mode back, whatever a test asked for.
	 *
	 * <p>{@link Party} is static, and surefire runs the suite in one JVM: a forced mode left
	 * behind would follow every test that ran after it into a different answer.
	 */
	@After
	public void backToAuto () {
		Party.forget();
	}

	/** With no ImageJ instance the theme is off, whatever the day and hour happen to be. */
	@Test
	public void withoutAFijiDesktopNothingIsEverThePartyColour () {
		assertFalse ( "a headless run must not party", Party.isPartyNow() );
		assertEquals ( "and the shared background is the usual one",
				Parameter.frameColor, Party.background() );
		assertEquals ( "including for a section heading",
				Color.BLACK, Party.heading ( Color.BLACK ) );
	}

	/** The margin is wide enough for the tube, in both kinds of window. */
	@Test
	public void theReservedMarginFitsTheTube () {
		assertTrue ( "the rim has to fit in the margin",
				Party.RIM_MARGIN >= (int) Math.ceil ( PartyRim.RIM ) );
		assertEquals ( "a GenericDialog's own 10 px plus what the subclass adds",
				Party.RIM_MARGIN,
				Party.DialogRim.GENERIC_DIALOG_MARGIN + Party.DialogRim.EXTRA );
	}

	/** A Swing component with no border of its own is padded out to the full margin. */
	@Test
	public void reservingTheRimPadsAComponentThatHasNoPadding () {
		JPanel content = new JPanel();
		Party.reserveRim ( content );
		Insets insets = content.getBorder().getBorderInsets ( content );
		assertEquals ( "top", Party.RIM_MARGIN, insets.top );
		assertEquals ( "left", Party.RIM_MARGIN, insets.left );
		assertEquals ( "bottom", Party.RIM_MARGIN, insets.bottom );
		assertEquals ( "right", Party.RIM_MARGIN, insets.right );
	}

	/** And one that already has more than enough keeps exactly what it had. */
	@Test
	public void reservingTheRimLeavesAWiderPaddingAlone () {
		JPanel content = new JPanel();
		content.setBorder ( BorderFactory.createEmptyBorder ( 20, 20, 20, 20 ) );
		Party.reserveRim ( content );
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
		Party.reserveRim ( decorated );
		Insets after = decorated.getBorder().getBorderInsets ( decorated );

		assertEquals ( "the rim adds no inset", before.top, after.top );
		assertEquals ( before.left, after.left );
	}

	/** Labels, check boxes and panels carry the background; fields and buttons do not. */
	@Test
	public void onlyTheComponentsThatCarryTheBackgroundAreRecoloured () {
		assertTrue ( "AWT label", Party.takesTheBackground ( new Label ( "save to" ) ) );
		assertTrue ( "AWT checkbox", Party.takesTheBackground ( new Checkbox ( "along Z" ) ) );
		assertTrue ( "Swing label", Party.takesTheBackground ( new JLabel ( "save to" ) ) );
		assertTrue ( "Swing checkbox", Party.takesTheBackground ( new JCheckBox ( "along Z" ) ) );
		assertTrue ( "panel", Party.takesTheBackground ( new JPanel() ) );

		assertFalse ( "AWT text field", Party.takesTheBackground ( new TextField ( "E:\\OPM" ) ) );
		assertFalse ( "AWT choice", Party.takesTheBackground ( new Choice() ) );
		assertFalse ( "AWT button", Party.takesTheBackground ( new Button ( "Browse..." ) ) );
		assertFalse ( "Swing button", Party.takesTheBackground ( new JButton ( "Scan" ) ) );
		assertFalse ( "text area", Party.takesTheBackground ( new JTextArea() ) );
	}

	/**
	 * A field the toolset coloured itself is kept in step all the same.
	 *
	 * <p>{@code Live2}'s status panel is a {@code JTextArea} painted in the frame colour. Left
	 * out of the sweep it would stay blue in a pink window, which is why the rule is "the
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

		Party.recolour ( content, Parameter.frameColor, Party.PARTY_BACKGROUND );
		assertEquals ( "the status panel follows", Party.PARTY_BACKGROUND, status.getBackground() );
		assertEquals ( "an ordinary field does not", white, untouched.getBackground() );

		Party.recolour ( content, Party.PARTY_BACKGROUND, Parameter.frameColor );
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

		Party.healColours ( content, Parameter.frameColor, Party.PARTY_BACKGROUND );
		assertEquals ( Party.PARTY_BACKGROUND, added.getBackground() );
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
		assertTrue ( "forced on", Party.isPartyNow() );
		assertEquals ( Party.PARTY_BACKGROUND, Party.background() );
		assertEquals ( "and the headings with it",
				Party.PARTY_HEADING, Party.heading ( Color.BLACK ) );

		Debug.party_mode ( "off" );
		assertFalse ( "forced off", Party.isPartyNow() );
		assertEquals ( Parameter.frameColor, Party.background() );

		Debug.party_mode ( "auto" );
		assertEquals ( Party.Mode.AUTO, Party.getMode() );
		assertFalse ( "back to the rule, which is blue with no desktop", Party.isPartyNow() );
	}

	/** Typed by hand, so the case and the spacing are not the user's problem. */
	@Test
	public void theRequestIsReadLeniently () {
		Debug.party_mode ( "  ON  " );
		assertEquals ( Party.Mode.ON, Party.getMode() );
		Debug.party_mode ( "Off" );
		assertEquals ( Party.Mode.OFF, Party.getMode() );
		Debug.party_mode ( null );
		assertEquals ( "null asks without changing anything", Party.Mode.OFF, Party.getMode() );
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
		assertEquals ( "and nothing changed", Party.Mode.AUTO, Party.getMode() );
	}

	/** Asking prints what is showing, and changes nothing. */
	@Test
	public void askingWithoutArgumentsOnlyReports () {
		String reported = Debug.party_mode();
		assertTrue ( "should mention the mode: " + reported, reported.contains ( "auto" ) );
		assertEquals ( Party.Mode.AUTO, Party.getMode() );
	}

	/**
	 * A forced mode beats the session switch, which is the point of having it.
	 *
	 * <p>The environment report run first turns the theme off for the session and there is no
	 * other way back from that. {@code "auto"} is not the way back - it means "the rule", and
	 * the rule for such a session is off - so {@code "on"} has to be.
	 */
	@Test
	public void forcingItOnOverridesTheSessionSwitch () {
		Party.commandStarted ( Party.ENVIRONMENT_REPORT );		// the live schedule, now disabled
		assertTrue ( "the session switch should have fired",
				Party.schedule().disabled() );

		Debug.party_mode ( "on" );
		assertTrue ( "an explicit request wins", Party.isPartyNow() );

		Debug.party_mode ( "auto" );
		assertFalse ( "and auto hands it back to a rule that is off for this session",
				Party.isPartyNow() );
	}


	// ---- the rim ---------------------------------------------------------------------

	/**
	 * The rim draws in the margin and nowhere else.
	 *
	 * <p>{@code PartyRim} keeps a whole image of the client area but copies out only the four
	 * margin strips, which is what lets it be painted over a live dialog without covering a
	 * field. The sentinel fill is how that is checked: whatever is inside the margin has to
	 * come back untouched.
	 */
	@Test
	public void theRimPaintsTheMarginAndLeavesTheInsideUntouched () {
		int w = 220;
		int h = 160;
		int margin = Party.RIM_MARGIN;
		PartyRim rim = new PartyRim();
		rim.layout ( w, h, margin, 1d, Party.PARTY_BACKGROUND );
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
		int centre = (int) ( PartyRim.RIM / 2d );
		int top = canvas.getRGB ( w / 2, centre );
		assertNotEquals ( "the tube is drawn along the top edge",
				Color.BLACK.getRGB(), top );
		assertNotEquals ( "and it is not just the background",
				Party.PARTY_BACKGROUND.getRGB(), top );
		assertNotEquals ( "nor down the left edge",
				Party.PARTY_BACKGROUND.getRGB(), canvas.getRGB ( centre, h / 2 ) );
	}

	/** A second layout of the same size is a no-op, which is what makes 20 fps affordable. */
	@Test
	public void relayingOutTheSameWindowCostsNothing () {
		PartyRim rim = new PartyRim();
		rim.layout ( 300, 200, Party.RIM_MARGIN, 1d, Party.PARTY_BACKGROUND );
		rim.render ( 0d );
		BufferedImage first = snapshot ( rim, 300, 200 );

		rim.layout ( 300, 200, Party.RIM_MARGIN, 1d, Party.PARTY_BACKGROUND );
		rim.render ( 0d );
		BufferedImage again = snapshot ( rim, 300, 200 );

		for (int y = 0; y < 200; y += 7)
			for (int x = 0; x < 300; x += 7)
				assertEquals ( "x=" + x + " y=" + y,
						first.getRGB ( x, y ), again.getRGB ( x, y ) );
	}

	private static BufferedImage snapshot (PartyRim rim, int w, int h) {
		BufferedImage canvas = new BufferedImage ( w, h, BufferedImage.TYPE_INT_RGB );
		Graphics2D g = canvas.createGraphics();
		try { rim.paintOnto ( g, 0, 0 ); } finally { g.dispose(); }
		return canvas;
	}

	/**
	 * The Swing path end to end: the border draws the tube on the padding, over nothing else.
	 *
	 * <p>{@link Party#reserveRim} is what a Swing window gets instead of the
	 * {@code GenericDialog} subclasses, and this is the whole of it - the border laid on the
	 * component, handed the component's full bounds, drawing inside the padding it reserved.
	 * A child sits in the middle of the sentinel fill to stand for the form underneath.
	 */
	@Test
	public void theRimBorderDrawsOnThePaddingAndNotOnTheForm () {
		JPanel content = new JPanel();
		content.setBackground ( Party.PARTY_BACKGROUND );
		Party.reserveRim ( content );
		content.setSize ( 240, 170 );

		Party.RimBorder border = Party.rimBorderOf ( content );
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

		int centre = (int) ( PartyRim.RIM / 2d );
		assertNotEquals ( "the tube is on the top padding",
				Color.BLACK.getRGB(), canvas.getRGB ( 120, centre ) );
		assertNotEquals ( "and on the bottom padding",
				Color.BLACK.getRGB(), canvas.getRGB ( 120, 169 - centre ) );
		assertEquals ( "the form itself is untouched",
				Color.BLACK.getRGB(), canvas.getRGB ( 120, 85 ) );
		assertEquals ( "including the first row inside the margin",
				Color.BLACK.getRGB(), canvas.getRGB ( 120, Party.RIM_MARGIN + 1 ) );
	}

	/** Belt and braces: the reserved border really is the compound the theme expects. */
	@Test
	public void theReservedBorderKeepsTheComponentsOwnBorderInside () {
		JPanel content = new JPanel();
		Border own = BorderFactory.createTitledBorder ( "Dataset metadata" );
		content.setBorder ( own );
		Party.reserveRim ( content );
		assertTrue ( "still a compound border",
				content.getBorder() instanceof javax.swing.border.CompoundBorder );
		Insets insets = content.getBorder().getBorderInsets ( content );
		assertTrue ( "at least the margin on every side", insets.top >= Party.RIM_MARGIN
				&& insets.left >= Party.RIM_MARGIN && insets.bottom >= Party.RIM_MARGIN
				&& insets.right >= Party.RIM_MARGIN );
	}
}
