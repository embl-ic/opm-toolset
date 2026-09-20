package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Regression tests for the folder watcher's file list filter.
 *
 * <p>Three defects lived in this one small method:
 *
 * <ul>
 * <li>after removing an entry that failed the first keyword, the loop {@code continue}d the
 *     inner keyword loop rather than the outer one, so a name failing two keywords was removed
 *     twice and threw {@link IllegalStateException} out of the monitor thread;</li>
 * <li>the extension test was case sensitive here while the directory listing that feeds it was
 *     not, so a file named .TIF was listed and then silently dropped;</li>
 * <li>the method returned a new list when handed an empty one, and the caller assigned the
 *     result back over a field shared with the watch thread - replacing a synchronized list
 *     with an unsynchronized one.</li>
 * </ul>
 *
 * <p>The filter now works in place, returns the same list instance it was given, and takes its
 * criteria as arguments so it can be exercised without opening the watcher window.
 */
public class FolderWatcherFilterTest {

	private static final String[] TIFF = { "tif", "tiff" };
	private static final String[] NO_KEYWORD = new String[0];

	@Test
	public void keepsOnlyTiffFilesWhateverTheCase () {
		List<String> files = new ArrayList<String>( Arrays.asList(
				path("volume_Channel0001.tif"),
				path("volume_Channel0002.TIF"),
				path("volume_Channel0003.tiff"),
				path("ExperimentalParameters.txt"),
				path("notes.md") ) );

		List<String> result = FolderWatcher.filterFileList ( files, TIFF, NO_KEYWORD );

		assertSame ( "the filter must work in place, not hand back a different list", files, result );
		assertEquals ( 3, result.size() );
		for ( String kept : result )
			assertTrue ( kept, kept.toLowerCase().endsWith(".tif") || kept.toLowerCase().endsWith(".tiff") );
	}

	@Test
	public void aNameFailingSeveralKeywordsIsRemovedOnce () {
		/* Two keywords, both absent: the old inner-loop continue removed the same entry twice
		 * and threw IllegalStateException, killing the monitor thread mid acquisition. */
		List<String> files = new ArrayList<String>( Arrays.asList(
				path("unrelated_volume.tif"),
				path("acquisition_Channel0001_Time0003.tif") ) );

		List<String> result = FolderWatcher.filterFileList ( files, TIFF, new String[]{ "Channel", "Time" } );

		assertEquals ( 1, result.size() );
		assertTrue ( result.get(0).contains("Channel0001") );
	}

	@Test
	public void everyKeywordMustAppear () {
		List<String> files = new ArrayList<String>( Arrays.asList(
				path("run_Channel0001.tif"),					// has Channel, no Time
				path("run_Channel0002_Time0001.tif") ) );

		FolderWatcher.filterFileList ( files, TIFF, new String[]{ "Channel", "Time" } );

		assertEquals ( 1, files.size() );
		assertTrue ( files.get(0).contains("Time0001") );
	}

	@Test
	public void anEmptyListComesBackAsTheSameInstance () {
		List<String> empty = Collections.synchronizedList ( new ArrayList<String>() );
		assertSame ( "returning a fresh list here is what dropped the synchronized wrapper",
				empty, FolderWatcher.filterFileList ( empty, TIFF, NO_KEYWORD ) );
	}

	@Test
	public void filteringASynchronizedListKeepsTheSameInstance () {
		List<String> shared = Collections.synchronizedList ( new ArrayList<String>(
				Arrays.asList ( path("a.tif"), path("b.txt") ) ) );

		List<String> result = FolderWatcher.filterFileList ( shared, TIFF, NO_KEYWORD );

		assertSame ( shared, result );
		assertEquals ( 1, result.size() );
	}

	@Test
	public void keepFileMatchesTheDirectoryListingRules () {
		assertTrue ( FolderWatcher.keepFile ( path("v.TIFF"), TIFF, NO_KEYWORD ) );
		assertTrue ( FolderWatcher.keepFile ( path("v.tif"), TIFF, NO_KEYWORD ) );
		assertFalse ( FolderWatcher.keepFile ( path("v.txt"), TIFF, NO_KEYWORD ) );
		assertFalse ( "a null path must not be kept", FolderWatcher.keepFile ( null, TIFF, NO_KEYWORD ) );
		// the keyword is matched against the file name, not the folder it sits in
		assertFalse ( FolderWatcher.keepFile ( path("v.tif"), TIFF, new String[]{ "acquisition" } ) );
	}

	private static String path ( String name ) {
		return "C:" + File.separator + "acquisition" + File.separator + name;
	}
}
