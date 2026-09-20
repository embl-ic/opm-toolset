package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Regression tests for the parameter persistence, which is now driven by the
 * {@code @Persist} annotation instead of two hand-written lists of preference keys.
 *
 * <p>The two lists could disagree - a setting present in one and missing from the other was
 * loaded but never stored, or stored but never restored - and neither the compiler nor a
 * reader could tell. Deriving both directions from the same annotated fields makes that class
 * of bug unrepresentable, and these tests pin the two properties that matters:
 *
 * <ul>
 * <li>the preference key is still {@code "OPM-<obj>-<field name>"}, which is exactly what the
 *     old lists spelled out, so settings saved by 2.1.5 and earlier are still read;</li>
 * <li>every annotated field survives a store then load cycle, whatever its type.</li>
 * </ul>
 */
public class ParameterPersistenceTest {

	/** A scope of its own, so the test never disturbs a real dialog's saved settings. */
	private static final String TEST_SCOPE = "junit-persistence";

	private static List<Field> persistedFields () {
		List<Field> fields = new ArrayList<Field>();
		for ( Field field : Parameter.class.getDeclaredFields() ) {
			if ( field.isAnnotationPresent( Parameter.Persist.class ) ) {
				field.setAccessible ( true );
				fields.add ( field );
			}
		}
		return fields;
	}

	@Test
	public void everyPersistedFieldIsASupportedType () {
		for ( Field field : persistedFields() ) {
			Class<?> type = field.getType();
			boolean supported = double.class.equals(type) || int.class.equals(type)
					|| boolean.class.equals(type) || String.class.equals(type);
			assertTrue ( field.getName() + " is annotated @Persist but has unsupported type " + type,
					supported );
		}
	}

	@Test
	public void noPersistedFieldIsStaticOrFinal () {
		for ( Field field : persistedFields() ) {
			int modifiers = field.getModifiers();
			assertFalse ( field.getName() + " must not be static to be persisted per dialog",
					java.lang.reflect.Modifier.isStatic( modifiers ) );
			assertFalse ( field.getName() + " must not be final: loading has to write it",
					java.lang.reflect.Modifier.isFinal( modifiers ) );
		}
	}

	@Test
	public void theDeskewSettingsSurviveAStoreAndLoadCycle () throws Exception {
		Parameter written = new Parameter ( TEST_SCOPE );
		written.xyPixelSize = 97.5d;
		written.zStepSize = 265.0d;
		written.opmAngle = 31.5d;
		written.channelStr = "align with SIFT";
		written.recursive = true;
		written.numIter = 42;
		written.storeParam();

		Parameter read = new Parameter ( TEST_SCOPE );
		assertEquals ( 97.5d, read.xyPixelSize, 1e-9 );
		assertEquals ( 265.0d, read.zStepSize, 1e-9 );
		assertEquals ( 31.5d, read.opmAngle, 1e-9 );
		assertEquals ( "align with SIFT", read.channelStr );
		assertTrue ( read.recursive );
		assertEquals ( 42, read.numIter );
	}

	@Test
	public void everyPersistedFieldRoundTripsWhateverItsType () throws Exception {
		Parameter written = new Parameter ( TEST_SCOPE + "-all" );
		// give every field a value that differs from its declared default
		for ( Field field : persistedFields() ) {
			Class<?> type = field.getType();
			if ( double.class.equals(type) )			field.setDouble ( written, 13.25d );
			else if ( int.class.equals(type) )			field.setInt ( written, 7 );
			else if ( boolean.class.equals(type) )		field.setBoolean ( written, !field.getBoolean(written) );
			else if ( String.class.equals(type) )		field.set ( written, "value-of-" + field.getName() );
		}
		written.storeParam();

		Parameter read = new Parameter ( TEST_SCOPE + "-all" );
		for ( Field field : persistedFields() ) {
			Class<?> type = field.getType();
			String name = field.getName();
			if ( double.class.equals(type) )			assertEquals ( name, 13.25d, field.getDouble(read), 1e-9 );
			else if ( int.class.equals(type) )			assertEquals ( name, 7, field.getInt(read) );
			else if ( String.class.equals(type) )		assertEquals ( name, "value-of-" + name, field.get(read) );
			else if ( boolean.class.equals(type) )		assertEquals ( name, field.getBoolean(written), field.getBoolean(read) );
		}
	}

	@Test
	public void theKeyFormatIsUnchangedFromTheHandWrittenLists () throws Exception {
		/* The old code spelled out "OPM-" + obj + "-xyPixelSize" and so on. Write through the
		 * documented key and check the new loader picks it up, which is what makes settings
		 * saved by earlier versions still readable. */
		org.scijava.prefs.DefaultPrefService prefs = new org.scijava.prefs.DefaultPrefService();
		prefs.put ( Double.class, "OPM-" + TEST_SCOPE + "-legacy" + "-xyPixelSize", 123.0d );
		prefs.put ( Double.class, "OPM-" + TEST_SCOPE + "-legacy-xyPixelSize", 123.0d );

		Parameter read = new Parameter ( TEST_SCOPE + "-legacy" );
		assertEquals ( "the loader must still read the historical key format",
				123.0d, read.xyPixelSize, 1e-9 );
	}
}
