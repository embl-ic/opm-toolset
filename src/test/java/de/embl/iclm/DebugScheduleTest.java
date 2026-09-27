package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.Test;

/**
 * The exact clock rule for the window theme.
 *
 * <p>There is deliberately no probability in this rule. On an eligible computer, auto mode is on
 * outside office hours and strictly off during both office-hours periods.
 */
public class DebugScheduleTest {

	private static final ZoneId UTC = ZoneOffset.UTC;

	private static long at (int year, int month, int day, int hour, int minute) {
		return LocalDateTime.of ( year, month, day, hour, minute )
				.atZone ( UTC ).toInstant().toEpochMilli();
	}

	/** 2026-09-21 is a Monday. */
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
						Debug.Theme.Schedule.outsideOfficeHours (
								LocalDateTime.of ( 2026, 9, day, hour, 0 ) ) );
	}

	@Test
	public void autoScheduleIsOnOnlyOutsideOfficeHours () {
		Debug.Theme.Schedule schedule = new Debug.Theme.Schedule ( UTC );

		assertTrue ( "Monday 07:00", schedule.themed ( monday ( 7, 0 ) ) );
		assertFalse ( "Monday 08:00", schedule.themed ( monday ( 8, 0 ) ) );
		assertFalse ( "Monday 11:44", schedule.themed ( monday ( 11, 44 ) ) );
		assertTrue ( "Monday 11:45", schedule.themed ( monday ( 11, 45 ) ) );
		assertTrue ( "Monday 12:29", schedule.themed ( monday ( 12, 29 ) ) );
		assertFalse ( "Monday 12:30", schedule.themed ( monday ( 12, 30 ) ) );
		assertFalse ( "Monday 16:59", schedule.themed ( monday ( 16, 59 ) ) );
		assertTrue ( "Monday 17:00", schedule.themed ( monday ( 17, 0 ) ) );
		assertTrue ( "Saturday noon", schedule.themed ( at ( 2026, 9, 26, 12, 0 ) ) );
	}

	@Test
	public void thePersistentOffPreferenceOverridesEveryTimePeriod () {
		Debug.Theme.Schedule schedule = new Debug.Theme.Schedule ( UTC );
		schedule.disable();

		assertTrue ( schedule.disabled() );
		assertFalse ( "before work", schedule.themed ( monday ( 7, 0 ) ) );
		assertFalse ( "at lunch", schedule.themed ( monday ( 12, 0 ) ) );
		assertFalse ( "after work", schedule.themed ( monday ( 18, 0 ) ) );
		assertFalse ( "at the weekend", schedule.themed ( at ( 2026, 9, 26, 12, 0 ) ) );
	}


	// ---- helpers ---------------------------------------------------------------------

	private static void assertInside (String what, int hour, int minute) {
		assertFalse ( what + " is office hours", Debug.Theme.Schedule.outsideOfficeHours (
				LocalDateTime.of ( 2026, 9, 21, hour, minute ) ) );
	}

	private static void assertOutside (String what, int hour, int minute) {
		assertTrue ( what + " is outside office hours", Debug.Theme.Schedule.outsideOfficeHours (
				LocalDateTime.of ( 2026, 9, 21, hour, minute ) ) );
	}
}
