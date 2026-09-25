package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import org.junit.Test;

/**
 * What the output-format controls offer, and what a stored value means to them.
 *
 * <p>An acquisition is worth keeping in the format that can be read while it is written and
 * that does not have to be rewritten to change how its channels are composed. TIFF alone is
 * still reachable, but as a tick made deliberately each time rather than as a preference that
 * outlives the reason for it - so no dropdown offers it, and no preference can select it.
 */
public class OutputFormatChoiceTest {

	@Test
	public void theDropdownOffersOmeZarrAndBothOnly() {
		assertEquals(Arrays.asList(Parameter.FORMAT_ZARR, Parameter.FORMAT_BOTH),
				Arrays.asList(Parameter.OUTPUT_FORMATS));
		assertEquals(Arrays.asList("OME-Zarr", "OME-Zarr + TIFF"),
				Arrays.asList(Parameter.OUTPUT_FORMAT_LABELS));
		assertFalse("TIFF alone is not offered",
				Arrays.asList(Parameter.OUTPUT_FORMATS).contains(Parameter.FORMAT_TIFF));
		assertTrue("but it is still a format a run can have",
				Parameter.isOutputFormat(Parameter.FORMAT_TIFF));
	}

	/** The stored values are unchanged, so a preference written by 2.1.5 still means what it did. */
	@Test
	public void theLabelsAreShownOverTheStoredValues() {
		assertEquals("save as OME-Zarr", Parameter.FORMAT_ZARR);
		assertEquals("save both", Parameter.FORMAT_BOTH);
		assertEquals("save as TIFF", Parameter.FORMAT_TIFF);
		assertEquals("OME-Zarr", Parameter.formatLabel(Parameter.FORMAT_ZARR));
		assertEquals("OME-Zarr + TIFF", Parameter.formatLabel(Parameter.FORMAT_BOTH));
		assertEquals(Parameter.FORMAT_ZARR, Parameter.formatValue("OME-Zarr"));
		assertEquals(Parameter.FORMAT_BOTH, Parameter.formatValue("OME-Zarr + TIFF"));
	}

	@Test
	public void aStoredTiffOnlyFormatReadsBackAsBoth() {
		assertEquals(Parameter.FORMAT_ZARR, Parameter.normalisedOutputFormat(Parameter.FORMAT_ZARR));
		assertEquals(Parameter.FORMAT_BOTH, Parameter.normalisedOutputFormat(Parameter.FORMAT_BOTH));
		assertEquals("what was asked for, plus the store that should have been beside it",
				Parameter.FORMAT_BOTH, Parameter.normalisedOutputFormat(Parameter.FORMAT_TIFF));
		assertEquals("OME-Zarr + TIFF", Parameter.formatLabel(Parameter.FORMAT_TIFF));
		assertEquals(Parameter.FORMAT_ZARR, Parameter.normalisedOutputFormat(null));
		assertEquals(Parameter.FORMAT_ZARR, Parameter.normalisedOutputFormat("save as ZARR"));
	}

	@Test
	public void theTickIsWhatMakesARunTiffOnly() {
		assertEquals(Parameter.FORMAT_ZARR, Parameter.outputFormatFrom("OME-Zarr", false));
		assertEquals(Parameter.FORMAT_BOTH, Parameter.outputFormatFrom("OME-Zarr + TIFF", false));
		assertEquals("the tick is absolute, whatever is chosen above it",
				Parameter.FORMAT_TIFF, Parameter.outputFormatFrom("OME-Zarr", true));
		assertEquals(Parameter.FORMAT_TIFF, Parameter.outputFormatFrom("OME-Zarr + TIFF", true));

		Parameter parameter = new Parameter("junit-format");
		parameter.outputFormat = Parameter.outputFormatFrom("OME-Zarr", true);
		parameter.applyOutputFormat();
		assertTrue(parameter.savesTiff());
		assertFalse("the tick has to survive applyOutputFormat, or the run writes a store anyway",
				parameter.savesZarr());
	}

	/**
	 * Every dialog that offers the format offers the tick with it.
	 *
	 * <p>Read from the sources, like {@link DialogConsistencyTest}: a dialog added later is
	 * held to the same rule the day it is added, and a dropdown without the tick would be one
	 * the user cannot get TIFF alone out of at all.
	 */
	@Test
	public void everyFormatDropdownCarriesTheTick() throws Exception {
		File sources = new File("src/main/java/de/embl/iclm");
		int checked = 0;
		for (File file : sources.listFiles()) {
			if (!file.getName().endsWith(".java")) continue;
			String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
			if (!source.contains("OUTPUT_FORMAT_LABELS")) continue;
			assertTrue(file.getName() + " offers the format without the opt-out tick",
					source.contains("NO_ZARR"));
			checked++;
		}
		assertTrue("no format dropdown found at all", checked >= 3);
	}

	/** The one place a TIFF-only target is still a first-class choice, and why. */
	@Test
	public void formatConversionKeepsItsOwnTargets() {
		assertEquals("deflated TIFF", FormatConversion.TARGET_TIFF);
		assertTrue("converting to deflated TIFF is what the command is for",
				Arrays.asList(FormatConversion.TARGETS).contains(FormatConversion.TARGET_TIFF));
	}
}
