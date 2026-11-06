package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** What Open Raw Acquisition remembers comes back as it was left; the user's own settings are put back afterwards. */
public class OnlineDeskewViewSettingsTest {

	private OnlineDeskewView.Settings before;

	@Before
	public void keepTheUsersSettings() {
		before = OnlineDeskewView.Settings.load();
	}

	@After
	public void putThemBack() {
		before.store();
	}

	@Test
	public void everyRememberedSettingComesBackAsStored() {
		OnlineDeskewView.Settings s = new OnlineDeskewView.Settings();
		s.folder = "D:\\raw\\acquisition";
		s.position = 3;
		s.channel = 2;
		s.parameterSource = OnlineDeskewView.PARAMETER_SOURCES[1];
		s.dz = 0.3;
		s.dxy = 0.1;
		s.angle = -31.5;
		s.channels.combineAcquisitionChannels = true;
		s.channels.flipHalf = BatchChannelOperation.FLIP_LEFT;
		s.channels.channelOrder[0] = ChannelOperationSettings.sourceKey(2, false);
		s.channels.channelOrder[1] = ChannelOperationSettings.wholeSourceKey(1);
		s.channels.channelOrder[2] = BatchChannelOperation.SKIP_CHANNEL;
		s.alignmentCsv = "D:\\raw\\alignment.csv";
		s.alignChannels = false;
		s.parameterSource = OnlineDeskewView.PARAMETER_SOURCES[2];
		s.parameterFile = "D:\\elsewhere\\parameters.txt";
		s.interpolation = OnlineDeskewView.INTERPOLATIONS[1];
		s.budgetMb = 512;
		s.store();

		OnlineDeskewView.Settings r = OnlineDeskewView.Settings.load();
		assertEquals(s.folder, r.folder);
		assertEquals(3, r.position);
		s.rememberPosition(4);
		assertEquals("the view's Position box remembers the position it switched to", 4, OnlineDeskewView.Settings.load().position);
		assertEquals(2, r.channel);
		assertEquals(s.parameterSource, r.parameterSource);
		assertEquals(s.parameterFile, r.parameterFile);
		assertEquals(new java.io.File(s.parameterFile), r.parameterFile());
		assertEquals(s.alignChannels, r.alignChannels);
		assertEquals(0.3, r.dz, 0);
		assertEquals(0.1, r.dxy, 0);
		assertEquals(-31.5, r.angle, 0);
		assertTrue(r.channels.combineAcquisitionChannels);
		assertEquals(BatchChannelOperation.FLIP_LEFT, r.channels.flipHalf);
		assertTrue("the flip chosen for combined files is the one used", r.flipLeft());
		assertArrayEquals(s.channels.channelOrder, r.channels.channelOrder);
		assertEquals(s.sourceKeys(), r.sourceKeys());
		assertEquals(s.alignmentCsv, r.alignmentCsv);
		assertEquals(s.interpolation, r.interpolation);
		assertEquals(512, r.budgetMb, 0);
	}

	@Test public void absentAlignmentTickMigratesThePreviouslyAppliedPath() {
		ij.Prefs.set("opm.onlineDeskew.alignChannels", (String) null);
		ij.Prefs.set("opm.onlineDeskew.alignmentCsv", "remembered.csv");
		assertTrue(OnlineDeskewView.Settings.load().alignChannels);
		ij.Prefs.set("opm.onlineDeskew.alignmentCsv", "");
		assertEquals(false, OnlineDeskewView.Settings.load().alignChannels);
	}

	@Test public void anUnknownParameterSourceFallsBackWithoutRenamingManual() {
		assertEquals("manual", OnlineDeskewView.PARAMETER_SOURCES[1]);
		ij.Prefs.set("opm.onlineDeskew.parameterSource", "unknown");
		assertEquals(OnlineDeskewView.PARAMETER_SOURCES[0], OnlineDeskewView.Settings.load().parameterSource);
		OnlineDeskewView.Settings s = new OnlineDeskewView.Settings();
		s.folder = "some-folder";
		assertEquals(new java.io.File(s.folder, "ExperimentalParameters.txt"), s.parameterFile());
		s.parameterSource = "manual";
		assertEquals(null, s.parameterFile());
	}
}
