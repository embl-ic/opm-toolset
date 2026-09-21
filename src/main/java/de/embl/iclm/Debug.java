package de.embl.iclm;

import java.awt.AWTEvent;
import java.awt.BasicStroke;
import java.awt.Button;
import java.awt.Checkbox;
import java.awt.Choice;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Scrollbar;
import java.awt.TextComponent;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

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

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.ImageWindow;
import ij.gui.Roi;
import ij.plugin.PlugIn;
import ij.plugin.ZProjector;
import ij.process.ImageProcessor;
import ij.process.LUT;
import inra.ijpb.data.border.BorderManager3D;
import inra.ijpb.data.border.ConstantBorder3D;
import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;
import net.haesleinhuepf.clij2.CLIJ2;
import net.imglib2.realtransform.AffineTransform3D;

/**		Code archive and environment report
 * <p>	Two unrelated things live in this class, deliberately:
 * <p>	1, the menu command "Environment report", which reports the processing environment -
 * <br>	plugin version, Java, ImageJ, and whether a usable OpenCL device is present with how much
 * <br>	memory. That is the first question to answer when a deskew is unexpectedly slow or falls
 * <br>	back to the CPU. It goes to {@code System.out} only, which Fiji shows in
 * <br>	{@code Window > Console} and a command-line start shows in the terminal. It used to go to
 * <br>	the ImageJ Log as well, which <b>opened</b> the Log window: a window raised for five lines
 * <br>	of text, in front of whatever the user was looking at, every time they asked. It used to
 * <br>	open a parameter dialog that duplicated the Deskew Batch one and processed nothing too;
 * <br>	that is gone as well.
 * <p>	1b, the window theme: {@link Theme}, which every OPM window follows and which every
 * <br>	command tells that it started ({@link #commandStarted}); {@link Rim}, the rim it draws;
 * <br>	{@link Site}, the computers its rule runs on; and {@link #party_mode(String)}, its
 * <br>	scriptable switch. It lives here, beside the environment report that is its other
 * <br>	switch, rather than in classes of its own.
 * <p>	2, everything below the report: earlier implementations kept for reference, and pieces
 * <br>	written for a purpose that has not arrived yet. They are not called from anywhere in the
 * <br>	plugin, and they are kept on purpose - do not delete them as dead code. They still
 * <br>	compile, so they cannot rot silently against the rest of the sources.
 *
 * @author ziqiang.huang@embl.de
 */
public class Debug implements PlugIn {

	/** Line separator for the environment report. */
	private static final String NL = System.getProperty("line.separator");

	/**			Report the processing environment to the Fiji console
	 * <p>		Not to the ImageJ Log: see the class comment.
	 *
	 * @param arg	: plugin argument from plugins.config; unused
	 */
	@Override
	public void run(String arg) {
		// turns the theme off, silently, exactly as party_mode("off"); see Theme
		commandStarted ( Theme.ENVIRONMENT_REPORT );
		StringBuilder report = new StringBuilder("OPM Toolset environment report" + NL);
		report.append("  plugin      : ").append( pluginVersion() ).append(NL);
		report.append("  ImageJ      : ").append( IJ.getFullVersion() ).append(NL);
		report.append("  Java        : ").append( System.getProperty("java.version") )
				.append(" (").append( System.getProperty("os.name") ).append(")").append(NL);
		report.append("  memory      : ").append( IJ.freeMemory() ).append(NL);
		report.append("  GPU (CLIJ2) : ").append( gpuDescription() ).append(NL);
		/* System.out only. Fiji shows it in Window > Console and a terminal start shows it
		 * there; IJ.log would open the Log window for it, which is what this no longer does. */
		System.out.println( report );
	}


	/**			Turn the party theme on, off, or back to its own rule - from a script
	 * <p>		For the Fiji script editor, where there is no dialog to reach for:
	 * <pre>	import de.embl.iclm.Debug;
	 * 			Debug.party_mode("on");		// every OPM window parties, whatever the hour
	 * 			Debug.party_mode("off");	// none of them does, for the rest of the session
	 * 			Debug.party_mode("auto");	// back to the rule; this is the default</pre>
	 * <p>		Windows already on screen change over at once. The request lasts as long as the
	 * <br>		Fiji session; {@code Theme.OFF_KEY} is the preference that outlives one.
	 * <p>		{@code "on"} works on any computer. The rule {@code "auto"} gives back runs only
	 * <br>		on the computers {@link Site} accepts, and never shows the theme on
	 * <br>		any other; asking says which this one is.
	 * <p>		Running {@code Utilities > Environment report} is the same switch as
	 * <br>		{@code party_mode("off")}, set silently: the report is what you reach for when
	 * <br>		something is wrong. Either {@code "on"} or {@code "auto"} undoes it.
	 *
	 * @param mode	: "on", "off" or "auto"; null asks without changing anything
	 * <p>
	 * @return		: one line saying what the theme is now doing, for the script editor to print
	 */
	public static String party_mode (String mode) {
		return Theme.setMode ( mode );
	}

	/**			What the party theme is doing, without changing it
	 * <p>
	 * @return		: one line, as {@link #party_mode(String)} returns
	 */
	public static String party_mode () {
		return Theme.describeMode();
	}

	/**			Note that an OPM Toolset command has started
	 * <p>		Called first thing by every command registered in {@code plugins.config};
	 * <br>		{@code CommandCoverageTest} fails if one does not. See {@link Theme#commandStarted}.
	 *
	 * @param command	: the command's menu label
	 */
	static void commandStarted (String command) {
		Theme.commandStarted ( command );
	}

	/**			Give a Swing window its outer margin and the window theme
	 * <p>		Call once the window's outermost component is built and <b>before</b> it is
	 * <br>		packed; see {@link Theme#decorate}.
	 */
	static void decorate (Window window, JComponent content) {
		Theme.decorate ( window, content );
	}

	/** The background every OPM window should be wearing at this moment. */
	static Color background () {
		return Theme.background();
	}

	/** Remember a section heading, so that it changes colour with the rest of its window. */
	static void rememberHeading (Component label) {
		Theme.rememberHeading ( label );
	}

	/**			Version of the packaged plugin, as recorded in the JAR manifest
	 * <p>
	 * @return	: the implementation version, or "unknown" when running from class files
	 */
	private static String pluginVersion () {
		String version = Debug.class.getPackage() == null
				? null : Debug.class.getPackage().getImplementationVersion();
		return null == version ? "unknown (not running from the JAR)" : version;
	}

	/**			Describe the OpenCL device CLIJ2 would use, without throwing when there is none
	 * <p>
	 * @return	: device name and maximum single allocation, or why the GPU cannot be used
	 */
	private static String gpuDescription () {
		try {
			CLIJ2 clij2 = CLIJ2.getInstance();
			String device = clij2.getGPUName();
			long maxAllocation = GPU.memory_size();
			return device + ", max single allocation " + (maxAllocation / (1024L*1024L)) + " MB";
		} catch ( Throwable noDevice ) {
			// Throwable, not Exception: a missing native OpenCL library surfaces as an Error
			return "not available, processing will fall back to the CPU (" + noDevice + ")";
		}
	}


	// ==== the window theme ==================================================================

	/**		The lunch-time party theme - one decision, every OPM window
	 * <p>	An easter egg, and deliberately a quiet one. When it is on, every OPM dialog and frame
	 * <br>	turns from the toolset's light blue ({@link Parameter#frameColor}) to pink-purple and a
	 * <br>	rainbow tube runs counter-clockwise round its rim ({@link Rim}). Nothing else
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
	 * <br>	egg rather than a second skin. {@code DebugScheduleTest} measures what the rule adds up
	 * <br>	to over a simulated year of use and fails if it leaves the 20-30% band it is tuned for;
	 * <br>	change {@link #OFFICE_CHANCE} and that test will tell you where you have landed.
	 *
	 * <p>	<b>Who sees it at all</b> comes before any of that. The rule runs only on a computer
	 * <br>	{@link Site} accepts - a listed account on an EMBL network, or a listed
	 * <br>	address - and everywhere else it never shows the theme, at any hour. The computer is
	 * <br>	looked at once a session, off the event thread, when the first command starts; until the
	 * <br>	answer is in, the answer is no.
	 *
	 * <p>	<b>A script can say so outright.</b> {@link #setMode(String)}, reached as
	 * <br>	{@code Debug.party_mode("on")}, forces the theme on or off for the session and overrides
	 * <br>	everything below, the computer check included; {@code "auto"} gives the rule back.
	 *
	 * <p>	<b>Two ways to turn it off, both silent.</b> Running {@code Utilities > Environment
	 * <br>	report} does exactly what {@code Debug.party_mode("off")} does, and says nothing about
	 * <br>	it - the report is the command to reach for when something is wrong, and nobody
	 * <br>	debugging a failed deskew wants a rainbow round the dialog. Any run of it, not only the
	 * <br>	session's first, and windows already on screen change back at once rather than at the
	 * <br>	next tick. {@code "on"} and {@code "auto"} both undo it, because it is the same switch.
	 * <br>	Setting the {@value #OFF_KEY} ImageJ preference to {@code true} disables it for good.
	 * <br>	Neither says so anywhere; a switch that announces itself is not the same switch.
	 *
	 * <p>	<b>The decision and the drawing are separate on purpose.</b> {@link Schedule} is pure -
	 * <br>	it takes the time as an argument, holds its own {@link Random}, and knows nothing of
	 * <br>	AWT - so the probability can be measured in a headless test. Everything below it is the
	 * <br>	window work, and none of it runs when {@code IJ.getInstance()} is null: a headless batch
	 * <br>	or a test sees the ordinary blue and no timer starts.
	 */
	static final class Theme {

		// ---- the look ------------------------------------------------------------------------

		/** The toolset's usual light blue, HSB 210 deg / 0.20 / 1.0. */
		public static final Color OFFICE_BACKGROUND = Parameter.frameColor;
		/** The same pale lightness, turned round the colour wheel to pink-purple (298 deg). */
		public static final Color THEMED_BACKGROUND = Color.getHSBColor ( (float) ( 298d / 360d ), 0.24f, 1.0f );
		/** Section headings while the theme is on. */
		public static final Color THEMED_HEADING = new Color ( 150, 20, 140 );

		/**
		 * Margin every OPM window keeps free for the rim, in window units.
		 *
		 * <p>A {@link ij.gui.GenericDialog} already leaves 10 px of its own (see its
		 * {@code getInsets}), so {@link OpmDialog} and {@link OpmDialogPlus} add only the
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
		 * this. {@code DebugScheduleTest} is where the arithmetic lives.
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
		/** How often the clock is looked at, on or off: twice a second. */
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

		/**
		 * Whether {@link Site} accepts this computer; null until it has been asked.
		 *
		 * <p>Unknown counts as no, so a window built before the answer arrives is blue and changes
		 * over at the next tick if the answer is yes - colour only, the rim's margin is already
		 * there.
		 */
		private static volatile Boolean eligible;
		/** The one look at this computer, once it has been started. Guarded by {@code Theme.class}. */
		private static Thread eligibilityProbe;
		/**
		 * How long the first command waits for that look before it carries on regardless.
		 *
		 * <p>{@code ipconfig /all} takes about 50 ms, and a process start as much again, so this is
		 * normally the difference between a first dialog that comes up in the right colour and one
		 * that changes half a second later. The bound is for a computer where it hangs.
		 */
		private static final long ELIGIBILITY_WAIT_MS = 1000L;

		private static Timer timer;
		private static AWTEventListener interactions;
		private static long lastDecision;
		/** What the registered windows are currently wearing, so a change can be noticed. */
		private static boolean applied;

		/** How long a window that was built and never shown is kept before it is forgotten. */
		private static final long GRACE_MS = 30L * 1000L;

		private Theme () { /* static */ }


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
			synchronized boolean themed (long nowMs) {
				if (disabled) return false;
				if (outsideOfficeHours ( nowMs )) return true;
				return nowMs < partyUntil;
			}

			/**			An OPM Toolset command has been started
			 * <p>		The schedule counts commands and rolls; <em>which</em> command it was is no
			 * <br>		longer its business. The environment report's switch is
			 * <br>		{@link Theme#commandStarted(String)}, and it is the scriptable mode rather
			 * <br>		than a flag in here, so that {@code "on"} and {@code "auto"} can undo it.
			 *
			 * @param nowMs		: wall-clock milliseconds
			 */
			synchronized void commandStarted (long nowMs) {
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
				if (disabled || themed ( nowMs )) return;
				if (random.nextDouble() < chance) partyUntil = nowMs + BURST_MS;
			}
		}


		// ---- what the toolset calls -----------------------------------------------------------

		/**			Note that an OPM Toolset command has started, and roll for it
		 * <p>		Called first thing by every command registered in {@code plugins.config};
		 * <br>		{@code CommandCoverageTest} fails if one forgets. Three things depend on it: the
		 * <br>		environment report's silent switch, the office-hours roll, which is per
		 * <br>		command because that is the unit the 20-30% budget is expressed in, and the
		 * <br>		session's one look at whether this computer sees the theme at all - which the
		 * <br>		first command waits for, briefly, so its dialog comes up in the right colour.
		 * <p>		The switch is {@link Mode#OFF} itself, not a flag of its own, so the report
		 * <br>		does precisely what {@code Debug.party_mode("off")} does: absolute for the rest
		 * <br>		of the session, windows on screen back to blue at once, and undone by
		 * <br>		{@code "on"} or {@code "auto"} like any other request. Every run of the report
		 * <br>		sets it, not only the session's first - somebody reaching for it a second time
		 * <br>		wants the rainbow gone just as much.
		 *
		 * @param command	: the command's menu label
		 */
		public static void commandStarted (String command) {
			if (ENVIRONMENT_REPORT.equals ( command )) applyMode ( Mode.OFF );
			if (Prefs.get ( OFF_KEY, false )) SCHEDULE.disable();
			SCHEDULE.commandStarted ( System.currentTimeMillis() );
			if (mode == Mode.AUTO) awaitEligibility ( ELIGIBILITY_WAIT_MS );
		}

		/**
		 * Whether the theme is on at this moment.
		 *
		 * <p>An explicit {@link Mode} is absolute, including without a Fiji desktop and on a
		 * computer the rule never shows it on: somebody who asked for it in so many words gets it.
		 * {@code AUTO} - the shipped state - is false when {@code IJ.getInstance()} is null, so a
		 * headless batch and the rest of the test suite see the ordinary blue whatever the day and
		 * hour happen to be, and otherwise is {@link #ruleSays}.
		 */
		public static boolean isThemedNow () {
			Mode asked = mode;
			if (asked == Mode.ON) return true;
			if (asked == Mode.OFF) return false;
			if (IJ.getInstance() == null) return false;
			return ruleSays ( System.currentTimeMillis() );
		}

		/** The rule on its own: this computer is one it runs on, and the schedule says now. */
		static boolean ruleSays (long nowMs) {
			return Boolean.TRUE.equals ( eligible ) && SCHEDULE.themed ( nowMs );
		}

		/**			Start looking at this computer, once a session
		 * <p>		Off the event thread and off the caller's: finding the DNS suffixes can mean
		 * <br>		starting {@code ipconfig}. Headless there is nothing to decide - {@code AUTO} is
		 * <br>		blue there regardless - so nothing is started.
		 */
		private static synchronized Thread learnEligibility () {
			if (eligible != null || eligibilityProbe != null || IJ.getInstance() == null)
				return eligibilityProbe;
			eligibilityProbe = Shutdown.daemon ( new Runnable() {
				@Override public void run () {
					boolean answer = false;
					try {
						answer = Site.eligible ( Site.Facts.probe() );
					} catch (Throwable unreadable) {
						// a computer that cannot say what it is does not qualify
					}
					eligible = answer;
					changeOver();
				}
			}, "OPM-party-eligibility" );
			eligibilityProbe.start();
			return eligibilityProbe;
		}

		/** Start the look if it has not been, and give it up to {@code ms} to answer - never on the EDT. */
		private static void awaitEligibility (long ms) {
			Thread probe = learnEligibility();
			if (probe == null || eligible != null || SwingUtilities.isEventDispatchThread()) return;
			try {
				probe.join ( ms );
			} catch (InterruptedException stop) {
				Thread.currentThread().interrupt();
			}
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
			applyMode ( wanted );
			return describeMode();
		}

		/** The one place the mode changes, so the script switch and the report cannot drift apart. */
		private static void applyMode (Mode wanted) {
			mode = wanted;
			changeOver();
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
					awaitEligibility ( ELIGIBILITY_WAIT_MS );
					return "party mode: auto - the usual rule, showing "
							+ ( isThemedNow() ? "the party theme" : "office blue" ) + " right now"
							+ ( Boolean.FALSE.equals ( eligible )
									? " (this computer is not one the rule shows it on)" : "" );
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
			return isThemedNow() ? THEMED_BACKGROUND : OFFICE_BACKGROUND;
		}

		/** The colour a section heading should be wearing at this moment. */
		public static Color heading (Color office) {
			return isThemedNow() ? THEMED_HEADING : office;
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
			learnEligibility();		// normally started by the command already; this never waits
			SKINS.add ( skin );
			skin.themeChanged ( isThemedNow() );
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
			applied = isThemedNow();
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
			boolean wanted = isThemedNow();
			if (timer != null) timer.setDelay ( wanted ? FRAME_MS : IDLE_FRAME_MS );
			Color from = wanted ? OFFICE_BACKGROUND : THEMED_BACKGROUND;
			Color to = wanted ? THEMED_BACKGROUND : OFFICE_BACKGROUND;
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
		 * it still is - with {@link Theme#GRACE_MS} as the bound, for the dialog that is built and
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
				component.setForeground ( party ? THEMED_HEADING : office );
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

			private final Rim rim = new Rim();

			@Override
			public void paintBorder (Component c, Graphics g, int x, int y, int width, int height) {
				if (!isThemedNow()) return;
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
						behind == null ? THEMED_BACKGROUND : behind );
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
				Color from = on ? OFFICE_BACKGROUND : THEMED_BACKGROUND;
				Color to = on ? THEMED_BACKGROUND : OFFICE_BACKGROUND;
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
		 * needs the dialog's own {@code paint} - which is why {@link OpmDialog} and
		 * {@link OpmDialogPlus} exist at all rather than a call on the dialogs already there.
		 * Everything else about them is delegated here so the two do not drift apart.
		 */
		static final class DialogRim implements Skin {

			/** {@code GenericDialog.getInsets()} adds this much round the frame's own insets. */
			static final int GENERIC_DIALOG_MARGIN = 10;
			/** What the dialog has to add on top of that to reach {@link Theme#RIM_MARGIN}. */
			static final int EXTRA = RIM_MARGIN - GENERIC_DIALOG_MARGIN;

			private final Window dialog;
			private final Rim rim = new Rim();
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
				Color from = party ? OFFICE_BACKGROUND : THEMED_BACKGROUND;
				Color to = party ? THEMED_BACKGROUND : OFFICE_BACKGROUND;
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

		/**
		 * Take this computer's answer as given instead of looking, so a test can drive the rule on
		 * an invented computer; null forgets it. Stops a look from starting, not one under way.
		 */
		static synchronized void assumeEligible (Boolean answer) {
			eligible = answer;
			eligibilityProbe = null;
		}

		/** What is known about this computer: true, false, or null while it has not been asked. */
		static Boolean eligibility () {
			return eligible;
		}

		/** Drop every registered window and any request, so a test can start from a known state. */
		static void forget () {
			SKINS.clear();
			HEADINGS.clear();
			mode = Mode.AUTO;
			assumeEligible ( null );
			stop();
		}
	}


	/**		A rainbow tube running counter-clockwise round the rim of a window
	 * <p>	The moving part of {@link Theme}: a rounded-rectangle tube of rainbow, shaded across its
	 * <br>	width, with white glints lapping it faster than the rainbow and short-lived sparkles on
	 * <br>	its crown. It is drawn into a margin the window already has, so no field moves when it
	 * <br>	appears and none moves back when it goes.
	 *
	 * <p>	<b>The layout is computed once per window size and every frame is then a table lookup.</b>
	 * <br>	{@link #layout} walks the margin pixel by pixel and records, for each pixel of the tube,
	 * <br>	where it sits round the rim (0..1, counter-clockwise from the top), how far across the
	 * <br>	tube it is - which gives the shading and the specular stripe - and its anti-aliasing
	 * <br>	coverage at the two edges. {@link #render} then only turns a phase into a colour, which
	 * <br>	is what makes 30 frames a second affordable beside a deskew.
	 *
	 * <p>	The image is kept in <b>device</b> pixels, so the tube is as smooth on a HiDPI screen as
	 * <br>	on an ordinary one; {@link #paintOnto} scales the four margin strips back to window
	 * <br>	units. The inside of the image is never drawn and never copied out, so nothing of the
	 * <br>	dialog underneath is covered.
	 *
	 * <p>	Not thread safe, and not meant to be: every call comes from the event dispatch thread.
	 */
	static final class Rim {

		/** Tube thickness, in window units. A window needs this much margin to spare. */
		static final double RIM = 9d;
		/** Radius of the tube's centre line where it turns a corner, in window units. */
		private static final double CORNER = 12d;
		/** Rainbows visible round the rim at once. */
		private static final double CYCLES = 2d;
		/** Seconds for one rainbow to travel once round the rim. */
		private static final double LAP_SECONDS = 6d;
		/** The white glints lap faster than the rainbow, which is what reads as a polished tube. */
		private static final double GLINT_LAP_SECONDS = 2.4d;
		private static final int GLINTS = 2;
		/** Width of one glint along the tube, in device pixels. */
		private static final double GLINT_SIGMA = 16d;
		private static final double SPARKLES_PER_S = 7d;
		private static final double SPARKLE_LIFE_S = 0.7d;

		private final int[] rainbow = new int[1024];
		private final Random random = new Random();
		/** x, y, born, size - in device pixels and seconds. */
		private final List<double[]> sparkles = new ArrayList<double[]>();

		private int width = -1;			// client area, window units
		private int height = -1;
		private int margin = -1;
		private double scale = -1d;		// device pixels per window unit (HiDPI)
		private int background;

		private BufferedImage image;	// client area in device pixels; only the margins are shown
		private int[] pixels;
		private int imageWidth;
		private int imageHeight;
		private int imageMargin;
		private double perimeter;

		// one entry per pixel of the tube
		private int count;
		private int[] index;
		private double[] arc;			// 0..1 round the rim, counter-clockwise from the top
		private double[] tone;			// shading across the tube's width: darker at both edges
		private double[] shine;			// a specular stripe along the inner side of the tube
		private double[] cover;			// anti-aliasing coverage at the two edges

		private double lastSeconds = -1d;

		Rim () {
			for (int i = 0; i < rainbow.length; i++)
				rainbow[i] = Color.HSBtoRGB ( (float) ( i / (double) rainbow.length ), 0.80f, 1.0f );
		}

		/** Forget the layout, so the next frame lays it out again - after a background change. */
		void reset () {
			width = -1;
		}

		/**			Lay the tube out for this window size, or return at once when nothing changed
		 *
		 * @param w		: client width, window units
		 * @param h		: client height, window units
		 * @param m		: margin the tube is drawn into, window units
		 * @param s		: device pixels per window unit
		 * @param bg	: what the margin is filled with around the tube
		 */
		void layout (int w, int h, int m, double s, Color bg) {
			if (w == width && h == height && m == margin && s == scale && bg.getRGB() == background)
				return;
			width = w; height = h; margin = m; scale = s; background = bg.getRGB();
			imageWidth = Math.max ( 1, (int) Math.ceil ( w * s ) );
			imageHeight = Math.max ( 1, (int) Math.ceil ( h * s ) );
			imageMargin = Math.max ( 1, (int) Math.round ( m * s ) );
			image = new BufferedImage ( imageWidth, imageHeight, BufferedImage.TYPE_INT_RGB );
			pixels = ( (DataBufferInt) image.getRaster().getDataBuffer() ).getData();
			Arrays.fill ( pixels, background );
			sparkles.clear();

			double half = RIM * s / 2d;
			double r = CORNER * s;
			// the tube's centre line, in device pixels
			double cx0 = half;
			double cy0 = half;
			double cx1 = imageWidth - half;
			double cy1 = imageHeight - half;
			double ix0 = cx0 + r;
			double ix1 = cx1 - r;
			double iy0 = cy0 + r;
			double iy1 = cy1 - r;
			double lh = ix1 - ix0;			// straight run along the top and the bottom
			double lv = iy1 - iy0;			// straight run down the sides
			double lc = Math.PI * r / 2d;	// one corner
			perimeter = 2d * lh + 2d * lv + 4d * lc;

			int capacity = 2 * imageMargin * ( imageWidth + imageHeight );
			index = new int[capacity]; arc = new double[capacity]; tone = new double[capacity];
			shine = new double[capacity]; cover = new double[capacity];
			count = 0;

			for (int y = 0; y < imageHeight; y++) {
				boolean fullRow = y < imageMargin || y >= imageHeight - imageMargin;
				for (int x = 0; x < imageWidth; x++) {
					if (!fullRow && x == imageMargin && imageWidth - imageMargin > x)
						x = imageWidth - imageMargin;	// the inside is never drawn
					double px = x + 0.5d;
					double py = y + 0.5d;
					double dx = px < ix0 ? px - ix0 : ( px > ix1 ? px - ix1 : 0d );
					double dy = py < iy0 ? py - iy0 : ( py > iy1 ? py - iy1 : 0d );
					double dist;	// from the centre line, positive towards the window edge
					double along;	// arc length, counter-clockwise: along the top edge leftwards first
					if (dx != 0d && dy != 0d) {
						dist = Math.hypot ( dx, dy ) - r;
						double ax = Math.abs ( dx );
						double ay = Math.abs ( dy );
						double quarter = Math.PI / 2d;
						if (dx < 0d && dy < 0d)	along = lh + lc * Math.atan2 ( ax, ay ) / quarter;
						else if (dx < 0d)		along = lh + lc + lv + lc * Math.atan2 ( ay, ax ) / quarter;
						else if (dy > 0d)		along = 2d * lh + 2d * lc + lv + lc * Math.atan2 ( ax, ay ) / quarter;
						else					along = 2d * lh + 3d * lc + 2d * lv + lc * Math.atan2 ( ay, ax ) / quarter;
					} else {
						boolean horizontal;
						if (dx != 0d) horizontal = false;
						else if (dy != 0d) horizontal = true;
						else horizontal = Math.min ( py - cy0, cy1 - py ) <= Math.min ( px - cx0, cx1 - px );
						if (horizontal) {
							if (py - cy0 <= cy1 - py) { dist = cy0 - py; along = ix1 - px; }							// top
							else					  { dist = py - cy1; along = lh + 2d * lc + lv + ( px - ix0 ); }	// bottom
						} else {
							if (px - cx0 <= cx1 - px) { dist = cx0 - px; along = lh + lc + ( py - iy0 ); }				// left
							else					  { dist = px - cx1; along = 2d * lh + 3d * lc + lv + ( iy1 - py ); }// right
						}
					}
					double coverage = Math.min ( 1d, half + 0.5d - Math.abs ( dist ) );
					if (coverage <= 0d) continue;
					double across = Math.max ( -1d, Math.min ( 1d, dist / half ) );	// -1 inner edge, +1 outer
					index[count] = y * imageWidth + x;
					arc[count] = along / perimeter;
					tone[count] = 0.74d + 0.26d * Math.cos ( across * Math.PI / 2d );
					double stripe = ( across + 0.38d ) / 0.26d;
					shine[count] = 0.6d * Math.exp ( -stripe * stripe );
					cover[count] = coverage;
					count++;
				}
			}
		}

		/** Draw the frame for this moment into the image. */
		void render (double seconds) {
			if (image == null) return;
			double dt = lastSeconds < 0d ? 0d : Math.max ( 0d, seconds - lastSeconds );
			lastSeconds = seconds;
			clearMargins();

			double lap = seconds / LAP_SECONDS;
			double glintLap = seconds / GLINT_LAP_SECONDS;
			double[] glints = new double[GLINTS];
			for (int i = 0; i < GLINTS; i++) {
				double g = glintLap + i / (double) GLINTS;
				glints[i] = g - Math.floor ( g );
			}
			double sigma = GLINT_SIGMA * scale;
			double reach = 3d * sigma / perimeter;
			int bgR = ( background >> 16 ) & 255;
			int bgG = ( background >> 8 ) & 255;
			int bgB = background & 255;

			for (int k = 0; k < count; k++) {
				double a = arc[k];
				// the pattern moves towards larger arc, which runs counter-clockwise on screen
				double hue = CYCLES * ( a - lap );
				hue -= Math.floor ( hue );
				int rgb = rainbow[ Math.min ( rainbow.length - 1, (int) ( hue * rainbow.length ) ) ];

				double glint = 0d;
				for (int i = 0; i < GLINTS; i++) {
					double d = Math.abs ( a - glints[i] );
					if (d > 0.5d) d = 1d - d;
					if (d < reach) {
						double u = d * perimeter / sigma;
						glint += Math.exp ( -u * u );
					}
				}
				double white = Math.min ( 1d, shine[k] + 0.9d * Math.min ( 1d, glint ) );
				double t = tone[k];
				double c = cover[k];
				pixels[index[k]] = ( mix ( ( rgb >> 16 ) & 255, t, white, c, bgR ) << 16 )
						| ( mix ( ( rgb >> 8 ) & 255, t, white, c, bgG ) << 8 )
						| mix ( rgb & 255, t, white, c, bgB );
			}
			drawSparkles ( seconds, dt );
		}

		private static int mix (int channel, double tone, double white, double cover, int bg) {
			double v = channel * tone;
			v += ( 255d - v ) * white;
			v = bg + ( v - bg ) * cover;
			return (int) Math.max ( 0d, Math.min ( 255d, v + 0.5d ) );
		}

		private void clearMargins () {
			int w = imageWidth;
			int h = imageHeight;
			int m = Math.min ( imageMargin, Math.min ( w, h ) );
			for (int y = 0; y < h; y++) {
				int row = y * w;
				if (y < m || y >= h - m) Arrays.fill ( pixels, row, row + w, background );
				else {
					Arrays.fill ( pixels, row, row + m, background );
					Arrays.fill ( pixels, row + w - m, row + w, background );
				}
			}
		}

		/** Short-lived four-pointed glints, born near the crown of the tube and fading out. */
		private void drawSparkles (double seconds, double dt) {
			double expected = SPARKLES_PER_S * dt;
			while (count > 0 && random.nextDouble() < expected) {
				expected -= 1d;
				for (int tries = 0; tries < 12; tries++) {
					int k = random.nextInt ( count );
					if (cover[k] < 1d || tone[k] < 0.9d) continue;	// somewhere near the tube's middle
					double x = index[k] % imageWidth + 0.5d;
					double y = Math.floorDiv ( index[k], imageWidth ) + 0.5d;
					sparkles.add ( new double[] { x, y, seconds, 0.7d + 0.6d * random.nextDouble() } );
					break;
				}
			}
			if (sparkles.isEmpty()) return;
			Graphics2D g = image.createGraphics();
			try {
				g.setRenderingHint ( RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON );
				Iterator<double[]> it = sparkles.iterator();
				while (it.hasNext()) {
					double[] s = it.next();
					double age = ( seconds - s[2] ) / SPARKLE_LIFE_S;
					if (age >= 1d || age < 0d) { it.remove(); continue; }
					double glow = Math.sin ( Math.PI * age );
					double len = ( 2.5d + 5d * glow ) * s[3] * scale;
					double x = s[0];
					double y = s[1];
					g.setColor ( new Color ( 255, 255, 255, (int) ( 240 * glow ) ) );
					g.setStroke ( new BasicStroke ( (float) ( 1.3d * scale ) ) );
					g.draw ( new Line2D.Double ( x - len, y, x + len, y ) );
					g.draw ( new Line2D.Double ( x, y - len, x, y + len ) );
					double d = len * 0.4d;
					g.setStroke ( new BasicStroke ( (float) ( 0.8d * scale ) ) );
					g.draw ( new Line2D.Double ( x - d, y - d, x + d, y + d ) );
					g.draw ( new Line2D.Double ( x - d, y + d, x + d, y - d ) );
					double dot = 1.6d * scale * ( 0.5d + glow );
					g.fill ( new Ellipse2D.Double ( x - dot, y - dot, 2d * dot, 2d * dot ) );
				}
			} finally {
				g.dispose();
			}
		}

		/** Copy the four margin strips onto the window; the inside of the image is never shown. */
		void paintOnto (Graphics g, int ox, int oy) {
			if (image == null) return;
			int w = width;
			int h = height;
			int m = margin;
			int iw = imageWidth;
			int ih = imageHeight;
			int im = imageMargin;
			g.drawImage ( image, ox, oy, ox + w, oy + m, 0, 0, iw, im, null );
			g.drawImage ( image, ox, oy + h - m, ox + w, oy + h, 0, ih - im, iw, ih, null );
			g.drawImage ( image, ox, oy + m, ox + m, oy + h - m, 0, im, im, ih - im, null );
			g.drawImage ( image, ox + w - m, oy + m, ox + w, oy + h - m, iw - im, im, iw, ih - im, null );
		}
	}


	/**
	 * Which computers the theme's rule runs on at all.
	 *
	 * <p>The theme's timing and probability are {@link Theme.Schedule}'s; this is the layer in
	 * front of it. A computer qualifies in one of two ways, and in no other:
	 * <ul>
	 * <li>it is on an EMBL network - a DNS suffix containing {@link #DOMAIN_KEYWORD} - <b>and</b> the
	 * account logged on is one of {@link #USERS}. Anyone else on the same network does not;</li>
	 * <li>one of its own IPv4 addresses, on an interface that is up, is one of {@link #ADDRESSES},
	 * whoever is logged on.</li>
	 * </ul>
	 * Everywhere else the rule never shows the theme. An explicit {@code party_mode("on")} still
	 * does - it is a request typed by hand, and the one way to look at the theme on a computer that
	 * does not qualify.
	 *
	 * <p><b>The accounts and addresses are kept as digests, not as themselves</b>: {@link #digest}
	 * is SHA-256 over {@link #SALT} and the value, so the list cannot be read off the source. That
	 * hides it; it does not make it secret. A digest confirms a guess, an account name can be
	 * guessed, and a private address range is small enough to try in full. To change the list,
	 * digest the new value the same way - an account name in lower case, an address as a dotted
	 * quad - and replace or add the entry.
	 *
	 * <p>Like the schedule, the decision is a pure function of what the computer says about itself
	 * ({@link #eligible(Collection, String, Collection, Set, Set)}), so it is tested on invented
	 * computers and invented lists rather than on whichever computer runs the tests. Finding those
	 * facts is {@link Facts#probe}, which reads only local configuration - nothing is sent
	 * anywhere, and no name is looked up.
	 */
	static final class Site {

		/** A DNS suffix containing this is an EMBL network: embl.de, wlan.embl.de, ... */
		static final String DOMAIN_KEYWORD = "embl";

		/** Mixed into every digest, so these values cannot be looked up in a table of plain ones. */
		static final String SALT = "de.embl.iclm/site/1:";

		/** The accounts whose computers qualify on an EMBL network, as {@link #digest}s. */
		static final Set<String> USERS = Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(
				"ad4e4f23aa9dc7588e40f4d42253585551ede3e39533617e2ad99b5c6fe91a23",
				"c533229eda2294e960b0d2c3cc880d9d4a7d6d9d9f000afe24b536f8f4c3e007",
				"88d620af8b06d2b88c79c329f6b3bbfa37164706d9046c6c66dc412e885556bc",
				"885040c8ef33121e904ac09209430dcb4347cc9dce40b94f865918e278a028ec",
				"18e6a55ba3b3a38854ecb9bf6981472f3e0dca25473a6f480148e69b93888f8a")));

		/** Computers that qualify whoever is logged on, by their own IPv4 address, as {@link #digest}s. */
		static final Set<String> ADDRESSES = Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(
				"dfcfa8d66f5472b236a043b999f6d883f11fa423139fcc01f6a07dec4bb09d5e",
				"065c3a5d41d65d94a6338c49e124a927f4b4022eefac5831f55a6031971c831c",
				"b2945995330c2ef5b3812de3ba9b22e1bb25db6257801c2ba302232706a48e42",
				"69c8ca82ad728a1b6959aa37ff659fefce516a60b9142832a1c75e5f4e981f27")));

		/** How long {@code ipconfig} or {@code scutil} may take before its answer is given up on. */
		private static final long COMMAND_TIMEOUT_MS = 5000;

		private Site() {}

		/** The rule over the shipped lists. */
		static boolean eligible(Collection<String> dnsSuffixes, String userName,
				Collection<String> addresses) {
			return eligible(dnsSuffixes, userName, addresses, USERS, ADDRESSES);
		}

		/**
		 * The rule, on facts and lists supplied rather than read.
		 *
		 * @param dnsSuffixes	: every DNS suffix the computer has, from any source; may be empty
		 * @param userName		: the account logged on, as the system gives it; may be null
		 * @param addresses		: the computer's own IPv4 addresses on interfaces that are up
		 * @param users			: digests of the accounts that qualify on an EMBL network
		 * @param listed		: digests of the addresses that qualify on their own
		 */
		static boolean eligible(Collection<String> dnsSuffixes, String userName,
				Collection<String> addresses, Set<String> users, Set<String> listed) {
			if (addresses != null)
				for (String address : addresses)
					if (address != null && listed.contains(digest(address.trim()))) return true;
			String user = accountName(userName);
			if (user == null || !users.contains(digest(user))) return false;
			if (dnsSuffixes != null)
				for (String suffix : dnsSuffixes)
					if (suffix != null && suffix.toLowerCase(Locale.ROOT).contains(DOMAIN_KEYWORD)) return true;
			return false;
		}

		static boolean eligible(Facts facts) {
			return facts != null && eligible(facts.dnsSuffixes, facts.userName, facts.addresses);
		}

		/** SHA-256 of {@link #SALT} and the value, as lower-case hex. */
		static String digest(String value) {
			try {
				byte[] hash = MessageDigest.getInstance("SHA-256")
						.digest((SALT + value).getBytes(StandardCharsets.UTF_8));
				StringBuilder hex = new StringBuilder(2 * hash.length);
				for (byte b : hash) {
					hex.append(Character.forDigit((b >> 4) & 0xf, 16));
					hex.append(Character.forDigit(b & 0xf, 16));
				}
				return hex.toString();
			} catch (NoSuchAlgorithmException impossible) {
				throw new IllegalStateException(impossible);	// every Java platform has SHA-256
			}
		}

		/**
		 * An account name as it is compared: lower case, without a {@code DOMAIN\} before it or an
		 * {@code @domain} after it, since either can come back from a domain logon.
		 */
		static String accountName(String raw) {
			if (raw == null) return null;
			String name = raw.trim();
			int slash = name.lastIndexOf('\\');
			if (slash >= 0) name = name.substring(slash + 1);
			int at = name.indexOf('@');
			if (at > 0) name = name.substring(0, at);
			name = name.trim().toLowerCase(Locale.ROOT);
			return name.isEmpty() ? null : name;
		}


		// ---- what the computer says about itself ----------------------------------------

		/** What the rule looks at, gathered once. */
		static final class Facts {
			final Set<String> dnsSuffixes = new LinkedHashSet<String>();
			String userName;
			final Set<String> addresses = new LinkedHashSet<String>();

			/**
			 * Read the facts from this computer.
			 * <p>
			 * The DNS suffixes are every one of: the logon's DNS domain ({@code USERDNSDOMAIN}, set
			 * for a Windows domain logon); on Windows, each value {@code ipconfig /all} gives on a
			 * line naming DNS - primary suffix, search list, connection-specific suffixes; elsewhere
			 * the {@code search} and {@code domain} lines of {@code /etc/resolv.conf}, and on macOS
			 * what {@code scutil --dns} lists. Any one containing the keyword is enough. A source that
			 * fails simply contributes nothing.
			 * <p>
			 * Blocking - it may start a process - so it is never called on the event thread.
			 */
			static Facts probe() {
				Facts facts = new Facts();
				facts.userName = System.getProperty("user.name");
				if (accountName(facts.userName) == null) facts.userName = System.getenv("USERNAME");
				addIfPresent(facts.dnsSuffixes, System.getenv("USERDNSDOMAIN"));
				String os = String.valueOf(System.getProperty("os.name")).toLowerCase(Locale.ROOT);
				if (os.contains("windows")) {
					facts.dnsSuffixes.addAll(dnsSuffixesFromIpconfig(run("ipconfig", "/all")));
				} else {
					facts.dnsSuffixes.addAll(dnsSuffixesFromResolvConf(read(new File("/etc/resolv.conf"))));
					if (os.contains("mac")) facts.dnsSuffixes.addAll(dnsSuffixesFromScutil(run("scutil", "--dns")));
				}
				facts.addresses.addAll(localIpv4Addresses());
				return facts;
			}

			@Override public String toString() {
				return "user " + userName + ", DNS suffixes " + dnsSuffixes + ", addresses " + addresses;
			}
		}

		/**
		 * The values of every {@code ipconfig /all} line that names DNS.
		 * <p>
		 * Matched on "DNS" rather than on the English labels, because Windows translates them -
		 * "Verbindungsspezifisches DNS-Suffix", "Suffixe DNS propre a la connexion" - while the
		 * word DNS itself survives. The DNS server lines come along too; their values are addresses
		 * and never contain the keyword. A search list of several suffixes puts the second and later
		 * ones on lines of their own with no label, so an indented line with no colon straight after
		 * a DNS line is read as one more value.
		 */
		static Set<String> dnsSuffixesFromIpconfig(String output) {
			Set<String> suffixes = new LinkedHashSet<String>();
			if (output == null) return suffixes;
			boolean continuing = false;
			for (String line : output.split("\\r?\\n")) {
				if (line.trim().isEmpty()) {
					continuing = false;
					continue;
				}
				if (!line.toLowerCase(Locale.ROOT).contains("dns")) {
					if (continuing && Character.isWhitespace(line.charAt(0)) && line.indexOf(':') < 0)
						addIfPresent(suffixes, line);
					else
						continuing = false;
					continue;
				}
				/* " : " where the label is padded with dots, a bare ':' where a translated label
				 * fills the column ("Verbindungsspezifisches DNS-Suffix: embl.de"). The first one,
				 * since a server's IPv6 address carries colons of its own. */
				int separator = line.indexOf(" : ");
				int value = separator >= 0 ? separator + 3 : line.indexOf(':') + 1;
				if (value <= 0) continue;
				addIfPresent(suffixes, line.substring(value));
				continuing = true;
			}
			return suffixes;
		}

		/** The names on the {@code search} and {@code domain} lines of a resolv.conf. */
		static Set<String> dnsSuffixesFromResolvConf(String text) {
			Set<String> suffixes = new LinkedHashSet<String>();
			if (text == null) return suffixes;
			for (String line : text.split("\\r?\\n")) {
				String[] words = line.trim().split("\\s+");
				if (words.length < 2 || !("search".equals(words[0]) || "domain".equals(words[0]))) continue;
				for (int i = 1; i < words.length; i++) addIfPresent(suffixes, words[i]);
			}
			return suffixes;
		}

		/** The values of the {@code domain} lines {@code scutil --dns} prints. */
		static Set<String> dnsSuffixesFromScutil(String output) {
			Set<String> suffixes = new LinkedHashSet<String>();
			if (output == null) return suffixes;
			for (String line : output.split("\\r?\\n")) {
				int colon = line.indexOf(':');
				if (colon < 0 || !line.substring(0, colon).toLowerCase(Locale.ROOT).contains("domain")) continue;
				addIfPresent(suffixes, line.substring(colon + 1));
			}
			return suffixes;
		}

		/** Every IPv4 address of an interface that is up and not the loopback. */
		static Set<String> localIpv4Addresses() {
			Set<String> addresses = new LinkedHashSet<String>();
			try {
				Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
				while (interfaces != null && interfaces.hasMoreElements()) {
					NetworkInterface network = interfaces.nextElement();
					try {
						if (!network.isUp() || network.isLoopback()) continue;
					} catch (IOException unreadable) {
						continue;
					}
					for (Enumeration<InetAddress> own = network.getInetAddresses(); own.hasMoreElements(); ) {
						InetAddress address = own.nextElement();
						if (address instanceof Inet4Address) addresses.add(address.getHostAddress());
					}
				}
			} catch (Throwable unreadable) {
				// no interfaces to look at: this computer is then eligible by name only, or not at all
			}
			return addresses;
		}

		private static void addIfPresent(Set<String> into, String value) {
			if (value == null) return;
			String trimmed = value.trim();
			if (!trimmed.isEmpty()) into.add(trimmed);
		}

		/** A command's output, or null when it cannot be run or does not finish in time. */
		private static String run(String... command) {
			Process process = null;
			try {
				process = new ProcessBuilder(command).redirectErrorStream(true).start();
				final InputStream output = process.getInputStream();
				final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				Thread reader = Shutdown.daemon(new Runnable() {
					@Override public void run() {
						byte[] buffer = new byte[8192];
						try {
							for (int n; (n = output.read(buffer)) > 0; ) bytes.write(buffer, 0, n);
						} catch (IOException closed) {
							// the process ended or was stopped; what arrived is what there is
						}
					}
				}, "OPM-site-probe");
				reader.start();
				if (!process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null;
				reader.join(COMMAND_TIMEOUT_MS);
				return new String(bytes.toByteArray(), Charset.defaultCharset());
			} catch (Throwable unavailable) {
				return null;
			} finally {
				if (process != null) process.destroy();
			}
		}

		private static String read(File file) {
			try {
				return file.isFile() ? new String(Files.readAllBytes(file.toPath()), Charset.defaultCharset()) : null;
			} catch (Throwable unreadable) {
				return null;
			}
		}
	}


	/*	----------------------------------------------------------------------------------------
	 *	Archive below this line. Nothing above calls any of it; see the class comment.
	 *	--------------------------------------------------------------------------------------*/



	/*  Moved here from Transform.java in 2.1.6: two transformations that were written,
	 *  commented out, and never called. Kept because the geometry in them is worth having
	 *  if flip-and-align ever has to happen in one pass again.  */

	/*  Moved here from Transform.java in 2.1.6: two transformations that were written,
	 *  commented out, and never called. Kept because the geometry in them is worth
	 *  having if flip-and-align ever has to be done in one pass again.  */

	/**
	 * 
	 * @param imp
	 * @param matrix_align
	 * @param matrix_deskew
	 * @return
	 */
	/*
	public static double[][] combineAlignToDeskew (
			ImagePlus imp,
			double[][] matrix_align,
			double[][] matrix_deskew
			) {
		if (null == imp || null == matrix_align || null == matrix_deskew) return null;
		// add horizontal flip to align matrix: Right to Left
		matrix_align[0][0] = - matrix_align[0][0];
		matrix_align[0][1] = - matrix_align[0][1];
		matrix_align[0][3] = - matrix_align[0][3] - imp.getWidth();
		// combine with deskew: first deskew then align = cross product align x deskew x image
		double[][] matrix_combine = crossproduct ( matrix_align, matrix_deskew );
		return matrix_combine;
	}
	*/

	/**				Apply flip and alignment simutaneously
	 * <br>			by constructing 3D affine matrix from 2D rigid matrix
	 * 
	 * @param imp
	 * @param rigid2d_matrix
	 */
	/*
	public static ImagePlus flipAndAlign (
			ImagePlus imp,
			double[][] matrix_2d,
			boolean tryGPU
			) {
		if (null == imp) return null;
		int w = imp.getWidth();
		int[] dims = imp.getDimensions(true);
		
		double[][] matrix_3d = identity();
		matrix_3d[0][0] = matrix_2d[0][0];
		matrix_3d[0][1] = matrix_2d[0][1];
		matrix_3d[0][3] = matrix_2d[0][2];
		matrix_3d[1][0] = matrix_2d[1][0];
		matrix_3d[1][1] = matrix_2d[1][1];
		matrix_3d[1][3] = matrix_2d[1][2];		
		
		AffineTransform3D affine_3d = new AffineTransform3D();
		affine_3d.set( matrix_3d ); 		// here affine_3d is the inverse of transform matrix
		affine_3d = affine_3d.inverse(); 	// now affine_3d is the transform matrix
		double m00 = affine_3d.get(0, 0);
		double m01 = affine_3d.get(0, 1);
		double m03 = affine_3d.get(0, 3);
		affine_3d.set( -m00,  0, 0 );		// add the horizontal filp to transform matrix
		affine_3d.set( -m01,  0, 1 );		// add the horizontal filp to transform matrix
		affine_3d.set( w-m03, 0, 3 );		// add the horizontal filp to transform matrix
		//affine_3d = affine_3d.inverse();	// now affine_3d can be used in imglib2
		affine_3d.toMatrix(matrix_3d);		// update the 3D transformation matrix
		// now apply the 3D affine transformation
		ImagePlus imp_flipAligned = null;
		
		affine_3d = affine_3d.inverse();
		CLIJ2 clij2 = CLIJ2.getInstance();
		ClearCLBuffer source = clij2.push(imp);
		// adjust output width to input width
		long[] outputsize = Transform.getTransformedDim (dims, matrix_3d, true);
		ClearCLBuffer destination = clij2.create(source);
		// apply transform with CLIJ2
		clij2.affineTransform3D(source, destination, affine_3d);
		imp_flipAligned = clij2.pull(destination);
		clij2.clear();
		
		return imp_flipAligned;
	}
	*/


// TODO: GPU
		
	
		public static ImagePlus transform ( Parameter parameter ) {
				/*ImagePlus imp, 
				AffineTransform3D transform,	// not inversed
				boolean autoPartitionData, 
				int numPartition,
				boolean doInverse
				) {*/
			Log log = Log.getInstance();
			if (null == parameter.impInput) return null;
			if (parameter.doInverse) parameter.deskewMatrix = Transform.inverse(parameter.deskewMatrix);
			long start = System.currentTimeMillis();
			ImagePlus imp_transform = null;
			AffineTransform3D transform = Transform.raw_to_imglib2(parameter.deskewMatrix);
			// calculate output dimension of transformed volume
			int[] dims = parameter.impInput.getDimensions(true);
			// if input image is hyperstack, put each xyz stack into Map and process each stack and then combine them
			if (dims[2]*dims[4] > 1) {
				Map<String, ImagePlus> map_input = Partition.toMap (parameter.impInput);
				Map<String, ImagePlus> map_transform = transform (map_input, transform,	parameter.autoPartition, parameter.numPartition, parameter.doInverse);
				imp_transform = Partition.toHyperstack(map_transform, parameter.impInput.getTitle() + "-deskwed");
				imp_transform.changes = false;
				//float duration = System.currentTimeMillis() - start;
				//log.add("\n\ttransform data on GPU takes %.3f seconds.\n", duration/1000);
				return imp_transform;
			}
			// transform non-hyperstack image stack
			double m11 = transform.get(1,1); double m12 = transform.get(1,2);
			double m21 = transform.get(2,1); double m22 = transform.get(2,2);
			long newYdim = (long)Math.round( Math.abs( dims[1] * m11 + dims[3] * m12 )); // h * cos0 + d * α
			long newZdim = (long)Math.round( Math.abs( dims[1] * m21 + dims[3] * m22 ));	// h * sin0
			long[] outputsize = {dims[0], newYdim, newZdim};
			double outputsize_MB = outputsize[0] * outputsize[1] * outputsize[2] * parameter.impInput.getBytesPerPixel() /1024/1024;
			log.add("\n\tGPU transform volume dimension calculated as:\n\t%d * %d * %d pixels = %.1f MB.\n", outputsize[0], outputsize[1], outputsize[2], outputsize_MB);
			// to correct transform with clij, transform need to be inversed
			transform = Transform.inverse(transform);
			try {	
				// serializing GPU processing to horizontally partitioned image volume
				ImagePlus[] imp_parts = Partition.partition (parameter.impInput, "X", parameter.numPartition);
				ImagePlus[] imp_deskewed = new ImagePlus[imp_parts.length];
				// processing on GPU
				long start_GPU = System.currentTimeMillis();
				CLIJ2 clij2 = CLIJ2.getInstance();
				for (int i=0; i<imp_parts.length; i++) {
					long start_part = System.currentTimeMillis();
					ClearCLBuffer source = clij2.push(imp_parts[i]);
					// adjust output width to input width
					outputsize[0] = imp_parts[i].getWidth();
					ClearCLBuffer destination = clij2.create(outputsize, source.getNativeType());
					// apply transform with CLIJ2
					clij2.affineTransform3D(source, destination, transform);
					//clij2.release(source);
					imp_deskewed[i] = clij2.pull(destination);
					//clij2.release(destination);
					clij2.clear();
					imp_parts[i].close();	
					//IJ.run("Collect Garbage", "");
					float time_part = System.currentTimeMillis() - start_part;
					log.add("\tGPU transform on partition %d takes %.3f seconds.\n", i+1, time_part/1000);
				}
				float time_processing = System.currentTimeMillis() - start_GPU;
				log.add("\n\tGPU transform in total takes %.3f seconds.\n", time_processing/1000);
				// combine partition horizontally together
				ImageStack stack_deskewed = Partition.combine (imp_deskewed, "X", false);
				for (int i=0; i<imp_parts.length; i++) {
					imp_deskewed[i].close();
				}
				imp_transform = new ImagePlus(parameter.impInput.getTitle() + "-deskwed", stack_deskewed);
				imp_transform.changes = false;
				log.add(clij2.reportMemory());
				Utils.collectGarbage(); // probably slow things down, but better for memory management
			} catch ( Exception e ) {
				log.add(e.getMessage());
				log.add(" Failed attempt transform with GPU!");	
			}
			float duration = System.currentTimeMillis() - start;
			log.add("\n\ttransform data on GPU takes %.3f seconds.\n", duration/1000);
			return imp_transform;
		}
		
		/**
		 * 
		 * @param impMapInput
		 * @param transform
		 * @param autoPartitionData
		 * @param numPartition
		 * @param doInverse
		 * @return
		 */
		public static Map<String, ImagePlus> transform (
				Map<String, ImagePlus> impMapInput, 
				AffineTransform3D transform,	// not inversed
				boolean autoPartitionData, 
				int numPartition,
				boolean doInverse
				) {
			if (null == impMapInput || 0 == impMapInput.size()) return null;
			Map<String, ImagePlus> impMapOutput = new HashMap<String, ImagePlus>();
			for (Map.Entry<String, ImagePlus> entry : impMapInput.entrySet()) {
				ImagePlus imp_transform = GPU.transform (entry.getValue(), null);
				impMapOutput.put(entry.getKey(), imp_transform);
			}
			return impMapOutput;
		}
		
		/**
		 * 
		 * @param impMapInput
		 * @param axis
		 * @param type
		 * @return
		 */
		public static Map<String, ImagePlus> projection (
				Map<String, ImagePlus> impMapInput, 
				String axis, 
				String type
				) {
			if (null == impMapInput || 0 == impMapInput.size()) return null;
			Map<String, ImagePlus> impMapOutput = new HashMap<String, ImagePlus>();
			for (Map.Entry<String, ImagePlus> entry : impMapInput.entrySet()) {
				//need todo catch the exception when GPU processing failed, switch onto CPU processing
				ImagePlus imp_project = null;
				switch (axis.toLowerCase()) {
				case "x":
					imp_project = GPU.projection_x (entry.getValue(), type);
					break;
				case "y":
					imp_project = GPU.projection_y (entry.getValue(), type);
					break;
				case "z":
					imp_project = GPU.projection_z (entry.getValue(), type);
					break;
				}
				impMapOutput.put(entry.getKey(), imp_project);
			}
			return impMapOutput;
		}
		
		/**
		 * 
		 * @param impMapInput
		 * @param permuteString
		 * <p>
		 * @return
		 */
		public static Map<String, ImagePlus> permute (
				Map<String, ImagePlus> impMapInput, // image stack in the order XYZ
				String permuteString
				) {
			if (null == impMapInput || 0 == impMapInput.size()) return null;
			Map<String, ImagePlus> impMapOutput = new HashMap<String, ImagePlus>();
			for (Map.Entry<String, ImagePlus> entry : impMapInput.entrySet()) {
				ImagePlus imp_permute = GPU.permute (entry.getValue(), permuteString);
				impMapOutput.put(entry.getKey(), imp_permute);
			}
			return impMapOutput;
		}
		
		/**
		 * 
		 * @param imp
		 * @param tranposeString
		 * <p>
		 * @return
		 */
		public static Map<String, ImagePlus> transpose (
				Map<String, ImagePlus> impMapInput,
				String tranposeString
				) {
			if (null == impMapInput || 0 == impMapInput.size()) return null;
			Map<String, ImagePlus> impMapOutput = new HashMap<String, ImagePlus>();
			for (Map.Entry<String, ImagePlus> entry : impMapInput.entrySet()) {
				ImagePlus imp_transpose = GPU.transpose (entry.getValue(), tranposeString);
				impMapOutput.put(entry.getKey(), imp_transpose);
			}
			return impMapOutput;
		}
		
		/**
		 * 
		 * @param impMapInput
		 * @param flip_x
		 * @param flip_y
		 * @param flip_z
		 * <p>
		 * @return
		 */
		public static Map<String, ImagePlus> flip (
				Map<String, ImagePlus> impMapInput, 
				boolean flip_x,
				boolean flip_y,
				boolean flip_z
				) {
			if (null == impMapInput || 0 == impMapInput.size()) return null;
			Map<String, ImagePlus> impMapOutput = new HashMap<String, ImagePlus>();
			for (Map.Entry<String, ImagePlus> entry : impMapInput.entrySet()) {
				ImagePlus imp_flip = GPU.flip (entry.getValue(), flip_x, flip_y, flip_z);
				impMapOutput.put(entry.getKey(), imp_flip);
			}
			return impMapOutput;
		}
		
		/** need todo: input is already multi-channel image
		 * 
		 * @param impMapInput
		 * <p>
		 * @return
		 */
		public static Map<String, ImagePlus> fold_x (
				Map<String, ImagePlus> impMapInput
				) {
			if (null == impMapInput || 0 == impMapInput.size()) return null;
			Map<String, ImagePlus> impMapOutput = new HashMap<String, ImagePlus>();
			for (Map.Entry<String, ImagePlus> entry : impMapInput.entrySet()) {
				ImagePlus imp_fold = Permutation.fold_x (entry.getValue(), true);
				impMapOutput.put(entry.getKey(), imp_fold);
			}
			return impMapOutput;
		}
		
		/**		Copy ImagePlus on GPU
		 * <br>	slower than ImagePlus.duplicate() for unknown reason. Not recommended to use.
		 * 
		 * @param imp				: input ImagePlus, should be image stack
		 * <p>
		 * @return					: output ImagePlus, as a copy of the input image stack; null if GPU process failed
		 */
		public static ImagePlus copy (
				ImagePlus imp
				) {
			Log log = Log.getInstance();
			long start = System.currentTimeMillis();
			ImagePlus imp_copy = null;
			try {
				CLIJ2 clij2 = CLIJ2.getInstance();
				ClearCLBuffer source = clij2.push(imp);
				ClearCLBuffer destination_copy = clij2.create(source);
				clij2.copy(source, destination_copy);
				imp_copy = clij2.pull(destination_copy);
				clij2.release(source);
				clij2.release(destination_copy);
				clij2.clear();
				log.add(clij2.reportMemory());
				imp_copy.changes = false;
			} catch (Exception e){
				log.add(e.getMessage());
				log.add(" Failed attempt copy data with GPU!");	
			}
			float duration = System.currentTimeMillis() - start;
			log.add("\n\tcopy of data on GPU takes %.3f seconds.\n", duration/1000);
			return imp_copy;
		}
		
		// need todo: implement tile and un-tile of GPU processing of large image
		public static void tile (
				ImagePlus imp
				) {
			
			//CLIJ2 clij2 = CLIJ2.getInstance();

			//for (int z=0; z<numZ; z++) {
			//	for (int y=0; y<numTile; z++) {
			//		for (int x=0; x<numTile; x++) {
						//ClearCLBuffer gpuImg = clij2.pushTile(imp, x, y, z, tileWidth, tileHeight, tileDepth, margin, margin, margin);
						//tempOut = clij2.create(gpuImg.getDimensions(), NativeTypeEnum.Float);
						
						//DeconvolveRichardsonLucyFFT.deconvolveRichardsonLucyFFT(clij2, gpuImg, gpuPSF, tempOut, 100, 0.0, False);
				
						//clijx.pullTile(deconvolved, tempOut, x, y, z, tileWidth, tileHeight, tileDepth, margin, margin, margin);
			
			//		}
			//	}
			//}
			
			/*
			for x in range(numTilesXY):
				for y in range(numTilesXY):
					for z in range(numTilesZ):
				
						print str(x)+' '+str(y) + ' ' + str(z)
						
						gpuImg = clij2.pushTile(img, x, y, z, tileWidth, tileHeight, tileDepth, margin, margin, margin);
						tempOut = clij2.create(gpuImg.getDimensions(), NativeTypeEnum.Float);
						
						DeconvolveRichardsonLucyFFT.deconvolveRichardsonLucyFFT(clij2, gpuImg, gpuPSF, tempOut, 100, 0.0, False);
				
						clijx.pullTile(deconvolved, tempOut, x, y, z, tileWidth, tileHeight, tileDepth, margin, margin, margin);
			*/
			
			/*
			ClearCLBuffer source = clij2.push(imp);
			outputsize = new long[]{dims[1], dims[3], dims[0]};
			ClearCLBuffer destination_yzx = clij2.create(outputsize, source.getNativeType());
			clij2.resliceLeft(source, destination_yzx);	// xyz to yzx
			clij2.release(source);
			imp_permute = clij2.pull(destination_yzx);
			clij2.release(destination_yzx);
			clij2.clear();
			
			
			
			CLIJ2 clij2 = CLIJ2.getInstance();
			ClearCLBuffer source = clij2.push(imp);
			ImagePlus imp_out = null;
			clij2.pullTile(imp_out, source, 0, 0, 0, 0, 0, 0, 0, 0, 0);
			*/
				//clij2.pushTile(ClearCLBuffer image, 
				//	Integer tileIndexX, Integer tileIndexY, Integer tileIndexZ, 
				//	Integer width, Integer height, Integer depth, 
				//	Integer marginWidth, Integer marginHeight, 
				//	Integer image0)
				/*
				 * 1: ImagePlus 2: clear buffer
				 * 3: tile index x
				 * 4: tile index y
				 * 5: tile index z
				 * 6: width
				 * 7: height
				 * 8: depth
				 * 9: margin width
				 * 10: margin height
				 * 11: ? margin depth ?
				 */
			}
		
		
		
// TODO: Projection
		
		/**
		 * 
		 * @param imp				: input ImagePlus, should be image stack
		 * @param type				: type of projection: max, mean, min, sum, med, std 
		 * 
		 * @return imp_xProj		: output ImagePlus, as X projection 2D image
		 */
		public static ImagePlus projection_x (
				ImagePlus imp, 
				String type,
				boolean tryGPU
				) {
			//long start = System.currentTimeMillis();
			ImagePlus imp_xProj = null;
			if (tryGPU) {
				imp_xProj = GPU.projection_x (imp, type);
				if (null != imp_xProj) return imp_xProj;
			}
			
			ImagePlus imp_zyx = null;
			if (tryGPU) {
				imp_zyx = GPU.permute (imp, "->ZYX");
			}
			if (null == imp_zyx)
				imp_zyx = CPU.permute(imp, "->ZYX");
			
			if (type.toLowerCase().equals("mean")) type = "avg";
			imp_xProj = projection_z (imp_zyx, type, tryGPU);
			imp_xProj.setTitle(imp.getTitle() + " -" + type + "X projection");
			imp_xProj.changes = false;
			//float duration = System.currentTimeMillis() - start;
			//System.out.printf("\n\t%s X projection data takes %.3f seconds.\n", type, duration/1000);
			return imp_xProj;
		}
		
		
		/**
		 * 
		 * @param imp				: input ImagePlus, should be image stack
		 * @param type				: type of projection: max, mean, min, sum, med, std
		 * 
		 * @return imp_yProj		: output ImagePlus, as Y projection 2D image
		 */
		public static ImagePlus projection_y (
				ImagePlus imp, 
				String type,
				boolean tryGPU
				) {
			//long start = System.currentTimeMillis();
			ImagePlus imp_yProj = null;
			if (tryGPU) {
				imp_yProj = GPU.projection_y (imp, type);
				if (null != imp_yProj) return imp_yProj;
			}
			
			ImagePlus imp_xzy = null;
			if (tryGPU) {
				imp_xzy = GPU.transpose (imp, "->XZY");
			}
			if (null == imp_xzy)
				imp_xzy = CPU.transpose(imp, "->XZY");
			
			if (type.toLowerCase().equals("mean")) type = "avg";
			imp_yProj = projection_z (imp_xzy, type, tryGPU);
			imp_yProj.setTitle(imp.getTitle() + " -" + type + "Y projection");
			imp_yProj.changes = false;
			//float duration = System.currentTimeMillis() - start;
			//System.out.printf("\n\t%s Y projection data takes %.3f seconds.\n", type, duration/1000);
			return imp_yProj;
		}
		
		/**
		 * 
		 * @param imp				: input ImagePlus, should be image stack
		 * @param type				: type of projection: max, mean, min, sum, med, std
		 * 
		 * @return imp_zProj		: output ImagePlus, as Z projection 2D image
		 */
		public static ImagePlus projection_z (
				ImagePlus imp, 
				String type,
				boolean tryGPU
				) {
			//long start = System.currentTimeMillis();
			ImagePlus imp_zProj = null;
			if (tryGPU) {
				imp_zProj = GPU.projection_z (imp, type);
				if (null != imp_zProj) return imp_zProj;
			}
			
			String typeString = type;
			if (type.toLowerCase().equals("mean")) {typeString = "avg"; type = "avg";};
			if (type.toLowerCase().equals("med")) typeString = "median";
			if (type.toLowerCase().equals("std")) typeString = "sd";
			typeString += " all";	// this will take care of hyperstack cases
			
			imp_zProj = ZProjector.run(imp, typeString);
			imp_zProj.setTitle(imp.getTitle() + " -" + type + "Z projection");
			imp_zProj.changes = false;
			//float duration = System.currentTimeMillis() - start;
			//System.out.printf("\n\t%s Z projection data takes %.3f seconds.\n", type, duration/1000);
			return imp_zProj;
		}


// TODO: parition
		
		
		
		/**			Horizontally partition stack to fit into GPU memory
		 * 
		 * @param imp
		 * @param guessNumParts
		 * @param numPartition
		 * <p>
		 * @return
		 */
		public static ImagePlus[] partitionHorizontally (ImagePlus imp, boolean guessNumParts, int numPartition) {
			Log log = Log.getInstance();
			long start = System.currentTimeMillis();
			CLIJ2 clij2 = CLIJ2.getInstance();
			//double gpuMemoryByte = clij2.getCLIJ().getGPUMemoryInBytes();
			double gpuMemoryByte = clij2.getCLIJ().getClearCLContext().getDevice().getMaxMemoryAllocationSizeInBytes();
			double maxImageSizeByte = gpuMemoryByte / 4;
			double imageSizeByte = imp.getSizeInBytes();
			if (guessNumParts) {
				numPartition = (int)Math.ceil( imageSizeByte / maxImageSizeByte );
			} else {
				numPartition = (int)Math.max(1, numPartition);
			}
			if (numPartition <= 1) {
				return new ImagePlus[]{imp};
			}
			log.add("\n\tGPU capacity: %.1f MB (ideal image size: ~%.1f MB).\n", gpuMemoryByte/1024/1024, maxImageSizeByte/1024/1024);
			log.add("\tdata size: %.1f MB.\n", imageSizeByte/1024/1024);
			log.add("\tPartition data into %d columns to fit into GPU memory.\n", numPartition);
			
			int[] dims = imp.getDimensions();
			int width_image = dims[0];
			int width_partition = (int) Math.ceil( (double)dims[0] / (double)numPartition );
			ImagePlus[] imp_parts = new ImagePlus[numPartition];
			
			
			boolean lastPart = false;
			//imp.getWindow().setVisible(false);
			for (int i=0; i<numPartition; i++) {
				Roi roi;
				if (width_partition * (i+1) >= dims[0]) {	// last parition
					lastPart = true;
					roi = new Roi(i*width_partition, 0, dims[0] - (i*width_partition) + 1, dims[1]);
				} else {
					roi = new Roi(i*width_partition, 0, width_partition, dims[1]);
				}
				imp.setRoi(roi, false);
				//imp_parts[i] = duplicator.crop(imp);
				imp_parts[i] = imp.crop("stack");
				imp.deleteRoi();
				if (lastPart) {
					log.add("\tOriginal image data width: %d pixels.\n", width_image);
					log.add("\tPartition width: %d * %d + %d pixels.\n", width_partition, (numPartition-1), imp_parts[i].getWidth());
					break;
				}
			}
			//imp.getWindow().setVisible(true);
			float duration = System.currentTimeMillis() - start;
			log.add("\n\tPartition data takes %.3f seconds.\n\n", duration/1000);
			return imp_parts;
		}
		
		
		/**			Horizontally combine stack together
		 * 
		 * @param imp_parts
		 * <p>
		 * @return
		 */
		public static ImageStack combineHorizontally (
				ImagePlus[] imp_parts
				) {
			Log log = Log.getInstance();
			long start = System.currentTimeMillis();
			int numParts = imp_parts.length;
			ImagePlus imp_1 = imp_parts[0];
			ImagePlus imp_last = imp_parts[numParts-1];
			int[] dims = imp_1.getDimensions();
			int width = dims[0]; int height = dims[1]; int depth = dims[3];
			int totalWidth = width * (numParts-1) + imp_last.getWidth();
			
	        ImageStack stack = new ImageStack(totalWidth, height);
	        ImageProcessor ip = imp_1.getStack().getProcessor(1);
	        ImageProcessor ip_combine;
	        	
			for (int z=1; z<=depth; z++) {
	            ip_combine = ip.createProcessor(totalWidth, height);
				for (int i=0; i<numParts-1; i++) {
		            ip_combine.insert(imp_parts[i].getStack().getProcessor(z), i*width, 0);
	        	}
		        ip_combine.insert(imp_last.getStack().getProcessor(z), width*(numParts-1), 0);
		        stack.addSlice(null, ip_combine);
			}
			float duration = System.currentTimeMillis() - start;
			log.add("\n\tCombine data takes %.3f seconds.\n", duration/1000);
			return stack;
		}
		
		/**			Vertically combine stack together
		 * 
		 * @param imp_parts
		 * <p>
		 * @return
		 */
		public static ImageStack combineVertically (
				ImagePlus[] imp_parts
				) {
			Log log = Log.getInstance();
			long start = System.currentTimeMillis();
			int numParts = imp_parts.length;
			ImagePlus imp_1 = imp_parts[0];
			ImagePlus imp_last = imp_parts[numParts-1];
			int[] dims = imp_1.getDimensions();
			int width = dims[0]; int height = dims[1]; int depth = dims[3];
			int totalWidth = width * (numParts-1) + imp_last.getWidth();
			
	        ImageStack stack = new ImageStack(totalWidth, height);
	        ImageProcessor ip = imp_1.getStack().getProcessor(1);
	        ImageProcessor ip_combine;
	        	
			for (int z=1; z<=depth; z++) {
	            ip_combine = ip.createProcessor(totalWidth, height);
				for (int i=0; i<numParts-1; i++) {
		            ip_combine.insert(imp_parts[i].getStack().getProcessor(z), i*width, 0);
	        	}
		        ip_combine.insert(imp_last.getStack().getProcessor(z), 0, width*(numParts-1));
		        stack.addSlice(null, ip_combine);
			}
			float duration = System.currentTimeMillis() - start;
			log.add("\n\tCombine data takes %.3f seconds.\n", duration/1000);
			return stack;
		}
		
		
		public static ImageStack combineLongitudinally () {
			return null;
		}
		
		/*
		public static ImagePlus[] separateChannel (
				ImagePlus imp,
				String channelString,
				double[][] alignmatrix
				) {
			if (null == imp) return null;
			String name = Utils.getName(imp);
			
			
			if (channelString.equals("whole image")) return new ImagePlus[] {imp};
			//Log log = Log.getInstance();
			String name = Utils.getName(imp);
			int[] dims = imp.getDimensions(true);
			// TODO: implement code for the case that input is already have multiple channel
			//if (dims[2] > 1 || dims[4] >1) {
			int width = (int) Math.ceil(dims[0]/2);	// if image width is odd: the midline is duplicated in both 
			
			// create ROIs corresponding to the left half of the image
			Roi roiL = new Roi(0, 0, width, dims[1]);
			Utils.hideRoi ( roiL );
			imp.setRoi( roiL, false );
			ImagePlus imp_left = imp.crop("stack");
			imp.deleteRoi();
			imp_left.setTitle(name + "-left");
			if (channelString.equals("only left"))
				return new ImagePlus[] {imp_left};
			
			// create ROIs corresponding to the right half of the image
			Roi roiR = new Roi(dims[0]-width, 0, width, dims[1]);
			Utils.hideRoi ( roiR );
			imp.setRoi( roiR, false );
			ImagePlus imp_right = imp.crop("stack");
			imp.deleteRoi();
			imp_right.setTitle(name + "-right");
			if (channelString.equals("only right"))
				return new ImagePlus[] {imp_right};
			
			// create image array, for following cases:

			// in the case both left and right sides requested
			if (channelString.equals("left & right separately"))
				return new ImagePlus[] {imp_left, imp_right};
			
			
			ImagePlus[] imp_LR = separateImageLeftRight (imp, channelString ); // fold and align case not yet covered here
			
			// flip the right side and merge onto the left as 2nd channel
			if (channelString.equals("fold by midline")) {
				ImagePlus imp_fold = Permutation.fold_x ( imp, true );
				imp_fold.setTitle( name + "-xFold" );
				return new ImagePlus[] { imp_fold };
			}
			
			// flip the right side and align with left side with alignment matrix (2D rigid)
			if (channelString.equals("align with SIFT matrix")) {
				ImagePlus imp_fold = Permutation.fold_x ( imp, true );
				imp_fold.setTitle( name + "-xFoldAligned" );
				return new ImagePlus[] { imp_fold };
			}
			
			// whole image, only left, only right, or both
			return imp_LR;
		}
		*/
		
		/**		Compute the base 2 logarithm of a double value.
		 * 
		 * @param N
		 * <p>
		 * @return
		 */
		public static double log2( double N ) {
			return (Math.log(N) / Math.log(2.0d));
	    }
		
		/**		Compute the base 8 logarithm of a double value.
		 * 
		 * @param N
		 * <p>
		 * @return
		 */
		public static double log8(double N) {
			return (Math.log(N) / Math.log(8.0d));
	    }
		
		/**
		 * 
		 * @param imp
		 */
		public static void padImage (
				ImagePlus imp
				) {
			int[] dims = imp.getDimensions(true);
			int padWidth 	= 8 - Math.floorMod(dims[0], 8);
			int padHeight 	= 8 - Math.floorMod(dims[1], 8);
			int padDepth 	= 8 - Math.floorMod(dims[3], 8);
			BorderManager3D bm = new ConstantBorder3D(imp.getImageStack(), 0);
			ImageStack stack = bm.addBorders(
					imp.getImageStack(), 0, padWidth, 0, padHeight, 0, padDepth);
			imp.setStack(stack);
		}

		
// TODO: deskew
				/**			Deskew a image as ImagePlus
				 *  <br>	could be an active image in ImageJ
				 *  <br>	or from opening an image file on disk
				 * 
				 * @param impInput			: input image, keep it untouched throughout the processing
				 * @param parameter			: input OPM image stack XY pixel size, in nm
				 * @param zStepSize				: input OPM image stack Z slice physical distance, in nm (as galvo step * DU) 
				 * @param opmAngle				: OPM angle, in degree
				 * @param doInverse				: whether to perform inverse transform, for debug
				 * @param doVirtual				: result as virtual stack, from imglib2 transformation, for fast visualziation or debug
				 * @param doGPU					: whether to perform the affine transform on GPU
				 * @param autoPartition			: whether to automatically calculate the data partition based on available graphic memory and input image size
				 * @param numPartition			: number of partition for GPU processing, if set manually
				 * <p>
				 * @return imp_deskewed			: deskewed image, need to fix for imglib2 Virtual stack problem
				 */
				
				//@Override
				public void deskew_run (String arg) {
					// get parameter of deskew on the active image
					Parameter parameter = new Parameter("image");
					parameter.impInput = IJ.getImage();
					if ( !deskew_image() ) return;
					if ( null == parameter.impInput ) return;
					
					// prepare log
					Log log = new Log("OPM_deskew.log");
					log.add(parameter);
					log.add("deskew image start:");
					
					// timing the start
					long start = System.currentTimeMillis();
					
					// in case only left or right side requested
					boolean doHalf = parameter.channelStr.equals("left only") || parameter.channelStr.equals("right only");
					ImagePlus impInput = parameter.impInput;
					if (doHalf) impInput = (Partition.separateImageLeftRight(impInput, parameter.channelStr)) [0];
					
					// get deskewed image
					ImagePlus imp_deskew = Deskew.deskew_image ( impInput, parameter );
					// get channel image
					ImagePlus[] imp_deskew_channel = new ImagePlus[]{imp_deskew};
					if (!doHalf) imp_deskew_channel = Partition.separateImageLeftRight (
							imp_deskew, parameter.channelStr ) ;
					
					// display result of requested channel(s)
					for (ImagePlus imp_channel : imp_deskew_channel) {
						// display transformed stack
						imp_channel.show();
						imp_channel.setDisplayRange(parameter.impInput.getDisplayRangeMin(), parameter.impInput.getDisplayRangeMax());
						imp_channel.setZ((int)Math.round(imp_channel.getNSlices()/2));
						
						// display projection image(s)
				        if ( (parameter.projX || parameter.projY || parameter.projZ) && !imp_deskew.getStack().isVirtual() ) {
				        	// prepare projection axis string list
				    		ArrayList<String> axes = new ArrayList<String>();
				    		if (parameter.projX) axes.add("X");
				    		if (parameter.projY) axes.add("Y");
				    		if (parameter.projZ) axes.add("Z");
				    		//if (0 == axes.size()) ;
				    		// prepare projection type string list
				    		ArrayList<String> types = new ArrayList<String>();
				    		if (parameter.maxProj)	types.add("max");
				    		if (parameter.avgProj)	types.add("avg");
				    		if (parameter.minProj)	types.add("min");
				    		if (parameter.sumProj)	types.add("sum");
				    		if (parameter.medProj)	types.add("med");
				    		if (parameter.stdProj)	types.add("std");	
				    		//if (0 == types.size()) return;
				    		// create projection images
				    		for (String axis : axes) {
				    			for (String type : types) {
				    				ImagePlus imp_project = Projection.projection (imp_channel, axis, type, parameter.tryGPU);
				    				imp_project.setTitle(imp_channel.getTitle() + "-" + type + axis + " projection");
				    				imp_project.show();
				    				IJ.run(imp_project, "Enhance Contrast", "saturated=0.35");
				    			}
				    		}
						}
					}
					
					// report runtime
					float duration = System.currentTimeMillis() - start;
					log.add("\n\tdeskew finished after %.3f seconds.\n", duration / 1000);
					log.add("deskew image finish.");
					log.close();
				}
				
		
// TODO: Parameter
				
		/** 		generic constructor for Parameter class
		 * 
		 * @param obj
		 */
		public void Parameter(String obj) {
			/*
			instance = this;
			this.obj = obj;
			
			// make use of scijava parameter persistence storage	
			DefaultPrefService prefs = new DefaultPrefService();
			
			xyPixelSize =	prefs.getDouble(Double.class, 		"OPM-"+obj+"-xyPixelSize", 		xyPixelSize);
			zStepSize =		prefs.getDouble(Double.class, 		"OPM-"+obj+"-zStepSize", 			zStepSize);
			opmAngle =		prefs.getDouble(Double.class, 		"OPM-"+obj+"-opmAngle", 			opmAngle);
			
			doInverse =		prefs.getBoolean(Boolean.class, 	"OPM-"+obj+"-doInverse", 			doInverse);
			channelString = prefs.get(String.class, 			"OPM-"+obj+"-channelString", 		channelString);
			projX =			prefs.getBoolean(Boolean.class, 	"OPM-"+obj+"-projX", 				projX);
			projY = 		prefs.getBoolean(Boolean.class, 	"OPM-"+obj+"-projY", 				projY);
			projZ = 		prefs.getBoolean(Boolean.class, 	"OPM-"+obj+"-projZ", 				projZ);
			maxProj = 		prefs.getBoolean(Boolean.class, 	"OPM-"+obj+"-maxProj", 			maxProj);
			avgProj = 		prefs.getBoolean(Boolean.class, 	"OPM-"+obj+"-avgProj", 			avgProj);
			minProj = 		prefs.getBoolean(Boolean.class, 	"OPM-"+obj+"-minProj", 			minProj);
			sumProj = 		prefs.getBoolean(Boolean.class, 	"OPM-"+obj+"-sumProj", 			sumProj);
			medProj =		prefs.getBoolean(Boolean.class, 	"OPM-"+obj+"-medProj", 			medProj);
			stdProj =		prefs.getBoolean(Boolean.class, 	"OPM-"+obj+"-stdProj", 			stdProj);
			
			inputDir = 		prefs.get(String.class, 			"OPM-batch-inputDir", 			inputDir);
			keywords = 		prefs.get(String.class, 			"OPM-batch-keywords", 			keywords);
			doDeskew = 		prefs.getBoolean(Boolean.class, 	"OPM-batch-doDeskew", 			doDeskew);	
			fileExistString=prefs.get(String.class, 			"OPM-batch-fileExistString", 	fileExistString);
			
			saveDir = 		prefs.get(String.class, 			"OPM-batch-saveDir", 			saveDir);
			saveSeparate = 	prefs.getBoolean(Boolean.class, 	"OPM-batch-saveSeparate", 		saveSeparate);
			
			logPath = 		prefs.get(String.class, 			"OPM-watcher-watchLog", 		logPath);
			
			watchDir = 		prefs.get(String.class, 			"OPM-watcher-watchDir", 		watchDir);
			keywords = 		prefs.get(String.class, 			"OPM-watcher-keywords", 		keywords);
			processOld = 	prefs.getBoolean(Boolean.class, 	"OPM-watcher-processOld", 		processOld);
			overwriteExist =prefs.getBoolean(Boolean.class, 	"OPM-watcher-overwriteExist", 	overwriteExist);
			maxWait = 		prefs.getInt(Integer.class, 		"OPM-watcher-maxWait", 			maxWait);
			
			//doVirtual =	prefs.getBoolean(Boolean.class, 	"OPM-"+obj+"-doVirtual", 			doVirtual);
					//tryGPU =		prefs.getBoolean(Boolean.class, 	"OPM-"+obj+"-tryGPU", 			tryGPU);
			
			flipX = 		prefs.getBoolean(Boolean.class, 	"OPM-permute-flipX", 			flipX);
			flipY = 		prefs.getBoolean(Boolean.class, 	"OPM-permute-flipY", 			flipY);
			flipZ = 		prefs.getBoolean(Boolean.class, 	"OPM-permute-flipZ", 			flipZ);
			foldX = 		prefs.getBoolean(Boolean.class, 	"OPM-permute-foldX", 			foldX);
			permuteString = prefs.get(String.class, 			"OPM-permute-permuteString",	permuteString);
			tryGPU = 		prefs.getBoolean(Boolean.class, 	"OPM-permute-tryGPU", 			tryGPU);
			
			
			doStepTransform = 	prefs.getBoolean(Boolean.class, "OPM-debug-doStepTransform", 	doStepTransform);
			
			doVirtual = 		prefs.getBoolean(Boolean.class, "OPM-debug-doVirtual", 			doVirtual);
			tryGPU = 			prefs.getBoolean(Boolean.class, "OPM-debug-tryGPU", 			tryGPU);
			autoPartitionData = prefs.getBoolean(Boolean.class, "OPM-debug-autoPartitionData", 	autoPartitionData);
			numPartition = 		prefs.getInt(Integer.class, 	"OPM-debug-numPartition", 		numPartition);
			
			
			switch (obj.toLowerCase()) {
			
			case "image":
				xyPixelSize =	prefs.getDouble(Double.class, 		"OPM-image-xyPixelSize", 		xyPixelSize);
				zStepSize =		prefs.getDouble(Double.class, 		"OPM-image-zStepSize", 			zStepSize);
				opmAngle =		prefs.getDouble(Double.class, 		"OPM-image-opmAngle", 			opmAngle);
				//doVirtual =	prefs.getBoolean(Boolean.class, 	"OPM-image-doVirtual", 			doVirtual);
				//tryGPU =		prefs.getBoolean(Boolean.class, 	"OPM-image-tryGPU", 			tryGPU);
				doInverse =		prefs.getBoolean(Boolean.class, 	"OPM-image-doInverse", 			doInverse);
				channelString = prefs.get(String.class, 			"OPM-image-channelString", 		channelString);
				projX =			prefs.getBoolean(Boolean.class, 	"OPM-image-projX", 				projX);
				projY = 		prefs.getBoolean(Boolean.class, 	"OPM-image-projY", 				projY);
				projZ = 		prefs.getBoolean(Boolean.class, 	"OPM-image-projZ", 				projZ);
				maxProj = 		prefs.getBoolean(Boolean.class, 	"OPM-image-maxProj", 			maxProj);
				avgProj = 		prefs.getBoolean(Boolean.class, 	"OPM-image-avgProj", 			avgProj);
				minProj = 		prefs.getBoolean(Boolean.class, 	"OPM-image-minProj", 			minProj);
				sumProj = 		prefs.getBoolean(Boolean.class, 	"OPM-image-sumProj", 			sumProj);
				medProj =		prefs.getBoolean(Boolean.class, 	"OPM-image-medProj", 			medProj);
				stdProj =		prefs.getBoolean(Boolean.class, 	"OPM-image-stdProj", 			stdProj);
				break;
			
				
			case "batch":
				inputDir = 		prefs.get(String.class, 			"OPM-batch-inputDir", 			inputDir);
				keywords = 		prefs.get(String.class, 			"OPM-batch-keywords", 			keywords);
				doDeskew = 		prefs.getBoolean(Boolean.class, 	"OPM-batch-doDeskew", 			doDeskew);	
				fileExistString=prefs.get(String.class, 			"OPM-batch-fileExistString", 	fileExistString);
				//overwriteExist =prefs.getBoolean(Boolean.class, 	"OPM-batch-overwriteExist", 	overwriteExist);
				xyPixelSize = 	prefs.getDouble(Double.class, 		"OPM-batch-xyPixelSize", 		xyPixelSize);
				zStepSize = 	prefs.getDouble(Double.class, 		"OPM-batch-zStepSize", 			zStepSize);
				opmAngle = 		prefs.getDouble(Double.class, 		"OPM-batch-opmAngle", 			opmAngle);
				channelString = prefs.get(String.class, 			"OPM-batch-channelString", 		channelString);
				projX = 		prefs.getBoolean(Boolean.class, 	"OPM-batch-projX", 				projX);
				projY = 		prefs.getBoolean(Boolean.class, 	"OPM-batch-projY", 				projY);
				projZ = 		prefs.getBoolean(Boolean.class, 	"OPM-batch-projZ", 				projZ);
				maxProj = 		prefs.getBoolean(Boolean.class, 	"OPM-batch-maxProj", 			maxProj);
				avgProj = 		prefs.getBoolean(Boolean.class, 	"OPM-batch-avgProj", 			avgProj);
				minProj = 		prefs.getBoolean(Boolean.class, 	"OPM-batch-minProj", 			minProj);
				sumProj = 		prefs.getBoolean(Boolean.class, 	"OPM-batch-sumProj", 			sumProj);
				medProj = 		prefs.getBoolean(Boolean.class, 	"OPM-batch-medProj", 			medProj);
				stdProj = 		prefs.getBoolean(Boolean.class, 	"OPM-batch-stdProj", 			stdProj);
				makeTimeLapse = prefs.getBoolean(Boolean.class, 	"OPM-batch-makeTimeLapse", 		makeTimeLapse);
				saveDir = 		prefs.get(String.class, 			"OPM-batch-saveDir", 			saveDir);
				saveSeparate = 	prefs.getBoolean(Boolean.class, 	"OPM-batch-saveSeparate", 		saveSeparate);
				//saveLog = 		prefs.getBoolean(Boolean.class, 	"OPM-batch-saveLog", 			saveLog);	
				break;
			
				
			case "watcher":
				watchDir = 		prefs.get(String.class, 			"OPM-watcher-watchDir", 		watchDir);
				keywords = 		prefs.get(String.class, 			"OPM-watcher-keywords", 		keywords);
				processOld = 	prefs.getBoolean(Boolean.class, 	"OPM-watcher-processOld", 		processOld);
				overwriteExist =prefs.getBoolean(Boolean.class, 	"OPM-watcher-overwriteExist", 	overwriteExist);
				maxWait = 		prefs.getInt(Integer.class, 		"OPM-watcher-maxWait", 			maxWait);
				logPath = 		prefs.get(String.class, 			"OPM-watcher-watchLog", 		logPath);
				xyPixelSize =	prefs.getDouble(Double.class, 		"OPM-watcher-xyPixelSize", 		xyPixelSize);
				zStepSize = 	prefs.getDouble(Double.class, 		"OPM-watcher-zStepSize", 		zStepSize);
				opmAngle =	 	prefs.getDouble(Double.class, 		"OPM-watcher-opmAngle", 		opmAngle);
				//tryGPU = 		prefs.getBoolean(Boolean.class, 	"OPM-watcher-tryGPU", 			tryGPU);
				channelString = prefs.get(String.class, 			"OPM-watcher-channelString", 	channelString);
				projX = 		prefs.getBoolean(Boolean.class, 	"OPM-watcher-projX", 			projX);
				projY = 		prefs.getBoolean(Boolean.class, 	"OPM-watcher-projY", 			projY);
				projZ = 		prefs.getBoolean(Boolean.class, 	"OPM-watcher-projZ", 			projZ);
				maxProj = 		prefs.getBoolean(Boolean.class, 	"OPM-watcher-maxProj", 			maxProj);
				avgProj = 		prefs.getBoolean(Boolean.class, 	"OPM-watcher-avgProj", 			avgProj);
				minProj = 		prefs.getBoolean(Boolean.class, 	"OPM-watcher-minProj", 			minProj);
				sumProj = 		prefs.getBoolean(Boolean.class, 	"OPM-watcher-sumProj", 			sumProj);
				medProj = 		prefs.getBoolean(Boolean.class, 	"OPM-watcher-medProj", 			medProj);
				stdProj = 		prefs.getBoolean(Boolean.class, 	"OPM-watcher-stdProj", 			stdProj);
				makeTimeLapse = prefs.getBoolean(Boolean.class, 	"OPM-watcher-makeTimeLapse", 	makeTimeLapse);
				saveDir = 		prefs.get(String.class, 			"OPM-watcher-saveDir", 			saveDir);
				saveSeparate = 	prefs.getBoolean(Boolean.class, 	"OPM-watcher-saveSeparate", 	saveSeparate);
				break;
			
			case "transform":
				saveDir = 		prefs.get(String.class, 			"OPM-transform-saveDir", 		saveDir);
				break;
				
			case "permutation":
				flipX = 		prefs.getBoolean(Boolean.class, 	"OPM-permute-flipX", 			flipX);
				flipY = 		prefs.getBoolean(Boolean.class, 	"OPM-permute-flipY", 			flipY);
				flipZ = 		prefs.getBoolean(Boolean.class, 	"OPM-permute-flipZ", 			flipZ);
				foldX = 		prefs.getBoolean(Boolean.class, 	"OPM-permute-foldX", 			foldX);
				permuteString = prefs.get(String.class, 			"OPM-permute-permuteString",	permuteString);
				tryGPU = 		prefs.getBoolean(Boolean.class, 	"OPM-permute-tryGPU", 			tryGPU);
				break;
			
				
			case "projection":
				projX = 		prefs.getBoolean(Boolean.class, 	"OPM-project-projX", 			projX);
				projY = 		prefs.getBoolean(Boolean.class, 	"OPM-project-projY", 			projY);
				projZ = 		prefs.getBoolean(Boolean.class, 	"OPM-project-projZ", 			projZ);
				maxProj =		prefs.getBoolean(Boolean.class,		"OPM-project-maxProj", 			maxProj);
				avgProj = 		prefs.getBoolean(Boolean.class, 	"OPM-project-avgProj", 			avgProj);
				minProj = 		prefs.getBoolean(Boolean.class, 	"OPM-project-minProj", 			minProj);
				sumProj = 		prefs.getBoolean(Boolean.class, 	"OPM-project-sumProj", 			sumProj);
				medProj = 		prefs.getBoolean(Boolean.class, 	"OPM-project-medProj", 			medProj);
				stdProj = 		prefs.getBoolean(Boolean.class, 	"OPM-project-stdProj", 			stdProj);
				tryGPU = 		prefs.getBoolean(Boolean.class, 	"OPM-project-tryGPU", 			tryGPU);
				break;
			
				
			case "debug":
				// debug Input
				inputDir = 			prefs.get(String.class, 		"OPM-debug-inputDir", 			inputDir);
				watchDir = 			prefs.get(String.class, 		"OPM-debug-watchDir", 			watchDir);
				keywords = 			prefs.get(String.class, 		"OPM-debug-keywords", 			keywords);
				maxWait = 			prefs.getInt(Integer.class, 	"OPM-debug-maxWait", 			maxWait);
				// debug Processing
				xyPixelSize = 		prefs.getDouble(Double.class, 	"OPM-debug-xyPixelSize", 		xyPixelSize);
				zStepSize = 		prefs.getDouble(Double.class, 	"OPM-debug-zStepSize", 			zStepSize);
				opmAngle = 			prefs.getDouble(Double.class, 	"OPM-debug-opmAngle", 			opmAngle);
				doVirtual = 		prefs.getBoolean(Boolean.class, "OPM-debug-doVirtual", 			doVirtual);
				tryGPU = 			prefs.getBoolean(Boolean.class, "OPM-debug-tryGPU", 			tryGPU);
				autoPartitionData = prefs.getBoolean(Boolean.class, "OPM-debug-autoPartitionData", 	autoPartitionData);
				numPartition = 		prefs.getInt(Integer.class, 	"OPM-debug-numPartition", 		numPartition);
				channelString = 	prefs.get(String.class, 		"OPM-debug-channelString", 		channelString);
				foldX = 			prefs.getBoolean(Boolean.class, "OPM-debug-foldX", 				foldX);
				projX = 			prefs.getBoolean(Boolean.class, "OPM-debug-projX", 				projX);
				projY = 			prefs.getBoolean(Boolean.class, "OPM-debug-projY", 				projY);
				projZ = 			prefs.getBoolean(Boolean.class, "OPM-debug-projZ", 				projZ);
				maxProj = 			prefs.getBoolean(Boolean.class, "OPM-debug-maxProj", 			maxProj);
				avgProj = 			prefs.getBoolean(Boolean.class, "OPM-debug-avgProj", 			avgProj);
				minProj = 			prefs.getBoolean(Boolean.class, "OPM-debug-minProj", 			minProj);
				sumProj = 			prefs.getBoolean(Boolean.class, "OPM-debug-sumProj", 			sumProj);
				medProj = 			prefs.getBoolean(Boolean.class, "OPM-debug-medProj", 			medProj);
				stdProj = 			prefs.getBoolean(Boolean.class, "OPM-debug-stdProj", 			stdProj);
				makeTimeLapse =	  	prefs.getBoolean(Boolean.class, "OPM-debug-makeTimeLapse", 		makeTimeLapse);
				doInverse =			prefs.getBoolean(Boolean.class, "OPM-debug-doInverse", 			doInverse);
				doStepTransform = 	prefs.getBoolean(Boolean.class, "OPM-debug-doStepTransform", 	doStepTransform);
				// debug Output
				saveDir = 			prefs.get(String.class, 		"OPM-debug-saveDir", 			saveDir);
				saveSeparate = 		prefs.getBoolean(Boolean.class, "OPM-debug-saveSeparate", 		saveSeparate);
				processOld =		prefs.getBoolean(Boolean.class, "OPM-debug-processOld", 		processOld);
				overwriteExist =	prefs.getBoolean(Boolean.class, "OPM-debug-overwriteExist", 	overwriteExist);
				logPath = 			prefs.get(String.class, 		"OPM-debug-watchLog", 			logPath);
				//saveLog = 			prefs.getBoolean(Boolean.class,	"OPM-debug-batchLog", 			saveLog);	
				break;
			
				
			default:	// no match case, TODO: consider reset all parameters?
			}
			*/
		}
		
		/**				Create parameter dialog for Deskew Image command
		 * 
		 * @return
		 */
		public boolean deskew_image() {
			// create parameter dialog
			/*
			GenericDialogPlus gd = new GenericDialogPlus("Deskew Image");
			gd.setBackground( frameColor );
			gd.addImageChoice("select active image", this.impInput.getTitle());
			gd.addNumericField("XY pixel size", xyPixelSize, 1, 5, "nm");
			gd.addNumericField("Z step size", zStepSize, 1, 5, "nm");
			gd.addNumericField("OPM angle", opmAngle, 1, 5, "°");
			gd.addFileField("", matrixMessage);
			//gd.addCheckbox("(V)irtual stack", doVirtual);
			//gd.addCheckbox("GPU processing", tryGPU);
			//gd.addMessage("\timage channel option");
			gd.addChoice("channel option", channelOptions, channelString);
			gd.addMessage("\tshow projection image(s):");
			String[] label_axis = {"along X", "along Y", "along Z"};
			boolean[] state_axis = {projX, projY, projZ};				
			gd.addCheckboxGroup(1, 3, label_axis, state_axis);		
			String[] label_type = {"maximum", "mean", "minimum", "sum", "median", "standard deviation"};
			boolean[] state_type = {maxProj, avgProj, minProj, sumProj, medProj, stdProj};				
			
			gd.addCheckboxGroup(2, 3, label_type, state_type);
			
			gd.showDialog();

			if (gd.wasCanceled()) return false;
	        
	        impInput = 		gd.getNextImage();
	        xyPixelSize = 	gd.getNextNumber();
	        zStepSize = 	gd.getNextNumber();
	        opmAngle = 		gd.getNextNumber();
	        parameterFile = 	gd.getNextString();
	        //doVirtual = 	gd.getNextBoolean();
	        //tryGPU = 		gd.getNextBoolean();
	        channelString = gd.getNextChoice();
	        projX = 		gd.getNextBoolean();
	        projY = 		gd.getNextBoolean();
	        projZ = 		gd.getNextBoolean();
	        maxProj = 		gd.getNextBoolean();
	        avgProj = 		gd.getNextBoolean();
	        minProj = 		gd.getNextBoolean();
	        sumProj = 		gd.getNextBoolean();
	        medProj = 		gd.getNextBoolean();
	        stdProj = 		gd.getNextBoolean();
	        //remove file extension from image title 
	        int dotIdx = impInput.getTitle().lastIndexOf(".");
			if (-1 != dotIdx) impInput.setTitle(impInput.getTitle().substring(0, dotIdx));
	        // store parameter values
	        DefaultPrefService prefs = new DefaultPrefService();
			prefs.put(Double.class,  	"OPM-image-xyPixelSize",   	xyPixelSize);
			prefs.put(Double.class,  	"OPM-image-zStepSize",     	zStepSize);
			prefs.put(Double.class,  	"OPM-image-opmAngle",      	opmAngle);
			//prefs.put(Boolean.class, 	"OPM-image-doVirtual",     	doVirtual);
			//prefs.put(Boolean.class, 	"OPM-image-tryGPU",        	tryGPU);
			prefs.put(String.class,  	"OPM-image-channelString", 	channelString);
			prefs.put(Boolean.class, 	"OPM-image-projX",         	projX);
			prefs.put(Boolean.class, 	"OPM-image-projY",         	projY);
			prefs.put(Boolean.class, 	"OPM-image-projZ",         	projZ);
			prefs.put(Boolean.class, 	"OPM-image-maxProj",       	maxProj);
			prefs.put(Boolean.class, 	"OPM-image-avgProj",       	avgProj);
			prefs.put(Boolean.class, 	"OPM-image-minProj",       	minProj);
			prefs.put(Boolean.class, 	"OPM-image-sumProj",       	sumProj);
			prefs.put(Boolean.class, 	"OPM-image-medProj",       	medProj);
			prefs.put(Boolean.class, 	"OPM-image-stdProj",       	stdProj);
			*/
			return true;
		}
		
// TODO: IO
		/**
		 * 
		 * @param filePath
		 * @param imageHeight
		 * @return
		 */
		public static double[] loadSettingsFromFile (
				String filePath,
				double imageHeight
				) {
			if (filePath.endsWith("ExperimentalParameters.txt")) {
				double[] params = IO.loadExperimentalParametersFromFile( filePath ); //xyPixelSize, zStepSize, opmAngle
				double dxy = params[0];
				double dz = params[1];
				double angle = params[2];
				if (dxy==Double.NaN || dz==Double.NaN || angle==Double.NaN ) return null;
				return new double[] { dz/dxy, angle, imageHeight*Utils.sin(angle) };	
			}
			
			if (filePath.endsWith(".csv")) {
				
				if (filePath.toLowerCase().contains("matrix")) {
					double[][] deskew_matrix = IO.loadMatrixFromFile( filePath );
			    	if (null == deskew_matrix) return null;
					// try to calculate input parameter: z step, angle, and z-translate amount
					return Transform.reverse_deskew ( deskew_matrix, imageHeight );
				} 
				
				//if (filePath.toLowerCase().contains("transform")) {
				//	
				//}
			}
			return null;	
		}
		
//TODO:	Utils
		
		public static void displayImage ( ImagePlus imp, String name ) {
			/*
			 * image exist: update image;
			 * image not exist: display image;
			 * 
			 * image exist: keep window location, keep BC
			 * image not exsit: auto BC (for each channel)
			 * 
			 * regardless: update Z focus slice, update composite mode (for 2C)
			 * composite - noComposite: udpate BC;
			 * noComposite - composite: update BC;
			 */
			if ( null == imp ) return;
			double min = Double.NaN; double max = Double.NaN;
			int numC = imp.getNChannels();
			LUT[] luts = new LUT[numC];
			
			ImagePlus imp_display = WindowManager.getImage( name );
			boolean imageExist = (null != imp_display);
			if ( imageExist ) {
				if ( imp_display.isComposite() ) {
					for (int c=0; c<numC; c++) {
						luts[c] = ((CompositeImage)imp_display).getChannelLut( c+1 );
					}
	            //img2.setDisplayRange(lut.min, lut.max);
	            //img2.updateAndDraw();
				} else {
					min = imp_display.getDisplayRangeMin();
					max = imp_display.getDisplayRangeMax();
				}
				
				imp_display.setImage( imp );

			} else {
				
				imp_display = imp;
				imp_display.setTitle ( name );
				imp_display.show();
			}
			
			//if (imp_display.getNSlices() > 1) imp_display.setZ ( findFocusSlice (imp_display) );
			int numZ = imp_display.getNSlices();
			if (numZ > 1) imp_display.setZ ( Utils.findFocusSlice(imp_display) );
			
			//int numC = imp_display.getNChannels();
			if (numC > 1) {
				if ( !imp_display.isComposite() ) {
					CompositeImage ci = new CompositeImage(imp_display, CompositeImage.COMPOSITE);
					if ( imp.getBitDepth() != 8 ) {
				        ci.reset();
				        ci.resetDisplayRanges();
				    }
				    ImageWindow win = imp.getWindow();
				    Point location = (win!=null) ? win.getLocation() : null;
				    imp_display.hide();
				    if ( location!=null )  ImageWindow.setNextLocation ( location );
				    ci.show();
				    imp_display = ci;
				} else {
					//imp_display = CompositeConverter.makeComposite( imp_display );
					imp_display.setDisplayMode(IJ.COMPOSITE);
					for (int c=0; c<numC; c++) {
						imp_display.setC ( c+1 );
						//ImageProcessor channelProcessor = ( (CompositeImage) imp_display).getProcessor( c+1 );
						if (imageExist) { 
							imp_display.setDisplayRange(luts[c].min, luts[c].max);
						} else {
							ImageProcessor channelProcessor = imp_display.getProcessor();
							int[] MinMax = Utils.getMinAndMax ( channelProcessor, 0.35d );
							channelProcessor.setMinAndMax ( MinMax[0], MinMax[1] );
						}
						imp_display.updateChannelAndDraw();
					}
					imp_display.setC ( 1 );
				}
			} else {
				//new ContrastEnhancer().stretchHistogram(imp_display.getProcessor(), 0.35d);
				ImageProcessor ip = imp_display.getProcessor();
				if ( !imageExist ) {
					int[] MinMax = Utils.getMinAndMax ( ip, 0.35d );
					min = MinMax[0]; max = MinMax[1];
				} 
				ip.setMinAndMax(min, max);
				imp_display.updateAndDraw(); //imp_display.updateAndRepaintWindow();
			}
			
			imp_display.changes = false;
		}
}
