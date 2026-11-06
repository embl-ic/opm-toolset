package de.embl.iclm;

import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.VoxelDimensions;

public class OnlineDeskewAutoContrastTest {
	@Test public void stagesMatchFijiAndWrapAfterNine() {
		int stage = 0;
		for (int expected : new int[] { 5000, 2500, 1250, 625, 312, 156, 78, 39, 19, 9, 5000 }) {
			stage = OnlineDeskewAutoContrast.nextStage(stage);
			assertEquals(expected, stage);
		}
	}

	@Test public void aBackgroundPeakAboveTenPercentDoesNotSetTheLowerBound() {
		int[] pixels = new int[10000];
		for (int i = 9000; i < pixels.length; i++) pixels[i] = 100 + i % 200;
		OnlineDeskewAutoContrast.Range range = OnlineDeskewAutoContrast.autoRange(pixels, 0);
		assertTrue("the 90% background peak is ignored", range.min > 90);
		assertTrue(range.max < 300);
	}

	static int[] distribution() {
		int[] values = new int[20000];
		// Sparse tails survive the first click but disappear as the threshold rises.
		for (int i = 0; i < values.length; i++) values[i] = 1000 + i % 1000;
		values[0] = 0; values[1] = 10000;
		return values;
	}

	@Test public void clicksTightenThenUseFullRangeAndStartAgain() {
		int[] values = distribution();
		OnlineDeskewAutoContrast.Cycle cycle = new OnlineDeskewAutoContrast.Cycle();
		OnlineDeskewAutoContrast.Range range = cycle.adjust(values, 0, 65535);
		double initialWidth = range.max - range.min;
		boolean tightened = false, reset = false;
		for (int i = 0; i < 15; i++) {
			range = cycle.adjust(values, range.min, range.max);
			if (range.stage == 0) { reset = true; break; }
			tightened |= range.max - range.min < initialWidth;
		}
		assertTrue(tightened); assertTrue(reset);
		assertEquals(0, range.min, 0); assertEquals(10000, range.max, 0);
		range = cycle.adjust(values, range.min, range.max);
		assertEquals(5000, range.stage);
		assertTrue(range.max - range.min < 10000);
	}

	@Test public void emptyThresholdIntersectionUsesFullSampleRangeAndResets() {
		int[] values = new int[256];
		for (int i = 0; i < values.length; i++) values[i] = i;
		OnlineDeskewAutoContrast.Range range = OnlineDeskewAutoContrast.autoRange(values, 19);
		assertEquals(0, range.stage); assertEquals(0, range.min, 0); assertEquals(255, range.max, 0);
	}

	@Test public void constantPixelsHaveANonzeroRange() {
		int[] values = new int[50]; Arrays.fill(values, 123);
		OnlineDeskewAutoContrast.Range range = OnlineDeskewAutoContrast.autoRange(values, 0);
		assertEquals(123, range.min, 0); assertEquals(124, range.max, 0);
	}

	@Test public void aHandEditedRangeRestartsOnlyThatSourcesCycle() {
		int[] values = distribution();
		OnlineDeskewAutoContrast.Cycle one = new OnlineDeskewAutoContrast.Cycle(), two = new OnlineDeskewAutoContrast.Cycle();
		OnlineDeskewAutoContrast.Range first = one.adjust(values, 0, 65535);
		assertEquals(2500, one.adjust(values, first.min, first.max).stage);
		assertEquals(5000, two.adjust(values, 0, 65535).stage);
		assertEquals(5000, one.adjust(values, 10, 90).stage);
	}

	@Test public void samplingUsesTheTransformedScreenPlaneAndExcludesOutsideZeros() {
		short[] pixels = new short[100];
		for (int i = 0; i < pixels.length; i++) pixels[i] = (short) (100 + i);
		RandomAccessibleInterval<UnsignedShortType> image = ArrayImgs.unsignedShorts(pixels, 10, 10, 1);
		Source<UnsignedShortType> source = new Source<UnsignedShortType>() {
			public boolean isPresent(int t) { return t == 0; }
			public RandomAccessibleInterval<UnsignedShortType> getSource(int t, int level) { return image; }
			public RealRandomAccessible<UnsignedShortType> getInterpolatedSource(int t, int level, Interpolation interpolation) { return null; }
			public void getSourceTransform(int t, int level, AffineTransform3D transform) { transform.identity(); transform.translate(3, 2, 0); }
			public UnsignedShortType getType() { return new UnsignedShortType(); }
			public String getName() { return "test"; }
			public VoxelDimensions getVoxelDimensions() { return new FinalVoxelDimensions("pixel", 1, 1, 1); }
			public int getNumMipmapLevels() { return 1; }
		};
		int[] sampled = OnlineDeskewAutoContrast.sample(source, 0, new AffineTransform3D(), 20, 20, () -> false);
		assertEquals(100, sampled.length);
		Arrays.sort(sampled); assertEquals(100, sampled[0]); assertEquals(199, sampled[99]);
		AffineTransform3D offPlane = new AffineTransform3D(); offPlane.translate(0, 0, 5);
		assertEquals(0, OnlineDeskewAutoContrast.sample(source, 0, offPlane, 20, 20, () -> false).length);
		assertEquals(0, OnlineDeskewAutoContrast.sample(source, 1, new AffineTransform3D(), 20, 20, () -> false).length);
	}
}
