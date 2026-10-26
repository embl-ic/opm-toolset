package de.embl.iclm;

import java.io.File;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import bdv.viewer.Interpolation;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * How long the store takes to prepare a frame's cells from a real acquisition, by plane of view:
 * one positional read per cell, as it read before C3.2, against runs of consecutive cells of a
 * plane read in one go, as it reads now. A main program; each time point is prepared once, by one
 * of the two in turn, so that neither finds the other's reads in the operating system's cache:
 * <pre>
 * java -cp ... de.embl.iclm.RawPrepareBenchmark E:\OPM\3_timelapse_0 YZ 800 580 30,31,32,33 [threads] [budget MB]
 * </pre>
 */
public final class RawPrepareBenchmark {

	private RawPrepareBenchmark() {}

	public static void main(String[] args) throws Exception {
		File folder = new File(args[0]);
		RawWorldView plane = RawWorldView.valueOf(args[1]);
		int w = Integer.parseInt(args[2]), h = Integer.parseInt(args[3]);
		String[] list = args[4].split(",");
		int threads = args.length > 5 ? Integer.parseInt(args[5]) : 8;
		long budget = (args.length > 6 ? Long.parseLong(args[6]) : 4096) << 20;
		RawGeometry geometry = RawGeometry.fromExperimentalParameters(new File(folder, "ExperimentalParameters.txt"));
		RawAcquisition acquisition = RawAcquisition.scan(folder);
		List<RawAcquisition.TimeKey> times = acquisition.times(1, 1);
		System.out.printf(Locale.US, "java %s; %s, %s plane, %d x %d, trilinear, %d threads, %d MB budget%n", System.getProperty("java.version"),
				folder.getName(), plane, w, h, threads, budget >> 20);
		System.out.println("time	mode	cells	MB	reads	ms	MB_per_s");
		for (int i = 0; i < list.length; i++) {
			int t = Integer.parseInt(list[i].trim());
			RawAcquisition.Entry e = acquisition.entry(times.get(t), 1);
			e.validate();
			RawTiffLayout layout = e.layout();
			long[] dims = layout.dimensions();
			RawSourceSpec[] specs = { RawSourceSpec.half(null, 1, true, false, layout.width, 0x00ff00),
					RawSourceSpec.half(null, 1, false, false, layout.width, 0xff00ff) };
			AffineTransform3D view = plane.orient(RawFrameRenderBenchmark.xyView(geometry, dims, specs, w, h), w, h);
			List<RawFrameRequest.Layer> layers = new ArrayList<RawFrameRequest.Layer>();
			for (RawSourceSpec spec : specs)
				layers.add(new RawFrameRequest.Layer(spec, geometry.sourceToWorld(dims, spec, null), 80, 150, 0xff000000 | spec.rgb));
			RawFrameRequest r = new RawFrameRequest(1, t, e, view, w, h, Interpolation.NLINEAR, layers);
			BitSet cells = RawCoverage.cells(dims, layout.rowsPerStrip, r.coverageParts(), w, h, Interpolation.NLINEAR);
			prepare(e, layout, cells, threads, budget, t, i % 2 == 1);
		}
	}

	/** Prepare the cells with a store of its own: one read per cell, or in runs. */
	private static void prepare(RawAcquisition.Entry e, RawTiffLayout layout, BitSet cells, int threads, long budget, int t, boolean runs)
			throws Exception {
		long cell = TiffRawVolumeStore.cellBytes(layout);
		TiffRawVolumeStore store = new TiffRawVolumeStore(budget, cell, threads, null);
		if (!runs) store.setMaxRunBytesForTest(cell);
		try {
			long start = System.nanoTime();
			TiffRawVolumeStore.Ready ready = store.prepare(1, Collections.singletonList(new TiffRawVolumeStore.Want(e.key(), layout, cells)),
					() -> false).get();
			double ms = (System.nanoTime() - start) / 1e6;
			double mb = ready.bytes() / 1e6;
			System.out.printf(Locale.US, "%d	%s	%d	%.1f	%d	%.1f	%.0f%n", t, runs ? "runs" : "cells", cells.cardinality(), mb,
					store.runReads(), ms, mb / (ms / 1000));
			ready.close();
		} finally {
			store.closeAsync().get(30, TimeUnit.SECONDS);
		}
	}
}
