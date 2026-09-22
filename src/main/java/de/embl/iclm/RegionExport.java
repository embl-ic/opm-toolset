package de.embl.iclm;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ij.IJ;
import ij.process.ImageProcessor;

/**		Write a chosen part of a dataset straight to disk, as TIFF, as OME-Zarr, or as both
 * <p>	The OPM Data Viewer's <b>Export...</b> is this: a region, a channel range and a time range
 * <br>	go from the dataset to files without ever becoming an image window. Materialising first
 * <br>	costs the whole region in RAM and then a save; here one plane is read, written and let go,
 * <br>	so what can be exported is bounded by the disk rather than by the heap.
 *
 * <p>	<b>It asks for planes; it does not know where they come from.</b> {@link Planes} is the
 * <br>	whole interface to the source: {@code OmeZarrView.regionPlanes} renders the runtime view of
 * <br>	a store - flip, alignment, side by side, the region - and {@code TiffResultView.regionPlanes}
 * <br>	reads a deflated TIFF result the same way. Both hand back exactly what the viewer would have
 * <br>	shown, so an export is what is on screen, saved.
 *
 * <p>	The TIFF is one deflated file holding the whole region, written plane by plane in the XYCZT
 * <br>	order ImageJ reads back. The OME-Zarr is a store of the region as its own dataset: the
 * <br>	pixels as they are (nothing left to flip or align), one channel of one time point written at
 * <br>	a time, committed as it goes, so the viewer can open it while it is still being written.
 *
 * @author ziqiang.huang@embl.de
 */
final class RegionExport {

	/** Deflate level for the exported TIFF; the toolset writes every TIFF at this level. */
	private static final int TIFF_LEVEL = FastTiffWriter.DEFAULT_LEVEL;

	private RegionExport() { }

	/** One plane of the region, in the coordinates the view produces; all indices 0 based. */
	interface Planes {
		ImageProcessor plane(int channel, int z, int timepoint) throws IOException;
	}

	/** What to export, where to, and in which format. */
	static final class Request {
		Planes planes;
		/** Base name of the files written, without an extension. */
		String name = "region";
		int width, height, depth, channels;
		/** The first source time point, and how many of them, as the source numbers them. */
		int firstTimepoint, frames = 1;
		final List<String> channelLabels = new ArrayList<String>();
		double pixelSizeUm = 1, voxelDepthUm = 1, frameIntervalSeconds = 0;
		boolean writeTiff, writeZarr, overwrite;
		/** The folder the files go in. */
		File folder;
		/** The dataset the region was taken from, for the record in the store. */
		File source;
		/** The region, in words, for the log and the status line. */
		String region = "";

		File tiffFile() { return new File(folder, VolumeIO.tiffPath(name)); }

		File zarrRoot() { return new File(folder, name + ".ome.zarr"); }

		/** Everything this request would write, so it can be refused before anything is. */
		List<File> targets() {
			List<File> targets = new ArrayList<File>();
			if (writeTiff) targets.add(tiffFile());
			if (writeZarr) targets.add(zarrRoot());
			return targets;
		}

		long pixelBytes() {
			return 2L * width * height * Math.max(1, depth) * Math.max(1, channels) * Math.max(1, frames);
		}
	}

	/**			Write the region
	 * <p>		The plane source is used from this thread only, and is not closed here - the caller
	 * <br>		owns it, as it owns the dataset it reads.
	 *
	 * @param request	: what to write and where
	 * <p>
	 * @return			: one line saying what was written, for the status line and the log
	 */
	static String run(Request request) throws IOException {
		if (request == null || request.planes == null) throw new IOException("Nothing to export.");
		if (request.folder == null) throw new IOException("Choose a folder to save into.");
		if (!request.writeTiff && !request.writeZarr) throw new IOException("Choose a format to save in.");
		if (request.width < 1 || request.height < 1 || request.depth < 1 || request.channels < 1)
			throw new IOException("The region is empty.");
		if (!request.folder.isDirectory() && !request.folder.mkdirs())
			throw new IOException("Could not create " + request.folder.getAbsolutePath());
		if (!request.overwrite) for (File target : request.targets())
			if (target.exists()) throw new IOException(target.getAbsolutePath()
					+ " exists already. Choose another folder or name, or tick overwrite.");

		List<String> written = new ArrayList<String>();
		if (request.writeTiff) written.add(writeTiff(request));
		if (request.writeZarr) written.add(writeZarr(request));
		return "exported " + request.width + "x" + request.height + "x" + request.depth
				+ ", " + request.channels + " channel(s), " + request.frames + " time point(s) as "
				+ join(written);
	}

	/** One deflated TIFF holding the region, streamed a plane at a time. */
	private static String writeTiff(final Request request) throws IOException {
		File file = request.tiffFile();
		FastTiffWriter.Layout layout = new FastTiffWriter.Layout();
		layout.width = request.width;
		layout.height = request.height;
		layout.channels = request.channels;
		layout.slices = request.depth;
		layout.frames = request.frames;
		layout.unit = "micron";
		/* One spacing, as every TIFF result this toolset writes carries: a deskewed volume is
		 * isotropic, and TiffResultDataset.Layout reads spacing for X and Y as well. */
		layout.pixelDepth = request.voxelDepthUm;
		layout.frameInterval = request.frameIntervalSeconds;
		final int channels = request.channels;
		final int depth = request.depth;
		FastTiffWriter.write(new FastTiffWriter.PlaneSource() {
			@Override public ImageProcessor plane(int index) throws IOException {
				// XYCZT: channel fastest, then Z, then T - the order ImageJ reads back
				int channel = index % channels;
				int z = (index / channels) % depth;
				int timepoint = request.firstTimepoint + index / (channels * depth);
				return request.planes.plane(channel, z, timepoint);
			}
		}, layout, file, TIFF_LEVEL);
		return file.getName() + " (" + IJ.d2s(file.length() / 1048576.0, 1) + " MB)";
	}

	/** The region as its own OME-Zarr dataset, one channel of one time point at a time. */
	private static String writeZarr(final Request request) throws IOException {
		File root = request.zarrRoot();
		if (root.exists() && request.overwrite)
			org.apache.commons.io.FileUtils.deleteDirectory(root);
		OmeZarrWriter writer = null;
		try {
			writer = new OmeZarrWriter(root);
			writer.createAppendableVolume(request.width, request.height, request.depth, request.channels);
			writer.markWriting();
			writer.writeMetadata(provenance(request, root), new ArrayList<String>());
			for (int frame = 0; frame < request.frames; frame++) {
				final int timepoint = request.firstTimepoint + frame;
				for (int channel = 0; channel < request.channels; channel++) {
					final int outputChannel = channel;
					writer.writeVolumeChannel(new OmeZarrWriter.VolumePlanes() {
						@Override public ImageProcessor plane(int z) throws IOException {
							return request.planes.plane(outputChannel, z, timepoint);
						}
					}, request.width, request.height, request.depth, channel, frame);
				}
				// committed as it goes, so the viewer can follow an export that is still running
				writer.commitTimePoint(frame, String.format("t%06d", timepoint),
						Arrays.asList(sourceName(request)), frame * request.frameIntervalSeconds);
			}
			writer.markComplete();
			return root.getName() + " ("
					+ IJ.d2s(org.apache.commons.io.FileUtils.sizeOfDirectory(root) / 1048576.0, 1) + " MB)";
		} finally {
			if (writer != null) writer.close();
		}
	}

	/**
	 * What the exported store says about itself.
	 *
	 * <p>Its pixels are the view as it was shown - flipped, aligned and composed already - so the
	 * alignment is recorded as applied and no matrix is carried over. A viewer opening it shows
	 * the channels as stored, which is what was exported.
	 */
	private static OpmProvenance provenance(Request request, File root) {
		OpmProvenance provenance = new OpmProvenance();
		provenance.contentKind = OpmProvenance.CONTENT_DESKEWED;
		provenance.datasetName = root.getName().replaceAll("(?i)[.]ome[.]zarr$", "");
		provenance.sourceFolder = request.source == null ? "" : request.source.getAbsolutePath();
		provenance.xyPixelSizeUm = request.pixelSizeUm;
		provenance.deskewedVoxelSizeUm = new double[] {
				request.pixelSizeUm, request.pixelSizeUm, request.voxelDepthUm };
		provenance.frameIntervalSeconds = request.frameIntervalSeconds;
		provenance.deskewMatrix = Transform.identity();
		provenance.alignApplied = true;
		provenance.channelLabels.addAll(request.channelLabels);
		while (provenance.channelLabels.size() < request.channels)
			provenance.channelLabels.add("C" + (provenance.channelLabels.size() + 1));
		return provenance.stampEnvironment(false);
	}

	private static String sourceName(Request request) {
		return request.source == null ? request.name : request.source.getName();
	}

	private static String join(List<String> parts) {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < parts.size(); i++) {
			if (i > 0) text.append(i + 1 == parts.size() ? " and " : ", ");
			text.append(parts.get(i));
		}
		return text.toString();
	}
}
