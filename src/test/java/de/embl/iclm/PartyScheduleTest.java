package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Random;

import org.junit.Test;

/**
 * When the party theme comes on, and how often that adds up to.
 *
 * <p>{@link Party.Schedule} is the whole rule and takes the clock as an argument, which is what
 * lets the second half of this test measure the thing that actually matters about an easter egg:
 * how much of the time it is showing. The answer is not obvious from the rule, because the
 * unconditional out-of-hours case contributes whatever share of the work happens out of hours -
 * a number about the users, not about the code - so it is measured against a stated usage
 * profile rather than asserted from first principles.
 *
 * <p>The target is 20-30% of commands run. {@link Party#OFFICE_CHANCE} is the one knob; raising
 * it fails {@link #theThemeShowsOnAFifthToAThirdOfCommands}, and the message says by how much.
 */
public class PartyScheduleTest {

	private static final ZoneId UTC = ZoneOffset.UTC;

	/** Always rolls a hit, so the trigger under test is the only thing deciding. */
	private static Random always () {
		return new Random() {
			private static final long serialVersionUID = 1L;
			@Override public double nextDouble () { return 0d; }
		};
	}

	/** Never rolls a hit, so only the unconditional out-of-hours rule can show. */
	private static Random never () {
		return new Random() {
			private static final long serialVersionUID = 1L;
			@Override public double nextDouble () { return 1d; }
		};
	}

	private static long at (int year, int month, int day, int hour, int minute) {
		return LocalDateTime.of ( year, month, day, hour, minute )
				.atZone ( UTC ).toInstant().toEpochMilli();
	}

	/** 2026-09-21 is a Monday, 2026-09-26 a Saturday. */
	private static long monday (int hour, int minute) {
		return at ( 2026, 9, 21, hour, minute );
	}


	// ---- the rule ---------------------------------------------------------------------

	@Test
	public void officeHoursAreWeekdayMorningsAndAfternoonsAndNothingElse () {
		assertEquals ( DayOfWeek.MONDAY,
				LocalDateTime.of ( 2026, 9, 21, 9, 0 ).getDayOfWeek() );

		assertOutside ( "before eight", 7, 59 );
		assertInside  ( "eight",        8,  0 );
		assertInside  ( "late morning", 11, 44 );
		assertOutside ( "lunch starts", 11, 45 );
		assertOutside ( "lunch",        12,  0 );
		assertOutside ( "lunch ends",   12, 29 );
		assertInside  ( "back at work", 12, 30 );
		assertInside  ( "afternoon",    16, 59 );
		assertOutside ( "five",         17,  0 );
		assertOutside ( "evening",      21, 30 );
	}

	@Test
	public void theWholeWeekendIsOutsideOfficeHours () {
		for (int day = 26; day <= 27; day++)			// Saturday and Sunday
			for (int hour = 0; hour < 24; hour += 3)
				assertTrue ( "2026-09-" + day + " " + hour + ":00",
						Party.Schedule.outsideOfficeHours (
								LocalDateTime.of ( 2026, 9, day, hour, 0 ) ) );
	}

	@Test
	public void outsideOfficeHoursTheThemeIsOnWithoutAnyRoll () {
		Party.Schedule schedule = new Party.Schedule ( never(), UTC );
		assertTrue ( "Monday 07:00", schedule.party ( monday ( 7, 0 ) ) );
		assertTrue ( "Monday 12:00", schedule.party ( monday ( 12, 0 ) ) );
		assertTrue ( "Monday 18:00", schedule.party ( monday ( 18, 0 ) ) );
		assertFalse ( "Monday 09:00", schedule.party ( monday ( 9, 0 ) ) );
	}

	@Test
	public void anOfficeHoursCommandRollsForABurstThatLastsAMinute () {
		Party.Schedule schedule = new Party.Schedule ( always(), UTC );
		long start = monday ( 9, 0 );
		assertFalse ( "nothing before the command", schedule.party ( start ) );

		schedule.commandStarted ( "Batch Processing > Deskew", start );
		assertTrue ( "the command brought it on", schedule.party ( start ) );
		assertTrue ( "still on at 59 s", schedule.party ( start + 59 * 1000L ) );
		assertFalse ( "off again at 60 s", schedule.party ( start + 60 * 1000L ) );
	}

	@Test
	public void aRolledMissLeavesTheOfficeBlueAlone () {
		Party.Schedule schedule = new Party.Schedule ( never(), UTC );
		long start = monday ( 9, 0 );
		schedule.commandStarted ( "Batch Processing > Deskew", start );
		assertFalse ( schedule.party ( start ) );
	}

	@Test
	public void aQuarterOfAnHourOfUnbrokenWorkRollsForABurst () {
		Party.Schedule schedule = new Party.Schedule ( always(), UTC );
		long start = monday ( 9, 0 );
		for (int minute = 0; minute < 15; minute++)
			schedule.interaction ( start + minute * 60L * 1000L );
		assertFalse ( "fourteen minutes is not fifteen",
				schedule.party ( start + 14 * 60L * 1000L ) );

		long milestone = start + 15 * 60L * 1000L;
		schedule.interaction ( milestone );
		assertTrue ( "a quarter of an hour in", schedule.party ( milestone ) );
	}

	@Test
	public void aRealBreakStartsTheQuarterOfAnHourAgain () {
		Party.Schedule schedule = new Party.Schedule ( always(), UTC );
		long start = monday ( 9, 0 );
		for (int minute = 0; minute < 10; minute++)
			schedule.interaction ( start + minute * 60L * 1000L );

		// away for four minutes, which is longer than IDLE_MS: the ten minutes are forfeited
		long back = start + 13 * 60L * 1000L;
		for (int minute = 0; minute < 15; minute++)
			schedule.interaction ( back + minute * 60L * 1000L );
		assertFalse ( "the streak restarted when they came back",
				schedule.party ( back + 14 * 60L * 1000L ) );

		long milestone = back + 15 * 60L * 1000L;
		schedule.interaction ( milestone );
		assertTrue ( "and the milestone is a quarter of an hour after that",
				schedule.party ( milestone ) );
	}

	@Test
	public void aLongSessionEarnsAtMostOneRollAQuarterOfAnHour () {
		final int[] rolls = { 0 };
		Random counted = new Random() {
			private static final long serialVersionUID = 1L;
			@Override public double nextDouble () { rolls[0]++; return 1d; }
		};
		Party.Schedule schedule = new Party.Schedule ( counted, UTC );
		long start = monday ( 9, 0 );
		for (int minute = 0; minute <= 60; minute++)
			schedule.interaction ( start + minute * 60L * 1000L );
		assertEquals ( "four milestones in an hour of work", 4, rolls[0] );
	}


	// ---- the session switch -----------------------------------------------------------

	@Test
	public void theEnvironmentReportRunFirstTurnsTheThemeOffForTheSession () {
		Party.Schedule schedule = new Party.Schedule ( always(), UTC );
		schedule.commandStarted ( Party.ENVIRONMENT_REPORT, monday ( 9, 0 ) );
		assertTrue ( schedule.disabled() );

		// not even out of hours, and not for any command that follows
		assertFalse ( "Saturday", schedule.party ( at ( 2026, 9, 26, 15, 0 ) ) );
		schedule.commandStarted ( "Batch Processing > Deskew", monday ( 9, 30 ) );
		assertFalse ( "a later command cannot bring it back", schedule.party ( monday ( 9, 30 ) ) );
		schedule.interaction ( monday ( 9, 30 ) );
		schedule.interaction ( monday ( 9, 46 ) );
		assertFalse ( "nor can a long session", schedule.party ( monday ( 9, 46 ) ) );
	}

	@Test
	public void theEnvironmentReportRunLaterChangesNothing () {
		Party.Schedule schedule = new Party.Schedule ( never(), UTC );
		schedule.commandStarted ( "Utilities > Projection", monday ( 9, 0 ) );
		schedule.commandStarted ( Party.ENVIRONMENT_REPORT, monday ( 9, 1 ) );
		assertFalse ( "the switch is the first command only", schedule.disabled() );
		assertTrue ( "so the evening still parties", schedule.party ( monday ( 18, 0 ) ) );
	}


	// ---- how often, over a simulated year of use --------------------------------------

	/**
	 * The share of OPM commands that come up in the party theme, over three simulated years.
	 *
	 * <p>Measured against {@link #FACILITY}, an hour-by-hour usage profile for a shared
	 * microscope: busy through the working day, a thin evening tail, and next to nothing at
	 * night or at the weekend. The profile is the assumption in this test, and it is the only
	 * one: everything else is the rule as shipped.
	 */
	@Test
	public void theThemeShowsOnAFifthToAThirdOfCommands () {
		Measurement facility = simulate ( FACILITY, 4931 );
		System.out.println ( "party theme, facility profile : " + facility );
		assertTrue ( "with " + facility + " the easter egg is too rare to find",
				facility.share() >= 0.20d );
		assertTrue ( "with " + facility + " it is not an easter egg any more",
				facility.share() <= 0.30d );
	}

	/**
	 * The same rule against a group that works late, which is the worst case for the budget.
	 *
	 * <p>Out of office hours the theme is unconditional, so the more of the work that happens
	 * then, the higher the total - and nothing in the code can hold it down. This pins how far
	 * that can go before {@link Party#OFFICE_CHANCE} would have to come down with it.
	 */
	@Test
	public void evenAGroupThatWorksLateStaysUnderAThird () {
		Measurement late = simulate ( LATE_SHIFTS, 77 );
		System.out.println ( "party theme, late-shift profile: " + late );
		assertTrue ( "with " + late + " the theme is showing too much", late.share() <= 0.30d );
	}

	/** What the rule looks like with the in-hours roll taken out: the floor it cannot go below. */
	@Test
	public void outOfHoursWorkAloneAccountsForMostOfTheBudget () {
		Measurement floor = simulate ( FACILITY, 4931, never() );
		System.out.println ( "party theme, out-of-hours only : " + floor );
		assertTrue ( "out-of-hours work alone is " + floor, floor.share() >= 0.12d );
		assertTrue ( "out-of-hours work alone is " + floor, floor.share() <= 0.25d );
	}


	// ---- the simulation ---------------------------------------------------------------

	/**
	 * Commands per minute, relative, by hour of the day - weekdays then weekend.
	 *
	 * <p>Index 0..23 is Monday to Friday, 24..47 the same hours at the weekend. Only the ratios
	 * matter; {@link #COMMANDS_PER_BUSY_MINUTE} sets the absolute rate.
	 */
	private static final double[] FACILITY = {
		//  00    01    02    03    04    05    06    07    08    09    10    11
		 0.03, 0.03, 0.03, 0.03, 0.03, 0.03, 0.03, 0.20, 1.00, 1.00, 1.00, 1.00,
		//  12    13    14    15    16    17    18    19    20    21    22    23
		 0.60, 1.00, 1.00, 1.00, 1.00, 0.25, 0.25, 0.06, 0.06, 0.06, 0.02, 0.02,
		// weekend
		 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.06, 0.06,
		 0.06, 0.06, 0.06, 0.06, 0.06, 0.06, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01
	};

	/** The same shape with a much heavier evening and weekend, as a sensitivity check. */
	private static final double[] LATE_SHIFTS = {
		 0.03, 0.03, 0.03, 0.03, 0.03, 0.03, 0.03, 0.30, 1.00, 1.00, 1.00, 1.00,
		 0.60, 1.00, 1.00, 1.00, 1.00, 0.35, 0.35, 0.12, 0.12, 0.12, 0.03, 0.03,
		 0.02, 0.02, 0.02, 0.02, 0.02, 0.02, 0.02, 0.02, 0.02, 0.02, 0.10, 0.10,
		 0.10, 0.10, 0.10, 0.10, 0.10, 0.10, 0.02, 0.02, 0.02, 0.02, 0.02, 0.02
	};

	/** Commands a minute where the profile is 1.0: one every five minutes. */
	private static final double COMMANDS_PER_BUSY_MINUTE = 0.2d;
	/** How long after a command the user counts as still at the machine. */
	private static final int AT_THE_MACHINE_MINUTES = 20;
	private static final int YEARS = 3;

	private static final class Measurement {
		int commands;
		int party;

		double share () {
			return commands == 0 ? 0d : party / (double) commands;
		}

		@Override public String toString () {
			return String.format ( "%.1f%% of %d commands", 100d * share(), commands );
		}
	}

	private static Measurement simulate (double[] profile, long seed) {
		return simulate ( profile, seed, new Random ( seed ) );
	}

	/**
	 * Walk three years a minute at a time, starting commands at the profile's rate.
	 *
	 * <p>The arrivals and the rule are driven by two separate generators, so changing the
	 * probability cannot change which minutes a command lands in and the two profiles stay
	 * comparable.
	 */
	private static Measurement simulate (double[] profile, long seed, Random dice) {
		Random arrivals = new Random ( seed );
		Party.Schedule schedule = new Party.Schedule ( dice, UTC );
		Measurement measured = new Measurement();
		LocalDateTime when = LocalDateTime.of ( 2026, 1, 1, 0, 0 );
		LocalDateTime end = when.plusYears ( YEARS );
		long lastCommandMinute = Long.MIN_VALUE;
		for (long minute = 0; when.isBefore ( end ); minute++, when = when.plusMinutes ( 1 )) {
			DayOfWeek day = when.getDayOfWeek();
			boolean weekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
			double weight = profile[ ( weekend ? 24 : 0 ) + when.getHour() ];
			long now = when.atZone ( UTC ).toInstant().toEpochMilli();
			if (arrivals.nextDouble() < weight * COMMANDS_PER_BUSY_MINUTE) {
				schedule.commandStarted ( "Batch Processing > Deskew", now );
				measured.commands++;
				if (schedule.party ( now )) measured.party++;
				lastCommandMinute = minute;
			}
			/* Somebody who has started something in the last twenty minutes is at the machine,
			 * typing into its dialogs - which is what the persistence trigger watches. */
			if (minute - lastCommandMinute <= AT_THE_MACHINE_MINUTES) schedule.interaction ( now );
		}
		return measured;
	}


	// ---- helpers ---------------------------------------------------------------------

	private static void assertInside (String what, int hour, int minute) {
		assertFalse ( what + " is office hours", Party.Schedule.outsideOfficeHours (
				LocalDateTime.of ( 2026, 9, 21, hour, minute ) ) );
	}

	private static void assertOutside (String what, int hour, int minute) {
		assertTrue ( what + " is outside office hours", Party.Schedule.outsideOfficeHours (
				LocalDateTime.of ( 2026, 9, 21, hour, minute ) ) );
	}
}
