package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadFactory;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests for the settings and filters the reworked Live Deskew setup introduces.
 *
 * <p>Three of these encode decisions that are easy to get quietly wrong:
 *
 * <ul>
 * <li>the output format is one dropdown, not two checkboxes, because an OME-Zarr dataset
 *     already carries its own projections - a Zarr-only run that still wrote a tree of
 *     projection TIFF sub-folders beside it was writing the same pixels twice;</li>
 * <li>the whole-width sources must slot in without moving the camera-half entries, or a
 *     stored channel selection would silently start meaning a different half;</li>
 * <li>{@link Shutdown} must never report "stopping" with no ImageJ present, or every headless
 *     conversion and every test in this suite would cancel itself on its first checkpoint.</li>
 * </ul>
 */
public class LiveConfigurationTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();


	// ---- output format --------------------------------------------------------------

	@Test
	public void theFormatDropdownDecidesWhichWritersRun () {
		Parameter parameter = new Parameter ( "junit-format" );

		parameter.outputFormat = Parameter.FORMAT_TIFF;
		parameter.applyOutputFormat();
		assertTrue ( parameter.savesTiff() );
		assertFalse ( parameter.savesZarr() );
		assertFalse ( "the Zarr writer must stay off for a TIFF run", parameter.saveDeskewZarr );

		parameter.outputFormat = Parameter.FORMAT_ZARR;
		parameter.applyOutputFormat();
		assertFalse ( parameter.savesTiff() );
		assertTrue ( parameter.savesZarr() );
		assertTrue ( parameter.saveDeskewZarr );

		parameter.outputFormat = Parameter.FORMAT_BOTH;
		parameter.applyOutputFormat();
		assertTrue ( parameter.savesTiff() );
		assertTrue ( parameter.savesZarr() );
		assertTrue ( parameter.saveDeskewZarr );
	}

	/** The point of the dropdown: a Zarr-only run writes no projection TIFF sub-folders. */
	@Test
	public void anOmeZarrOnlyRunMakesNoProjectionTiffs () {
		Parameter parameter = new Parameter ( "junit-format" );
		parameter.projX = parameter.projY = parameter.projZ = true;
		parameter.maxProj = true;
		parameter.parseProjectionParameter();
		assertTrue ( "projections are requested", parameter.doProjection );

		parameter.outputFormat = Parameter.FORMAT_ZARR;
		parameter.applyOutputFormat();
		assertFalse ( "OME-Zarr carries its own projections; no TIFF tree beside it",
				parameter.doProjection );

		// the same selection with TIFF in the format still produces them
		parameter.parseProjectionParameter();
		parameter.outputFormat = Parameter.FORMAT_BOTH;
		parameter.applyOutputFormat();
		assertTrue ( parameter.doProjection );
	}

	@Test
	public void anUnknownStoredFormatFallsBackToTheDefault () {
		/* OME-Zarr, not TIFF: it is the format the previews read, because its time points
		 * commit one at a time and a reader can follow a run in progress safely. */
		Parameter parameter = new Parameter ( "junit-format" );
		parameter.outputFormat = "written by some future version";
		parameter.applyOutputFormat();
		assertEquals ( Parameter.FORMAT_ZARR, parameter.outputFormat );
		assertTrue ( parameter.savesZarr() );
		assertFalse ( Parameter.isOutputFormat ( "written by some future version" ) );
	}


	// ---- name filters ---------------------------------------------------------------

	@Test
	public void theExcludeListRejectsFilesTheIncludeListWouldAccept () throws IOException {
		File run = folder.newFolder ( "run_0" );
		touch ( new File ( run, "sample_Channel0001.tif" ) );
		touch ( new File ( run, "sample_Channel0002.tif" ) );
		touch ( new File ( run, "sample_Channel0002_preview.tif" ) );

		assertEquals ( "no filter takes everything", 3, collect ( "", "" ).size() );
		assertEquals ( "the preview is excluded by name", 2, collect ( "", "preview" ).size() );
		assertEquals ( "include and exclude are applied together",
				1, collect ( "Channel0002", "preview" ).size() );
		assertEquals ( "an exclusion that matches everything queues nothing",
				0, collect ( "", "sample" ).size() );
	}

	@Test
	public void theNameTestAppliesBothFilters () throws IOException {
		File keep = touch ( new File ( folder.getRoot(), "sample_Channel0001.tif" ) );
		File drop = touch ( new File ( folder.getRoot(), "sample_Channel0001_preview.tif" ) );

		assertTrue ( Live2.isTiffNamed ( keep, "", "preview" ) );
		assertFalse ( Live2.isTiffNamed ( drop, "", "preview" ) );
		assertFalse ( "matching is case insensitive, like the directory listing",
				Live2.isTiffNamed ( drop, "", "PREVIEW" ) );
		// the two argument form is the old behaviour, unchanged
		assertTrue ( Live2.isTiffNamed ( drop, "" ) );
	}


	// ---- whole width ----------------------------------------------------------------

	@Test
	public void wholeWidthSourcesAreAddedWithoutMovingTheHalves () {
		String[] options = ChannelOperationSettings.DESKEW_SOURCE_OPTIONS;
		int acquisitions = BatchChannelOperation.MAX_ACQUISITION_CHANNELS;
		assertEquals ( 3 * acquisitions + 1, options.length );

		/* Slot values are stored by name, so a half that moved index would still load, but a
		 * dialog built from a differently ordered list would show a different default. */
		for (int acquisition = 1; acquisition <= acquisitions; acquisition++) {
			assertEquals ( ChannelOperationSettings.sourceKey ( acquisition, true ),
					options[2 * acquisition - 2] );
			assertEquals ( ChannelOperationSettings.sourceKey ( acquisition, false ),
					options[2 * acquisition - 1] );
			assertEquals ( ChannelOperationSettings.wholeSourceKey ( acquisition ),
					options[2 * acquisitions + acquisition - 1] );
		}
		assertEquals ( BatchChannelOperation.SKIP_CHANNEL, options[options.length - 1] );

		// every half offered by Channel Operation is still offered here
		for (String half : BatchChannelOperation.CHANNEL_SOURCE_OPTIONS)
			assertTrue ( half, Arrays.asList ( options ).contains ( half ) );
	}

	@Test
	public void aSourceKeyNamesItsAcquisitionChannelAndItsKind () {
		assertEquals ( "_Channel0003-whole", ChannelOperationSettings.wholeSourceKey ( 3 ) );
		assertTrue ( ChannelOperationSettings.isWholeSource ( "_Channel0003-whole" ) );
		assertFalse ( ChannelOperationSettings.isWholeSource ( "_Channel0003-left" ) );

		assertEquals ( 3, ChannelOperationSettings.acquisitionChannelOf ( "_Channel0003-whole" ) );
		assertEquals ( 2, ChannelOperationSettings.acquisitionChannelOf ( "_Channel0002-right" ) );
		assertEquals ( -1, ChannelOperationSettings.acquisitionChannelOf (
				BatchChannelOperation.SKIP_CHANNEL ) );
		assertEquals ( -1, ChannelOperationSettings.acquisitionChannelOf ( null ) );
	}

	/** A whole-width selection has to survive a store/load round trip like any other. */
	@Test
	public void aWholeWidthSelectionIsAValidStoredSource () {
		assertTrue ( ChannelOperationSettings.isSourceOption (
				ChannelOperationSettings.wholeSourceKey ( 1 ) ) );
		assertTrue ( ChannelOperationSettings.isSourceOption (
				ChannelOperationSettings.sourceKey ( 1, true ) ) );
		assertFalse ( ChannelOperationSettings.isSourceOption ( "_Channel0001-middle" ) );
	}


	// ---- live preview ---------------------------------------------------------------

	/**
	 * maxZ is the projection that keeps both alignment axes, so it is the one worth watching
	 * during an acquisition; the others are only offered when a dataset lacks it.
	 */
	@Test
	public void thePreviewPrefersTheProjectionThatCarriesTheAlignmentExactly () {
		assertEquals ( "maxZ", LivePreview.choose ( Arrays.asList ( "maxX", "meanZ", "maxZ" ) ) );
		assertEquals ( "maxY", LivePreview.choose ( Arrays.asList ( "meanX", "maxY", "maxX" ) ) );
		assertEquals ( "anything it has, rather than nothing",
				"meanY", LivePreview.choose ( Arrays.asList ( "meanY" ) ) );
		assertNull ( LivePreview.choose ( new ArrayList<String>() ) );
		assertNull ( LivePreview.choose ( null ) );
	}

	/**
	 * The preview shows every projection the run writes. It showed maxZ alone, whatever axes
	 * were ticked, so a run projecting along X, Y and Z looked like one that wrote only Z.
	 */
	@Test
	public void thePreviewAsksForEveryProjectionTheRunWrites () {
		assertEquals ( Arrays.asList ( "maxZ", "maxY", "maxX" ),
				LivePreview.wantedProjections ( true, true, true, true, false ) );
		assertEquals ( Arrays.asList ( "maxZ", "meanZ", "maxX", "meanX" ),
				LivePreview.wantedProjections ( true, false, true, true, true ) );
		assertEquals ( "nothing ticked still previews something",
				Arrays.asList ( "maxZ" ), LivePreview.wantedProjections ( false, false, false, false, false ) );
	}

	@Test
	public void wantedProjectionsAreMatchedToWhatTheDatasetCallsThem () {
		// an OME-Zarr carries all six; the order is the run's, not the store's
		assertEquals ( Arrays.asList ( "maxZ", "maxY", "maxX" ), LivePreview.choose (
				Arrays.asList ( "maxX", "maxY", "maxZ", "meanX", "meanY", "meanZ" ),
				Arrays.asList ( "maxZ", "maxY", "maxX" ) ) );
		// a TIFF result names its means avg, from its file names
		assertEquals ( Arrays.asList ( "avgZ" ), LivePreview.choose (
				Arrays.asList ( "maxX", "avgZ" ), Arrays.asList ( "meanZ" ) ) );
		// what the run did not write is not invented
		assertEquals ( Arrays.asList ( "maxZ" ), LivePreview.choose (
				Arrays.asList ( "maxZ" ), Arrays.asList ( "maxZ", "maxX" ) ) );
		assertEquals ( "none of the wanted ones there: the one it always showed",
				Arrays.asList ( "maxY" ), LivePreview.choose (
						Arrays.asList ( "maxX", "maxY" ), Arrays.asList ( "meanZ" ) ) );
		assertTrue ( LivePreview.choose ( new ArrayList<String>(), Arrays.asList ( "maxZ" ) ).isEmpty() );
	}

	@Test
	public void aPreviewThatShowsNeitherViewIsNotWanted () {
		assertFalse ( new LivePreview ( false, false, true, false ).wanted() );
		assertTrue ( new LivePreview ( true, false, true, false ).wanted() );
		assertTrue ( new LivePreview ( false, true, true, false ).wanted() );
		// asking for a materialised projection is still asking for a preview
		assertTrue ( new LivePreview ( true, false, false, false ).wanted() );
		// and a TIFF-only run asks for one the same way
		assertTrue ( new LivePreview ( true, true, true, true ).wanted() );
	}


	// ---- cancellation ---------------------------------------------------------------

	@Test
	public void nothingIsStoppingWhenThereIsNoImageJToQuit () {
		Shutdown.begin();
		assertFalse ( "a headless run must never cancel itself", Shutdown.stopping() );
	}

	@Test
	public void anExplicitRequestStopsUntilTheNextOperationBegins () {
		Shutdown.begin();
		Shutdown.request();
		assertTrue ( Shutdown.stopping() );
		assertNotNull ( Shutdown.reason() );
		Shutdown.begin();
		assertFalse ( "begin clears the previous operation's stop", Shutdown.stopping() );
	}

	/**
	 * The reason a worker pool must not be built with the default factory: its threads are not
	 * daemons, and Fiji quits by disposing its window and then waiting for the non-daemon
	 * threads to end.
	 */
	@Test
	public void workerPoolThreadsNeverHoldTheJvmOpen () {
		ThreadFactory factory = Shutdown.daemonThreads ( "junit-pool" );
		Thread first = factory.newThread ( new Runnable() {
			@Override public void run () { }
		} );
		Thread second = factory.newThread ( new Runnable() {
			@Override public void run () { }
		} );
		assertTrue ( first.isDaemon() );
		assertTrue ( second.isDaemon() );
		assertTrue ( first.getName().startsWith ( "junit-pool-" ) );
		assertFalse ( "names must differ, or a stack dump cannot tell them apart",
				first.getName().equals ( second.getName() ) );

		Thread named = Shutdown.daemon ( new Runnable() {
			@Override public void run () { }
		}, "junit-worker" );
		assertTrue ( named.isDaemon() );
		assertEquals ( "junit-worker", named.getName() );
	}

	@Test
	public void aCancellableOperationRunsToCompletionWhenNothingStopsIt () {
		Shutdown.begin();
		final boolean[] ran = { false };
		boolean completed = Shutdown.runCancellable ( "junit-op", new Runnable() {
			@Override public void run () { ran[0] = true; }
		} );
		assertTrue ( completed );
		assertTrue ( ran[0] );
	}


	// ---- helpers --------------------------------------------------------------------

	private List<File> collect (String include, String exclude) {
		List<File> out = new ArrayList<File>();
		Live2.collectTiffs ( folder.getRoot(), true, include, exclude, out, 0 );
		return out;
	}

	private static File touch (File file) throws IOException {
		File parent = file.getParentFile();
		if (parent != null) parent.mkdirs();
		OutputStream out = new FileOutputStream ( file );
		try {
			out.write ( new byte[16] );
		} finally {
			out.close();
		}
		return file;
	}
}
