package de.embl.iclm;

import java.awt.AWTEvent;
import java.awt.Button;
import java.awt.Checkbox;
import java.awt.Choice;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Scrollbar;
import java.awt.TextComponent;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.lang.ref.WeakReference;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JProgressBar;
import javax.swing.JScrollBar;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;
import javax.swing.text.JTextComponent;

import ij.IJ;
import ij.Prefs;

/**		The lunch-time party theme - one decision, every OPM window
 * <p>	An easter egg, and deliberately a quiet one. When it is on, every OPM dialog and frame
 * <br>	turns from the toolset's light blue ({@link Parameter#frameColor}) to pink-purple and a
 * <br>	rainbow tube runs counter-clockwise round its rim ({@link PartyRim}). Nothing else
 * <br>	changes: no control appears, nothing is written to the Log, no result differs, and the
 * <br>	rim is painted into a margin every window reserves whether the theme is on or not, so no
 * <br>	field moves when it arrives and none moves back when it goes.
 *
 * <p>	<b>When it happens</b>, and this is the whole rule:
 * <ul>
 * <li>	outside office hours it is always on - Mon-Fri before {@value #OFFICE_OPENS_TEXT}, from
 *		{@value #OFFICE_CLOSES_TEXT}, over lunch from {@value #LUNCH_STARTS_TEXT} to
 *		{@value #LUNCH_ENDS_TEXT}, and all weekend;
 * <li>	during office hours each command that starts rolls a die, and on {@link #OFFICE_CHANCE}
 *		of them the theme comes on for {@link Schedule#BURST_MS} and then goes off again;
 * <li>	and once a window has been worked in for {@link Schedule#PERSISTENCE_MS} without a real
 *		break, the same burst is rolled at {@link #PERSISTENCE_CHANCE}. Somebody still typing
 *		into a dialog a quarter of an hour later has earned it.
 * </ul>
 * <p>	Both in-hours triggers are rolled, never certain, which is what keeps the thing an easter
 * <br>	egg rather than a second skin. {@code PartyScheduleTest} measures what the rule adds up
 * <br>	to over a simulated year of use and fails if it leaves the 20-30% band it is tuned for;
 * <br>	change {@link #OFFICE_CHANCE} and that test will tell you where you have landed.
 *
 * <p>	<b>A script can say so outright.</b> {@link #setMode(String)}, reached as
 * <br>	{@code Debug.party_mode("on")}, forces the theme on or off for the session and overrides
 * <br>	everything below; {@code "auto"} gives the rule back. That is the only way to see it on
 * <br>	demand, and the only way to undo the session switch below.
 *
 * <p>	<b>Two ways to turn it off, both silent.</b> Running {@code Utilities > Environment
 * <br>	report} as the <i>first</i> OPM Toolset command of a Fiji session disables it for the
 * <br>	rest of that session - the report is the command to reach for when something is wrong,
 * <br>	and nobody debugging a failed deskew wants a rainbow round the dialog. Setting the
 * <br>	{@value #OFF_KEY} ImageJ preference to {@code true} disables it for good. Neither says so
 * <br>	anywhere; a switch that announces itself is not the same switch.
 *
 * <p>	<b>The decision and the drawing are separate on purpose.</b> {@link Schedule} is pure -
 * <br>	it takes the time as an argument, holds its own {@link Random}, and knows nothing of
 * <br>	AWT - so the probability can be measured in a headless test. Everything below it is the
 * <br>	window work, and none of it runs when {@code IJ.getInstance()} is null: a headless batch
 * <br>	or a test sees the ordinary blue and no timer starts.
 *
 * @author ziqiang.huang@embl.de
 */
public final class Party {

	// ---- the look ------------------------------------------------------------------------

	/** The toolset's usual light blue, HSB 210 deg / 0.20 / 1.0. */
	public static final Color OFFICE_BACKGROUND = Parameter.frameColor;
	/** The same pale lightness, turned round the colour wheel to pink-purple (298 deg). */
	public static final Color PARTY_BACKGROUND = Color.getHSBColor ( (float) ( 298d / 360d ), 0.24f, 1.0f );
	/** Section headings while the theme is on. */
	public static final Color PARTY_HEADING = new Color ( 150, 20, 140 );

	/**
	 * Margin every OPM window keeps free for the rim, in window units.
	 *
	 * <p>A {@link ij.gui.GenericDialog} already leaves 10 px of its own (see its
	 * {@code getInsets}), so {@link PartyDialog} and {@link PartyDialogPlus} add only the
	 * remainder; a Swing window is padded up to this by {@link #reserveRim}. The padding is
	 * reserved whether the theme is on or not, which is what stops a window resizing under
	 * the hand when the theme changes.
	 */
	static final int RIM_MARGIN = 12;

	// ---- when ----------------------------------------------------------------------------

	static final String OFFICE_OPENS_TEXT = "08:00";
	static final String OFFICE_CLOSES_TEXT = "17:00";
	static final String LUNCH_STARTS_TEXT = "11:45";
	static final String LUNCH_ENDS_TEXT = "12:30";

	static final LocalTime OFFICE_OPENS = LocalTime.of ( 8, 0 );
	static final LocalTime OFFICE_CLOSES = LocalTime.of ( 17, 0 );
	static final LocalTime LUNCH_STARTS = LocalTime.of ( 11, 45 );
	static final LocalTime LUNCH_ENDS = LocalTime.of ( 12, 30 );

	/**
	 * How often a command started during office hours brings the theme on.
	 *
	 * <p>Small, and it has to be: outside office hours the theme is unconditional, and that
	 * alone accounts for most of the 20-30% the whole rule is allowed. What is left over is
	 * this. {@code PartyScheduleTest} is where the arithmetic lives.
	 */
	static final double OFFICE_CHANCE = 0.04d;

	/** How often a quarter of an hour of unbroken work in an OPM window is rewarded. */
	static final double PERSISTENCE_CHANCE = 0.5d;

	/** The command whose name, run first, turns the theme off for the session. */
	static final String ENVIRONMENT_REPORT = "Environment report";

	/** An ImageJ preference that turns the theme off for good. Nothing in the UI sets it. */
	static final String OFF_KEY = "opm.party.off";

	// ---- animation -----------------------------------------------------------------------

	/** One animation frame, in milliseconds; 20 fps is smooth enough for a slow tube. */
	private static final int FRAME_MS = 50;
	/**
	 * How often the timer wakes while the theme is off.
	 *
	 * <p>The timer runs for as long as any OPM window is open, which for the data viewer is
	 * hours, and 19 frames out of 20 of that would have nothing to draw. It drops to this
	 * between bursts and goes back to {@link #FRAME_MS} when one starts. Named apart from
	 * {@link Schedule#IDLE_MS}, which is a different idea entirely.
	 */
	private static final int IDLE_FRAME_MS = 500;
	/** How often the clock is looked at, on or off: twice a second, as in the demo script. */
	private static final int DECISION_MS = 500;

	private static final Schedule SCHEDULE = new Schedule ( new Random(), ZoneId.systemDefault() );
	private static final List<Skin> SKINS = new CopyOnWriteArrayList<Skin>();
	private static final List<Heading> HEADINGS = new CopyOnWriteArrayList<Heading>();
	private static final long STARTED_NANOS = System.nanoTime();

	/**
	 * What a script has asked for outright, on top of the rule.
	 *
	 * <p>{@code AUTO} is the shipped state and the only one the toolset ever sets itself.
	 */
	public enum Mode { AUTO, ON, OFF }

	private static volatile Mode mode = Mode.AUTO;

	private static Timer timer;
	private static AWTEventListener interactions;
	private static long lastDecision;
	/** What the registered windows are currently wearing, so a change can be noticed. */
	private static boolean applied;

	/** How long a window that was built and never shown is kept before it is forgotten. */
	private static final long GRACE_MS = 30L * 1000L;

	private Party () { /* static */ }


	// ---- the rule, without any windows in it ---------------------------------------------

	/**
	 * When the theme is on, and why - the whole decision, with the clock passed in.
	 *
	 * <p>Separate from the window work so the probability the toolset actually runs at can be
	 * measured rather than argued about: a test drives one of these with a seeded
	 * {@link Random} over a simulated year and counts.
	 */
	static final class Schedule {

		/** How long an office-hours burst lasts. */
		static final long BURST_MS = 60L * 1000L;
		/** Unbroken work in an OPM window that earns a burst. */
		static final long PERSISTENCE_MS = 15L * 60L * 1000L;
		/** A gap this long ends the streak, so "15 minutes" means worked, not left open. */
		static final long IDLE_MS = 3L * 60L * 1000L;

		private final Random random;
		private final ZoneId zone;

		private boolean disabled;
		private int commands;
		private long partyUntil;
		private long lastInteraction;
		private long streakStart;

		Schedule (Random random, ZoneId zone) {
			this.random = random;
			this.zone = zone;
		}

		/**			Mon-Fri 08:00-11:45 and 12:30-17:00 are office hours; everything else is not
		 *
		 * @param when	: local wall-clock time
		 * <p>
		 * @return		: true when the theme is unconditionally on
		 */
		static boolean outsideOfficeHours (LocalDateTime when) {
			DayOfWeek day = when.getDayOfWeek();
			if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return true;
			LocalTime time = when.toLocalTime();
			if (time.isBefore ( OFFICE_OPENS )) return true;
			if (!time.isBefore ( OFFICE_CLOSES )) return true;
			return !time.isBefore ( LUNCH_STARTS ) && time.isBefore ( LUNCH_ENDS );
		}

		boolean outsideOfficeHours (long nowMs) {
			return outsideOfficeHours ( LocalDateTime.ofInstant ( Instant.ofEpochMilli ( nowMs ), zone ) );
		}

		/** Whether the theme is on at this moment. */
		synchronized boolean party (long nowMs) {
			if (disabled) return false;
			if (outsideOfficeHours ( nowMs )) return true;
			return nowMs < partyUntil;
		}

		/**			An OPM Toolset command has been started
		 * <p>		The session switch is here: the environment report, and only as the first
		 * <br>		command of the session, turns the theme off for the rest of it.
		 *
		 * @param command	: the menu label of the command
		 * @param nowMs		: wall-clock milliseconds
		 */
		synchronized void commandStarted (String command, long nowMs) {
			if (commands == 0 && ENVIRONMENT_REPORT.equals ( command )) disabled = true;
			commands++;
			roll ( nowMs, OFFICE_CHANCE );
		}

		/**			A key or a click has landed in an OPM window
		 * <p>		Rolls only at each {@link #PERSISTENCE_MS} milestone of unbroken work, and
		 * <br>		the milestone moves to now whether the roll succeeds or not, so a long
		 * <br>		session earns at most one roll a quarter of an hour.
		 *
		 * @param nowMs		: wall-clock milliseconds
		 */
		synchronized void interaction (long nowMs) {
			if (streakStart == 0L || nowMs - lastInteraction > IDLE_MS) streakStart = nowMs;
			lastInteraction = nowMs;
			if (nowMs - streakStart < PERSISTENCE_MS) return;
			streakStart = nowMs;
			roll ( nowMs, PERSISTENCE_CHANCE );
		}

		/** Turn the theme off for this session, silently and for good. */
		synchronized void disable () {
			disabled = true;
			partyUntil = 0L;
		}

		boolean disabled () {
			return disabled;
		}

		int commands () {
			return commands;
		}

		/** A burst is only ever rolled during office hours, and never on top of one already on. */
		private void roll (long nowMs, double chance) {
			if (disabled || party ( nowMs )) return;
			if (random.nextDouble() < chance) partyUntil = nowMs + BURST_MS;
		}
	}


	// ---- what the toolset calls -----------------------------------------------------------

	/**			Note that an OPM Toolset command has started, and roll for it
	 * <p>		Called first thing by every command registered in {@code plugins.config};
	 * <br>		{@code PartyCoverageTest} fails if one forgets. Two things depend on it: the
	 * <br>		environment-report session switch, which needs to know it was first, and the
	 * <br>		office-hours roll, which is per command because that is the unit the 20-30%
	 * <br>		budget is expressed in.
	 *
	 * @param command	: the command's menu label
	 */
	public static void commandStarted (String command) {
		if (Prefs.get ( OFF_KEY, false )) SCHEDULE.disable();
		SCHEDULE.commandStarted ( command, System.currentTimeMillis() );
	}

	/**
	 * Whether the theme is on at this moment.
	 *
	 * <p>An explicit {@link Mode} is absolute, including without a Fiji desktop: somebody who
	 * asked for it in so many words gets it. {@code AUTO} - the shipped state - is false when
	 * {@code IJ.getInstance()} is null, so a headless batch and the rest of the test suite see
	 * the ordinary blue whatever the day and hour happen to be.
	 */
	public static boolean isPartyNow () {
		Mode asked = mode;
		if (asked == Mode.ON) return true;
		if (asked == Mode.OFF) return false;
		if (IJ.getInstance() == null) return false;
		return SCHEDULE.party ( System.currentTimeMillis() );
	}

	/**			Ask for the theme on, off, or back to its own rule
	 * <p>		The scriptable switch; {@code Debug.party_mode(String)} is the name it is meant
	 * <br>		to be called by, and its javadoc is the one to read. Windows already on screen
	 * <br>		change over at once rather than at the next half-second tick.
	 *
	 * @param request	: "on", "off" or "auto", in any case and with any spacing; null or empty
	 *					  asks without changing anything, and anything else is refused rather
	 *					  than quietly taken for one of them
	 * <p>
	 * @return			: what the theme is now doing, one line
	 */
	public static String setMode (String request) {
		if (request == null) return describeMode();
		String asked = request.trim().toLowerCase ( Locale.ROOT );
		if (asked.isEmpty()) return describeMode();
		Mode wanted;
		if ("on".equals ( asked ) || "true".equals ( asked )) wanted = Mode.ON;
		else if ("off".equals ( asked ) || "false".equals ( asked )) wanted = Mode.OFF;
		else if ("auto".equals ( asked )) wanted = Mode.AUTO;
		else throw new IllegalArgumentException ( "party mode: expected \"on\", \"off\" or"
				+ " \"auto\", not \"" + request + "\"" );
		mode = wanted;
		changeOver();
		return describeMode();
	}

	/** What a script has asked for, which is {@link Mode#AUTO} unless one has. */
	public static Mode getMode () {
		return mode;
	}

	/** One line for the script editor to print: what was asked for, and what is showing. */
	public static String describeMode () {
		switch (mode) {
			case ON:
				return "party mode: on - every OPM window parties until it is set back to auto";
			case OFF:
				return "party mode: off - no OPM window parties for the rest of this session";
			default:
				return "party mode: auto - the usual rule, showing "
						+ ( isPartyNow() ? "the party theme" : "office blue" ) + " right now";
		}
	}

	/**
	 * Let the windows on screen follow a change of mode now, not at the next tick.
	 *
	 * <p>Half a second is nothing while the clock is what decides, and far too long when
	 * somebody has just typed the request into the script editor and is watching.
	 */
	private static void changeOver () {
		if (SKINS.isEmpty()) return;
		if (SwingUtilities.isEventDispatchThread()) decide();
		else SwingUtilities.invokeLater ( new Runnable() {
			@Override public void run () { decide(); }
		} );
	}

	/** The background every OPM window should be wearing at this moment. */
	public static Color background () {
		return isPartyNow() ? PARTY_BACKGROUND : OFFICE_BACKGROUND;
	}

	/** The colour a section heading should be wearing at this moment. */
	public static Color heading (Color office) {
		return isPartyNow() ? PARTY_HEADING : office;
	}

	/**
	 * Remember a section heading, so it changes colour with the rest of the window.
	 *
	 * <p>Its present foreground is kept as the one to go back to: the look and feel decides
	 * what an ordinary label is drawn in, and assuming black would quietly repaint every
	 * heading on a dark theme.
	 */
	public static void rememberHeading (Component label) {
		if (label == null || IJ.getInstance() == null) return;	// nothing can change it
		HEADINGS.add ( new Heading ( label ) );
		label.setForeground ( heading ( label.getForeground() ) );
	}


	// ---- the windows ----------------------------------------------------------------------

	/**
	 * Give a Swing window the theme: the rim in its own padding, and the colour sweep.
	 *
	 * <p>{@code content} is the window's outermost Swing component - its content pane, or the
	 * panel a {@link ij.plugin.frame.PlugInFrame} adds. Call this after that component is
	 * built and <b>before</b> the window is packed: {@link #reserveRim} may add padding, and a
	 * window packed before it would come up that much too small.
	 *
	 * @param window	: the window whose rim is painted
	 * @param content	: its outermost Swing component
	 */
	public static void decorate (Window window, JComponent content) {
		if (window == null || content == null) return;
		reserveRim ( content );
		if (IJ.getInstance() == null) return;	// headless: the padding is reserved, nothing runs
		register ( new SwingSkin ( window, content ) );
	}

	/**
	 * Pad a Swing component out to {@link #RIM_MARGIN} on every side, keeping its own border.
	 *
	 * <p>The padding is permanent and deliberately independent of whether the theme is on. A
	 * rim that made room for itself would resize the window as it came and went - and
	 * {@code Live2}'s status panel is sized by its line count with nothing to spare, so it
	 * would have lost its last line to a rainbow.
	 */
	static void reserveRim (JComponent content) {
		Border own = content.getBorder();
		Insets have = own == null ? new Insets ( 0, 0, 0, 0 ) : own.getBorderInsets ( content );
		int top = Math.max ( 0, RIM_MARGIN - have.top );
		int left = Math.max ( 0, RIM_MARGIN - have.left );
		int bottom = Math.max ( 0, RIM_MARGIN - have.bottom );
		int right = Math.max ( 0, RIM_MARGIN - have.right );
		Border padded = own;
		if (top + left + bottom + right > 0) padded = BorderFactory.createCompoundBorder (
				BorderFactory.createEmptyBorder ( top, left, bottom, right ), own );
		/* The rim border has no insets of its own - the space is already there - and a
		 * CompoundBorder paints its outer border over the whole component before the inner
		 * one, which is exactly where the tube goes. */
		content.setBorder ( BorderFactory.createCompoundBorder ( new RimBorder(), padded ) );
	}

	/** A window that wears the theme. Registered while it is on screen, and no longer. */
	interface Skin {
		/** The theme has just changed: take the colours and repaint. */
		void themeChanged (boolean on);
		/** One animation frame of the rim. */
		void animate ();
		/** Keep the colours of anything added since the last look. */
		void heal (Color from, Color to);
		/** False once the window is gone, so the registry can forget it. */
		boolean alive ();
		/** The window, for the interaction listener. */
		Window window ();
	}

	/** Start wearing the theme, and start the shared timer if this is the first window. */
	static void register (Skin skin) {
		if (skin == null || IJ.getInstance() == null) return;
		SKINS.add ( skin );
		skin.themeChanged ( isPartyNow() );
		start();
	}

	static void unregister (Skin skin) {
		SKINS.remove ( skin );
	}

	/** Seconds since the toolset was loaded, so every rim on screen runs in phase. */
	static double seconds () {
		return ( System.nanoTime() - STARTED_NANOS ) / 1.0e9d;
	}

	private static synchronized void start () {
		if (timer != null) return;
		applied = isPartyNow();
		/* A Swing timer, on the shared daemon TimerQueue: it cannot hold the JVM open, which
		 * is the MemoryMonitor trap Shutdown's notes are about. */
		timer = new Timer ( FRAME_MS, new ActionListener() {
			@Override public void actionPerformed (ActionEvent event) { tick(); }
		} );
		timer.start();
		installInteractionListener();
	}

	private static synchronized void stop () {
		if (timer != null) { timer.stop(); timer = null; }
		if (interactions != null) {
			try { Toolkit.getDefaultToolkit().removeAWTEventListener ( interactions ); }
			catch (Exception ignored) { /* nothing left to remove */ }
			interactions = null;
		}
	}

	/** One frame: forget dead windows, look at the clock now and then, and move the rim. */
	private static void tick () {
		prune();
		if (SKINS.isEmpty()) { stop(); return; }
		long now = System.currentTimeMillis();
		if (now - lastDecision >= DECISION_MS) { lastDecision = now; decide(); }
		if (!applied) return;
		for (Skin skin : SKINS) skin.animate();
	}

	/** Look at the clock, switch if it has changed, and keep stragglers in step either way. */
	private static void decide () {
		boolean wanted = isPartyNow();
		if (timer != null) timer.setDelay ( wanted ? FRAME_MS : IDLE_FRAME_MS );
		Color from = wanted ? OFFICE_BACKGROUND : PARTY_BACKGROUND;
		Color to = wanted ? PARTY_BACKGROUND : OFFICE_BACKGROUND;
		if (wanted != applied) {
			applied = wanted;
			for (Skin skin : SKINS) skin.themeChanged ( wanted );
			for (Heading heading : HEADINGS) heading.apply ( wanted );
			return;
		}
		/* Rows are added to some of these windows long after they are built - a channel row,
		 * a status line - and they are built with Parameter.frameColor whatever the theme is
		 * doing. Repainting only what is out of step keeps that cheap and quiet. */
		for (Skin skin : SKINS) skin.heal ( from, to );
	}

	private static void prune () {
		for (Skin skin : SKINS) if (!skin.alive()) SKINS.remove ( skin );
		List<Heading> collected = null;
		for (Heading heading : HEADINGS) if (heading.gone()) {
			if (collected == null) collected = new ArrayList<Heading>();
			collected.add ( heading );
		}
		if (collected != null) HEADINGS.removeAll ( collected );
	}

	/**
	 * Watch for keys and clicks in OPM windows, for the persistence trigger.
	 *
	 * <p>One listener on the toolkit rather than one per control: the controls of these
	 * windows are added, hidden and replaced as the dialogs change shape, and a listener per
	 * control would have to follow all of it. The filter is an identity check against the
	 * windows already registered, so events from the rest of Fiji cost a walk up the parent
	 * chain and nothing else.
	 */
	private static void installInteractionListener () {
		if (interactions != null) return;
		interactions = new AWTEventListener() {
			@Override public void eventDispatched (AWTEvent event) {
				int id = event.getID();
				if (id != KeyEvent.KEY_PRESSED && id != MouseEvent.MOUSE_PRESSED
						&& id != MouseEvent.MOUSE_WHEEL) return;
				if (!(event.getSource() instanceof Component)) return;
				Window window = windowOf ( (Component) event.getSource() );
				if (window == null) return;
				for (Skin skin : SKINS) if (skin.window() == window) {
					SCHEDULE.interaction ( System.currentTimeMillis() );
					return;
				}
			}
		};
		try {
			Toolkit.getDefaultToolkit().addAWTEventListener ( interactions,
					AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK
					| AWTEvent.MOUSE_WHEEL_EVENT_MASK );
		} catch (Exception denied) {
			interactions = null;	// a security manager forbids it; the other triggers still work
		}
	}

	private static Window windowOf (Component component) {
		for (Component c = component; c != null; c = c.getParent())
			if (c instanceof Window) return (Window) c;
		return null;
	}


	// ---- colours --------------------------------------------------------------------------

	/**
	 * Repaint a window's tree in {@code to}, leaving the fields and buttons alone.
	 *
	 * <p>Labels, checkboxes and panels are what carry the background; an entry field, a
	 * button, a list or a scrollbar has a look of its own that a theme has no business in.
	 * The exception is a field the toolset coloured itself - {@code Live2}'s status area is a
	 * {@code JTextArea} painted in the frame colour - which is recognised by its present
	 * background and kept in step.
	 *
	 * <p>Set unconditionally rather than only where it differs, because an AWT child with no
	 * background of its own already reports the parent's new one while its peer is still
	 * drawn in the old.
	 */
	static void recolour (Container parent, Color from, Color to) {
		for (Component child : parent.getComponents()) {
			Color own = child.getBackground();
			if (takesTheBackground ( child ) || from.equals ( own ) || to.equals ( own )) {
				child.setBackground ( to );
				child.repaint();
			}
			if (child instanceof Container) recolour ( (Container) child, from, to );
		}
	}

	/** The cheap sweep: only what is still wearing the other theme's colour. */
	static void healColours (Container parent, Color from, Color to) {
		for (Component child : parent.getComponents()) {
			if (from.equals ( child.getBackground() )) {
				child.setBackground ( to );
				child.repaint();
			}
			if (child instanceof Container) healColours ( (Container) child, from, to );
		}
	}

	/**
	 * Whether a component is one the theme colours in.
	 *
	 * <p>Labels, check boxes and panels carry the background; an entry field, a push button, a
	 * list, a table or a scrollbar has a look of its own. A check box is an
	 * {@link AbstractButton} to Swing and not a button at all to AWT, and it takes the
	 * background in both: a check box with the look and feel's grey behind it in a coloured
	 * form is exactly the ragged edge the shared background exists to remove.
	 */
	static boolean takesTheBackground (Component c) {
		if (c instanceof Checkbox || c instanceof javax.swing.JCheckBox
				|| c instanceof javax.swing.JRadioButton) return true;
		return !( c instanceof TextComponent || c instanceof Choice || c instanceof Button
				|| c instanceof java.awt.List || c instanceof Scrollbar
				|| c instanceof JTextComponent || c instanceof AbstractButton
				|| c instanceof javax.swing.JComboBox || c instanceof JList
				|| c instanceof JScrollBar || c instanceof JSpinner || c instanceof JTable
				|| c instanceof JProgressBar || c instanceof JSlider );
	}


	// ---- implementations ------------------------------------------------------------------

	/**
	 * Whether a window is still worth animating.
	 *
	 * <p>Not simply {@code isDisplayable()}: a dialog is registered in its constructor and is
	 * not displayable until it is shown, so the first tick would drop it and it would come up
	 * with no rim. It is therefore kept until it has been on screen once, and then only while
	 * it still is - with {@link Party#GRACE_MS} as the bound, for the dialog that is built and
	 * never shown at all, as happens when a macro supplies the options.
	 */
	private static final class Liveness {
		private final long created = System.currentTimeMillis();
		private boolean shown;

		boolean alive (Window window) {
			if (window.isDisplayable()) { shown = true; return true; }
			return !shown && System.currentTimeMillis() - created < GRACE_MS;
		}
	}

	/** A heading label and the colour it is drawn in when the theme is off. */
	private static final class Heading {
		private final WeakReference<Component> label;
		private final Color office;

		Heading (Component label) {
			this.label = new WeakReference<Component> ( label );
			Color own = label.getForeground();
			this.office = own == null ? Color.BLACK : own;
		}

		void apply (boolean party) {
			Component component = label.get();
			if (component == null) return;
			component.setForeground ( party ? PARTY_HEADING : office );
			component.repaint();
		}

		/** True once the label has been collected, so the entry can go. */
		boolean gone () {
			return label.get() == null;
		}
	}

	/**
	 * The rim, as a border with no insets of its own.
	 *
	 * <p>A Swing border is painted after the component's background and before its children,
	 * so the tube sits on the padding {@link #reserveRim} set aside and under nothing. The
	 * animation repaints the four margin strips only, which is why the children never come
	 * into it.
	 */
	static final class RimBorder extends AbstractBorder {

		private static final long serialVersionUID = 1L;

		private final PartyRim rim = new PartyRim();

		@Override
		public void paintBorder (Component c, Graphics g, int x, int y, int width, int height) {
			if (!isPartyNow()) return;
			paintRim ( c, g, x, y, width, height );
		}

		/**			Draw the tube, whatever the clock says
		 * <p>		The decision is in {@link #paintBorder}; this is the drawing on its own, so
		 * <br>		that it can be checked without a Fiji desktop to ask.
		 */
		void paintRim (Component c, Graphics g, int x, int y, int width, int height) {
			if (width <= 0 || height <= 0) return;
			double scale = 1d;
			if (g instanceof Graphics2D)
				scale = Math.max ( 1d, ( (Graphics2D) g ).getTransform().getScaleX() );
			Color behind = c.getBackground();
			rim.layout ( width, height, RIM_MARGIN, scale,
					behind == null ? PARTY_BACKGROUND : behind );
			rim.render ( seconds() );
			rim.paintOnto ( g, x, y );
		}

		@Override
		public Insets getBorderInsets (Component c) {
			return new Insets ( 0, 0, 0, 0 );
		}

		@Override
		public Insets getBorderInsets (Component c, Insets insets) {
			insets.set ( 0, 0, 0, 0 );
			return insets;
		}

		@Override
		public boolean isBorderOpaque () {
			return false;
		}

		void reset () {
			rim.reset();
		}
	}

	/** A Swing window: the rim is a border on its outermost component. */
	private static final class SwingSkin implements Skin {

		private final Window window;
		private final JComponent content;
		private final Liveness liveness = new Liveness();

		SwingSkin (Window window, JComponent content) {
			this.window = window;
			this.content = content;
		}

		@Override
		public void themeChanged (boolean on) {
			Color from = on ? OFFICE_BACKGROUND : PARTY_BACKGROUND;
			Color to = on ? PARTY_BACKGROUND : OFFICE_BACKGROUND;
			window.setBackground ( to );
			recolour ( window, from, to );
			RimBorder border = rimBorderOf ( content );
			if (border != null) border.reset();
			content.repaint();
		}

		@Override
		public void animate () {
			if (!content.isShowing()) return;
			int w = content.getWidth();
			int h = content.getHeight();
			int m = Math.min ( RIM_MARGIN, Math.min ( w, h ) );
			if (m <= 0) return;
			/* paintImmediately rather than repaint: the RepaintManager unions the four strips
			 * into the whole component, and would then repaint every row of the form thirty
			 * times a second. A strip on its own holds no children, so this paints the
			 * background and the rim and stops. */
			try {
				content.paintImmediately ( 0, 0, w, m );
				content.paintImmediately ( 0, h - m, w, m );
				content.paintImmediately ( 0, m, m, h - 2 * m );
				content.paintImmediately ( w - m, m, m, h - 2 * m );
			} catch (RuntimeException painting) {
				content.repaint();	// a window caught mid-rebuild; the next frame will do
			}
		}

		@Override
		public void heal (Color from, Color to) {
			healColours ( window, from, to );
		}

		@Override
		public boolean alive () {
			return liveness.alive ( window );
		}

		@Override
		public Window window () {
			return window;
		}

	}


	// ---- for the dialog subclasses --------------------------------------------------------

	/**
	 * The rim of a {@link ij.gui.GenericDialog}, which has to paint itself.
	 *
	 * <p>An AWT dialog fills its background in {@code paint} and nowhere else, so the rim
	 * needs the dialog's own {@code paint} - which is why {@link PartyDialog} and
	 * {@link PartyDialogPlus} exist at all rather than a call on the dialogs already there.
	 * Everything else about them is delegated here so the two do not drift apart.
	 */
	static final class DialogRim implements Skin {

		/** {@code GenericDialog.getInsets()} adds this much round the frame's own insets. */
		static final int GENERIC_DIALOG_MARGIN = 10;
		/** What the dialog has to add on top of that to reach {@link Party#RIM_MARGIN}. */
		static final int EXTRA = RIM_MARGIN - GENERIC_DIALOG_MARGIN;

		private final Window dialog;
		private final PartyRim rim = new PartyRim();
		private final Liveness liveness = new Liveness();
		private boolean on;

		DialogRim (Window dialog) {
			this.dialog = dialog;
		}

		/** Whether the rim is being drawn - the dialog's {@code update} has to know. */
		boolean on () {
			return on;
		}

		/** Room for the tube: the caller's own insets, plus {@link #EXTRA}. */
		Insets pad (Insets base) {
			return new Insets ( base.top + EXTRA, base.left + EXTRA,
					base.bottom + EXTRA, base.right + EXTRA );
		}

		/**
		 * The window's drawable area, in window coordinates.
		 *
		 * @param base	: what {@code GenericDialog.getInsets()} returns - the frame's own
		 *				  insets plus its 10 px margin, and without our {@link #EXTRA}
		 */
		private java.awt.Rectangle clientArea (Insets base) {
			int left = base.left - GENERIC_DIALOG_MARGIN;
			int top = base.top - GENERIC_DIALOG_MARGIN;
			int right = base.right - GENERIC_DIALOG_MARGIN;
			int bottom = base.bottom - GENERIC_DIALOG_MARGIN;
			return new java.awt.Rectangle ( left, top,
					dialog.getWidth() - left - right, dialog.getHeight() - top - bottom );
		}

		/** Paint the rim over the dialog's margin. Called from the dialog's {@code paint}. */
		void paint (Graphics g, Insets base) {
			if (!on) return;
			java.awt.Rectangle client = clientArea ( base );
			if (client.width <= 0 || client.height <= 0) return;
			double scale = 1d;
			if (g instanceof Graphics2D)
				scale = Math.max ( 1d, ( (Graphics2D) g ).getTransform().getScaleX() );
			rim.layout ( client.width, client.height, RIM_MARGIN, scale, dialog.getBackground() );
			rim.render ( seconds() );
			rim.paintOnto ( g, client.x, client.y );
		}

		@Override
		public void themeChanged (boolean party) {
			on = party;
			Color from = party ? OFFICE_BACKGROUND : PARTY_BACKGROUND;
			Color to = party ? PARTY_BACKGROUND : OFFICE_BACKGROUND;
			dialog.setBackground ( to );
			recolour ( dialog, from, to );
			rim.reset();
			dialog.repaint();
		}

		@Override
		public void animate () {
			if (!on || !dialog.isShowing()) return;
			/* Only the margin, and the dialog's update() is overridden not to clear first;
			 * repainting the whole dialog would flicker every field thirty times a second. */
			Insets base = dialog.getInsets();
			java.awt.Rectangle client = clientArea ( new Insets ( base.top - EXTRA,
					base.left - EXTRA, base.bottom - EXTRA, base.right - EXTRA ) );
			dialog.repaint ( client.x, client.y, client.width, client.height );
		}

		@Override
		public void heal (Color from, Color to) {
			healColours ( dialog, from, to );
		}

		@Override
		public boolean alive () {
			return liveness.alive ( dialog );
		}

		@Override
		public Window window () {
			return dialog;
		}
	}


	// ---- what the tests reach for ---------------------------------------------------------

	/** The live schedule, for the coverage test; the simulation builds its own. */
	static Schedule schedule () {
		return SCHEDULE;
	}

	/** The rim border {@link #reserveRim} put on a component, or null if it has none. */
	static RimBorder rimBorderOf (JComponent content) {
		Border border = content.getBorder();
		if (border instanceof javax.swing.border.CompoundBorder)
			border = ( (javax.swing.border.CompoundBorder) border ).getOutsideBorder();
		return border instanceof RimBorder ? (RimBorder) border : null;
	}

	/** Drop every registered window and any request, so a test can start from a known state. */
	static void forget () {
		SKINS.clear();
		HEADINGS.clear();
		mode = Mode.AUTO;
		stop();
	}
}
