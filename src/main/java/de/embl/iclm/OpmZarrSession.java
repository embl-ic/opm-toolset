package de.embl.iclm;

import ij.ImagePlus;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Append/resume session shared by offline conversion, Batch and Live processing. */
public final class OpmZarrSession implements AutoCloseable {

	public static final List<String> PROJECTIONS = Arrays.asList(
			"maxX", "maxY", "maxZ", "meanX", "meanY", "meanZ");

	private final File sourceFolder;
	private final double[][] deskewMatrix;
	private final boolean tryGpu;
	private final boolean writeProjections;
	private final OpmZarrWriter writer;
	private OpmProvenance provenance;

	/** Precomputed Zarr projections; the canonical channel volumes remain caller-owned. */
	static final class PreparedTimePoint implements AutoCloseable {
		final OpmTimepointProcessor.Result channels;
		final List<List<ImagePlus>> projections = new ArrayList<List<ImagePlus>>();

		PreparedTimePoint(OpmTimepointProcessor.Result channels) {
			this.channels = channels;
		}

		@Override
		public void close() {
			for (List<ImagePlus> channel : projections)
				for (ImagePlus projection : channel) BatchProcessingUtils.close(projection);
			projections.clear();
		}
	}

	public OpmZarrSession(
			File zarrRoot, File sourceFolder, OpmProvenance requestedProvenance,
			double[][] deskewMatrix, boolean tryGpu, boolean writeProjections) throws IOException {
		this.sourceFolder = sourceFolder;
		this.deskewMatrix = Transform.copy(deskewMatrix);
		this.tryGpu = tryGpu;
		this.writeProjections = writeProjections;
		writer = new OpmZarrWriter(zarrRoot);
		OpmProvenance existing = writer.getProvenance();
		if (existing != null) {
			validateStableMetadata(existing, requestedProvenance);
			provenance = existing;
		} else {
			provenance = requestedProvenance;
		}
		if (provenance == null) throw new IllegalArgumentException("OPM provenance is required.");
	}

	public File getRoot() { return writer.getRoot(); }

	public int getCommittedTimepoints() { return writer.getCommittedTimepoints(); }

	public boolean isCommitted(String label) { return writer.isTimePointCommitted(label); }

	public OpmProvenance getProvenance() { return provenance; }

	/**
	 * Process and append a complete raw time point.
	 *
	 * @return true when a new T position was committed, false when its label was already present
	 */
	public synchronized boolean append(OpmTimepointProcessor.TimePoint timePoint) throws IOException {
		if (writer.isTimePointCommitted(timePoint.label)) return false;
		final List<String> labels = OpmTimepointProcessor.channelLabels(timePoint);
		final int time = writer.getCommittedTimepoints();
		final boolean[] prepared = { false };
		try {
			final List<ProjectionBatch.Request> projectionRequests = projectionRequests();
			OpmTimepointProcessor.processChannels(timePoint, deskewMatrix, tryGpu,
					new OpmTimepointProcessor.ChannelSink() {
				@Override
				public void accept(ImagePlus volume, String label, int channel, boolean usedGpu)
						throws Exception {
					if (!prepared[0]) {
						prepareArraysAndMetadata(volume, labels, usedGpu);
						prepared[0] = true;
					}
					writer.writeVolumeChannel(volume, channel, time);
					if (writeProjections) {
						List<ImagePlus> projections = ProjectionBatch.compute(
								volume, projectionRequests, tryGpu);
						try {
							for (int projection = 0; projection < PROJECTIONS.size(); projection++)
								writer.writeProjection(projections.get(projection), PROJECTIONS.get(projection),
										channel, time);
						} finally {
							for (ImagePlus projection : projections)
								BatchProcessingUtils.close(projection);
						}
					}
				}
			});
			if (!prepared[0]) throw new IOException("The time point produced no channels.");
			writer.commitTimePoint(time, timePoint.label, relativeSourceNames(timePoint.files),
					timePoint.elapsedSeconds);
			return true;
		} catch (IOException failure) {
			throw failure;
		} catch (Exception failure) {
			throw new IOException("Could not append OME-Zarr time point " + timePoint.label, failure);
		}
	}

	/**
	 * Compute all requested MIPs before either disk writer starts. The returned object owns
	 * only the projections; its canonical channel volumes remain owned by {@code result}.
	 */
	static PreparedTimePoint prepare(
			OpmTimepointProcessor.Result result, boolean tryGpu, boolean writeProjections)
			throws IOException {
		if (result == null || result.channels.isEmpty())
			throw new IOException("The prepared OME-Zarr time point has no channels.");
		PreparedTimePoint prepared = new PreparedTimePoint(result);
		try {
			List<ProjectionBatch.Request> requests = projectionRequests();
			for (ImagePlus volume : result.channels) {
				List<ImagePlus> channel = writeProjections
						? ProjectionBatch.compute(volume, requests, tryGpu)
						: new ArrayList<ImagePlus>();
				prepared.projections.add(channel);
			}
			return prepared;
		} catch (Throwable failure) {
			prepared.close();
			if (failure instanceof IOException) throw (IOException) failure;
			throw new IOException("Could not prepare OME-Zarr projections.", failure);
		}
	}

	/** Append channels and already computed MIPs without re-opening or re-deskewing raw TIFFs. */
	public synchronized boolean appendPrepared(
			OpmTimepointProcessor.TimePoint timePoint, PreparedTimePoint prepared) throws IOException {
		if (writer.isTimePointCommitted(timePoint.label)) return false;
		if (prepared == null || prepared.channels == null)
			throw new IOException("A prepared OME-Zarr time point is required.");
		List<String> expectedLabels = OpmTimepointProcessor.channelLabels(timePoint);
		if (!expectedLabels.equals(prepared.channels.channelLabels))
			throw new IOException("Prepared channel labels " + prepared.channels.channelLabels
					+ " do not match " + expectedLabels);
		if (writeProjections && prepared.projections.size() != prepared.channels.channels.size())
			throw new IOException("Prepared OME-Zarr projection/channel count differs.");

		final int time = writer.getCommittedTimepoints();
		try {
			for (int channel = 0; channel < prepared.channels.channels.size(); channel++) {
				ImagePlus volume = prepared.channels.channels.get(channel);
				if (channel == 0)
					prepareArraysAndMetadata(volume, expectedLabels, prepared.channels.usedGpu);
				writer.writeVolumeChannel(volume, channel, time);
				if (writeProjections) {
					List<ImagePlus> mips = prepared.projections.get(channel);
					if (mips.size() != PROJECTIONS.size())
						throw new IOException("Prepared OME-Zarr projection count differs for channel " + channel);
					for (int projection = 0; projection < PROJECTIONS.size(); projection++)
						writer.writeProjection(mips.get(projection), PROJECTIONS.get(projection), channel, time);
				}
			}
			writer.commitTimePoint(time, timePoint.label, relativeSourceNames(timePoint.files),
					timePoint.elapsedSeconds);
			return true;
		} catch (IOException failure) {
			throw failure;
		} catch (Exception failure) {
			throw new IOException("Could not append prepared OME-Zarr time point "
					+ timePoint.label, failure);
		}
	}

	private void prepareArraysAndMetadata(
			ImagePlus first, List<String> channelLabels, boolean usedGpu) throws IOException {
		int channels = channelLabels.size();
		if (provenance.channelLabels == null || provenance.channelLabels.isEmpty())
			provenance.channelLabels = new ArrayList<String>(channelLabels);
		else if (!provenance.channelLabels.equals(channelLabels))
			throw new IOException("Stored channel labels " + provenance.channelLabels
					+ " do not match incoming labels " + channelLabels);

		writer.createAppendableVolume(first.getWidth(), first.getHeight(), first.getStackSize(), channels);
		if (writeProjections) {
			if (writer.getCommittedTimepoints() > 0)
				for (String name : PROJECTIONS) if (!writer.hasDataset("projections/" + name))
					throw new IOException("Cannot add missing projection " + name
							+ " after time points have already committed.");
			for (String name : PROJECTIONS) {
				char axis = Character.toUpperCase(name.charAt(name.length() - 1));
				int width = axis == 'X' ? first.getStackSize() : first.getWidth();
				int height = axis == 'Y' ? first.getStackSize() : first.getHeight();
				writer.createAppendableProjection(name, width, height, channels);
			}
		}
		if (provenance.createdUtc == null) provenance.stampEnvironment(usedGpu);
		writer.markWriting();
		writer.writeMetadata(provenance, writeProjections ? PROJECTIONS : new ArrayList<String>());
	}

	public synchronized void markComplete() throws IOException {
		// A Live session can be allocated before its first raw group successfully opens.
		// Do not bless an empty/failed shell with a misleading success marker.
		if (writer.getCommittedTimepoints() > 0 && writer.hasDataset("s0")
				&& writer.getProvenance() != null) writer.markComplete();
	}

	@Override
	public void close() { writer.close(); }

	private ImagePlus project(ImagePlus image, String name) {
		return project(image, name, tryGpu);
	}

	private static ImagePlus project(ImagePlus image, String name, boolean tryGpu) {
		String axis = name.substring(name.length() - 1);
		String type = name.startsWith("mean") ? "avg" : "max";
		// Projection itself tries the GPU and falls back to CPU. The volume is already in RAM.
		return Projection.projection(image, axis, type, tryGpu);
	}

	private static List<ProjectionBatch.Request> projectionRequests() {
		List<ProjectionBatch.Request> requests = new ArrayList<ProjectionBatch.Request>();
		for (String name : PROJECTIONS) {
			String axis = name.substring(name.length() - 1);
			String type = name.startsWith("mean") ? "avg" : "max";
			requests.add(new ProjectionBatch.Request(axis, type));
		}
		return requests;
	}

	private List<String> relativeSourceNames(List<File> files) {
		List<String> names = new ArrayList<String>();
		Path rootPath = sourceFolder == null ? null : sourceFolder.toPath().toAbsolutePath().normalize();
		for (File file : files) {
			String name = file.getName();
			if (rootPath != null) try {
				Path filePath = file.toPath().toAbsolutePath().normalize();
				if (filePath.startsWith(rootPath)) name = rootPath.relativize(filePath).toString();
			} catch (Throwable ignored) { }
			names.add(name.replace(File.separatorChar, '/'));
		}
		return names;
	}

	private static void validateStableMetadata(OpmProvenance existing, OpmProvenance requested) throws IOException {
		if (requested == null) return;
		if (!same(existing.xyPixelSizeUm, requested.xyPixelSizeUm)
				|| !same(existing.zStepSizeUm, requested.zStepSizeUm)
				|| !same(existing.opmAngleDegrees, requested.opmAngleDegrees)
				|| !matricesEqual(existing.deskewMatrix, requested.deskewMatrix)
				|| !matricesEqual(existing.alignMatrix, requested.alignMatrix)
				|| existing.alignApplied != requested.alignApplied
				|| !equal(existing.alignMatrixConvention, requested.alignMatrixConvention)
				|| !equal(existing.alignFlipHalf, requested.alignFlipHalf)
				|| existing.alignInterpolate != requested.alignInterpolate)
			throw new IOException("The existing partial OME-Zarr dataset was created with different geometry or alignment metadata.");
	}

	private static boolean matricesEqual(double[][] a, double[][] b) {
		if (a == b) return true;
		if (a == null || b == null || a.length != b.length) return false;
		for (int row = 0; row < a.length; row++) {
			if (a[row] == null || b[row] == null || a[row].length != b[row].length) return false;
			for (int column = 0; column < a[row].length; column++)
				if (!same(a[row][column], b[row][column])) return false;
		}
		return true;
	}

	private static boolean same(double a, double b) {
		return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
	}

	private static boolean equal(Object a, Object b) { return a == null ? b == null : a.equals(b); }
}
