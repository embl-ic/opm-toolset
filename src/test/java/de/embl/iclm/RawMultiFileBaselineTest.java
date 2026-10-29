package de.embl.iclm;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** M4-A's entry points: compiled unchanged before and after the implementation. */
public class RawMultiFileBaselineTest {

	@Test
	public void aViewerCanSelectTheSharedChannelSlots() throws Exception {
		assertEquals(ChannelOperationSettings.class, OnlineDeskewView.Settings.class.getDeclaredField("channels").getType());
	}

	@Test
	public void aCatalogueCanFreezeSeveralSelectedFiles() throws Exception {
		assertEquals(RawAcquisition.Snapshot.class,
				RawAcquisition.class.getDeclaredMethod("snapshot", int.class, int[].class).getReturnType());
	}
}
