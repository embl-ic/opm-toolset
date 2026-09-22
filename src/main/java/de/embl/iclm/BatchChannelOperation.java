package de.embl.iclm;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.gui.YesNoCancelDialog;
import ij.measure.Calibration;
import ij.plugin.ChannelSplitter;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;

import java.awt.AWTEvent;
import java.awt.Checkbox;
import java.awt.Choice;
import java.awt.Label;
import java.awt.TextField;
import java.awt.event.TextEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;

/**
 * Split, flip, align and combine the camera halves of results that are already deskewed.
 *
 * <p><b>It never deskews.</b> Its input is a result folder, found exactly as the OPM Data Viewer
 * finds one ({@link DataFolder}), and what it does depends on the format it finds there:
 *
 * <ul>
 * <li>An <b>OME-Zarr</b> dataset stores its halves unflipped and unaligned, and carries the
 * alignment as metadata. Re-aligning one is therefore a metadata edit and nothing else: the
 * chosen matrix is written into its {@code .zattrs}
 * ({@link OmeZarrDataset#writeAlignMatrices}) and every view re-derives itself from it. No pixel
 * is read or written, and the untouched attributes are kept as {@code .zattrs.original}.</li>
 * <li>A <b>TIFF</b> result - plain, BigTIFF or deflated - holds finished pixels, so it is
 * processed: each chosen view is split into its halves, the chosen half flipped and aligned,
 * and the sources written in slot order. When the result has its deskewed volume, every
 * projection is recomputed from the operated volume, which is exact; without one only the Z
 * projections can be operated, because an X or Y projection has already collapsed the plane the
 * 2-D alignment is defined in, and an X projection of a whole camera width has merged the two
 * halves into one image.</li>
 * </ul>
 *
 * <p>A TIFF result can be written back as deflated TIFF, as OME-Zarr, or both. The OME-Zarr is
 * canonical: it stores the unflipped halves and records the alignment, the flip and the slot
 * order as metadata, so the viewer opens it as the operated result without a resampled pixel on
 * disk - the same thing this command does to an OME-Zarr input.
 */
public class BatchChannelOperation implements PlugIn {
	private static final String TITLE = "Batch Processing - Channel Operation";
	private static final String AUTO = "auto detect";
	private static final String MIRRORED = "mirrored left/right halves";
	private static final String EXISTING = "existing channel hyperstack (right already flipped)";
	static final String FLIP_RIGHT = "flip right half";
	static final String FLIP_LEFT = "flip left half";
	static final String SKIP_CHANNEL = "- (skip)";
	/**
	 * Acquisition files one time point may hold, each written as {@code _ChannelNNNN}.
	 * <p>
	 * The canonical OME-Zarr writer is already generic in this number: it stores both halves of
	 * every file it finds. The limit exists for the fixed-slot dialogs, which have to draw a
	 * row per output channel, and for ImageJ composite display, which shows at most eight
	 * channels at once. Four files at two halves each lands exactly on that ceiling.
	 */
	static final int MAX_ACQUISITION_CHANNELS = 4;
	/** Camera halves one result may carry: {@link #MAX_ACQUISITION_CHANNELS} files, two sides each. */
	static final int MAX_OUTPUT_CHANNELS = MAX_ACQUISITION_CHANNELS * 2;
	static final String[] CHANNEL_SOURCE_OPTIONS = buildChannelSourceOptions();

	/** The views a TIFF result can hold, in the order the dialog offers them. */
	static final String[] VIEW_KEYS = {
		TiffResultDataset.VOLUME, "maxX", "maxY", "maxZ", "meanX", "meanY", "meanZ"
	};
	private static final Pattern CHANNEL_TOKEN = Pattern.compile("(?i)_Channel(\\d+)");

	private static String[] buildChannelSourceOptions() {
		String[] options = new String[MAX_OUTPUT_CHANNELS + 1];
		for (int acquisition = 1; acquisition <= MAX_ACQUISITION_CHANNELS; acquisition++) {
			options[2 * acquisition - 2] = ChannelOperationSettings.sourceKey(acquisition, true);
			options[2 * acquisition - 1] = ChannelOperationSettings.sourceKey(acquisition, false);
		}
		options[MAX_OUTPUT_CHANNELS] = SKIP_CHANNEL;
		return options;
	}

	private Parameter parameter;
	private String inputLayout = AUTO;
	private boolean combineAcquisitionChannels = true;
	private boolean interpolate = true;
	private String flipHalf = FLIP_RIGHT;
	private final String[] channelOrder = ChannelOperationSettings.defaultChannelOrder();
	/** The views ticked in the dialog, in {@link #VIEW_KEYS} spelling. */
	private final List<String> wantedViews = new ArrayList<String>();

	static final class PreparedChannels {
		final List<ImagePlus> images = new ArrayList<ImagePlus>();
		/** The unsplit width, when a slot asked for {@code _ChannelNNNN-whole}; not a channel. */
		ImagePlus whole;
		int slices;
		int frames;

		void close() {
			for (ImagePlus image : images) BatchProcessingUtils.close(image);
			images.clear();
			BatchProcessingUtils.close(whole);
			whole = null;
		}
	}

	/** One output result: the TIFF results whose frames are combined into it. */
	static final class Group {
		final String name;
		final List<TiffResultDataset> members = new ArrayList<TiffResultDataset>();

		Group(String name) { this.name = name; }
	}


	// ---- the command ----------------------------------------------------------------

	@Override
	public void run(String arg) {
		Debug.commandStarted ( "Batch Processing > Channel Operation" );
		parameter = new Parameter("batch_channel");
		parameter.displayResult = false;
		if (!showDialog()) return;

		final File input = new File(parameter.inputDir.trim());
		if (!input.exists()) {
			IJ.error(TITLE, "The result folder does not exist:\n" + input);
			return;
		}
		String problem = selectionProblem();
		if (problem != null) { IJ.error(TITLE, problem); return; }
		final AlignmentMatrixSet alignment = AlignmentMatrixSet.load(parameter.alignmFile);
		if (alignment == null) {
			IJ.error(TITLE, "Choose the alignment matrix (CSV) to apply.\n\n"
					+ "Utilities > Channel Alignment measures one from a bead acquisition.");
			return;
		}
		final File outputRoot = outputRoot(input);
		if (BatchProcessingUtils.isInside(outputRoot, OmeZarrDataset.resolveDatasetFolder(input))
				|| sameFolder(outputRoot, OmeZarrDataset.resolveDatasetFolder(input))) {
			IJ.error(TITLE, "Save the results outside the folder being read:\n" + outputRoot
					+ "\n\nWritten inside it, the next scan would read them back as part of the"
					+ "\nsame results they were made from.");
			return;
		}

		DataFolder.Filter leaveOut = new DataFolder.Filter();
		leaveOut.outputRoot = outputRoot;
		final DataFolder folder = DataFolder.scan(input, leaveOut, false);
		if (folder.zarr.isEmpty() && folder.results.isEmpty()) {
			IJ.error(TITLE, "No TIFF result or OME-Zarr dataset was found in:\n" + input
					+ "\n\nThe same folder should list its datasets in the OPM Data Viewer.");
			return;
		}
		parameter.storeParam();

		final List<OmeZarrDataset> stores = confirmMetadataEdits(folder.zarr, alignment);
		if (stores == null) return;	// cancelled
		final List<Group> groups = group(folder.results);

		boolean finished = Shutdown.runCancellable("OPM Batch Channel Operation", new Runnable() {
			@Override public void run() { process(stores, groups, alignment, outputRoot); }
		});
		if (!finished)
			IJ.log("Batch Channel Operation stopped early: " + Shutdown.reason() + ".");
	}

	/**
	 * The run without its dialog, for a caller that already has the settings: every OME-Zarr in
	 * {@code folder} has its alignment metadata replaced and every TIFF result is operated.
	 *
	 * @param settings		: output format, sub-folders, skip/overwrite, GPU and the matrix path
	 * @param views			: the TIFF views to write, in {@link #VIEW_KEYS} spelling
	 */
	void run(DataFolder folder, AlignmentMatrixSet alignment, File outputRoot, Parameter settings,
			String... views) {
		parameter = settings;
		wantedViews.clear();
		for (String view : views) wantedViews.add(view);
		process(folder.zarr, group(folder.results), alignment, outputRoot);
	}

	/**
	 * Ask once before touching any OME-Zarr metadata, and say what will and will not change.
	 *
	 * @return	the stores to edit - all of them, or none when the user keeps them - or null to
	 * 			abandon the whole run
	 */
	private static List<OmeZarrDataset> confirmMetadataEdits(List<OmeZarrDataset> stores,
			AlignmentMatrixSet alignment) {
		if (stores.isEmpty() || IJ.getInstance() == null) return stores;
		YesNoCancelDialog ask = new YesNoCancelDialog(IJ.getInstance(), "Replace alignment metadata",
				"Write this alignment into the metadata of " + stores.size() + " OME-Zarr dataset"
				+ (stores.size() == 1 ? "" : "s") + "?\n\n"
				+ (alignment.isLegacy() ? "  one 2 x 3 matrix, right half onto left\n"
						: "  " + alignment.size() + " source matrices against " + alignment.reference() + "\n")
				+ "\nNo pixel data is read or written: an OME-Zarr stores its halves unaligned\n"
				+ "and applies the matrix when it is viewed. Each untouched .zattrs is kept\n"
				+ "as .zattrs.original the first time it is replaced.\n\n"
				+ "No leaves them as they are and still processes the TIFF results.");
		if (ask.cancelPressed()) return null;
		return ask.yesPressed() ? stores : new ArrayList<OmeZarrDataset>();
	}

	/**			Edit every OME-Zarr, then process every TIFF group, stopping cleanly when asked
	 * <p>		The checkpoint is once per time point of a group: its files are opened, operated
	 * 			and written as one result, so stopping between two leaves only whole results.
	 */
	private void process(List<OmeZarrDataset> stores, List<Group> groups,
			AlignmentMatrixSet alignment, File outputRoot) {
		int edited = 0, results = 0, failures = 0;
		for (OmeZarrDataset store : stores) {
			try {
				OmeZarrDataset.writeAlignMatrices(store.getRoot(), alignment, parameter.alignmFile);
				IJ.log("Batch Channel Operation: alignment metadata of " + store.getDisplayName()
						+ " replaced from " + parameter.alignmFile);
				edited++;
			} catch (Throwable failure) {
				failures++;
				IJ.log("Batch Channel Operation could not update " + store.getRoot() + ": " + failure);
			}
		}
		boolean stopped = false;
		for (int i = 0; i < groups.size() && !stopped; i++) {
			IJ.showProgress(i, groups.size());
			try {
				int[] outcome = processGroup(groups.get(i), alignment, outputRoot);
				results += outcome[0];
				failures += outcome[1];
				stopped = outcome[2] != 0;
			} catch (Throwable failure) {
				failures++;
				IJ.log("Batch Channel Operation failed for " + groups.get(i).name + ": " + failure);
				failure.printStackTrace();
			}
		}
		IJ.showProgress(1.0);
		IJ.log("Batch Channel Operation " + (stopped ? "stopped" : "finished") + ": "
				+ edited + " OME-Zarr metadata edit(s), " + results + " TIFF time point(s) written, "
				+ failures + " failure(s).");
	}


	// ---- one group of TIFF results --------------------------------------------------

	/** @return {written time points, failures, stopped ? 1 : 0} */
	private int[] processGroup(Group group, AlignmentMatrixSet alignment, File outputRoot)
			throws IOException {
		boolean everyVolume = true;
		for (TiffResultDataset member : group.members) everyVolume &= member.hasVolume();
		if (everyVolume) return processVolumes(group, alignment, outputRoot);
		if (wantsZarr())
			IJ.log("Batch Channel Operation: " + group.name + " has no deskewed volume, so no"
					+ " OME-Zarr is written for it - a dataset stores its volume.");
		return processProjections(group, alignment, outputRoot);
	}

	/** Operate the volume, write it, and recompute every chosen projection from it. */
	private int[] processVolumes(Group group, AlignmentMatrixSet alignment, File outputRoot)
			throws IOException {
		List<List<TiffResultDataset.Frame>> frames = pairFrames(group, TiffResultDataset.VOLUME);
		Calibration calibration = calibration(group.members.get(0).getView(TiffResultDataset.VOLUME));
		List<ProjectionBatch.Request> requests = requestedProjections();
		boolean saveVolume = wantedViews.contains(TiffResultDataset.VOLUME);
		boolean tiff = wantsTiff() && (saveVolume || !requests.isEmpty());
		boolean overwrite = overwrite();
		OmeZarrSession session = null;
		int written = 0, failures = 0;
		try {
			for (int t = 0; t < frames.size(); t++) {
				if (Shutdown.stopping()) return new int[] { written, failures, 1 };
				List<TiffResultDataset.Frame> point = frames.get(t);
				List<File> files = filesOf(point);
				String name = outputName(files);
				IJ.showStatus("Channel operation: " + name);

				boolean needTiff = tiff && (overwrite
						|| !tiffWritten(outputRoot, name, saveVolume, requests));
				boolean needZarr = false;
				if (wantsZarr()) {
					if (session == null) session = openSession(group, outputRoot, calibration, alignment);
					needZarr = session != null && !session.isCommitted(name);
				}
				if (!needTiff && !needZarr) continue;

				List<PreparedChannels> prepared = new ArrayList<PreparedChannels>();
				OpmTimepointProcessor.Result halves = new OpmTimepointProcessor.Result();
				ImagePlus composed = null;
				try {
					for (File file : files) {
						ImagePlus input = VolumeIO.open(file.getAbsolutePath());
						if (input == null) throw new IOException("Could not open " + file);
						try {
							if (needZarr) addCanonicalHalves(input, halves);
							if (needTiff) prepared.add(prepare(input, file.getName(), alignment));
						} finally {
							BatchProcessingUtils.close(input);
						}
					}
					if (needZarr && session != null) {
						OpmTimepointProcessor.TimePoint timePoint = new OpmTimepointProcessor.TimePoint(
								name, files, t * calibration.frameInterval);
						halves.channelLabels.addAll(OpmTimepointProcessor.channelLabels(timePoint));
						OmeZarrSession.PreparedTimePoint mips = OmeZarrSession.prepare(
								halves, parameter.tryGPU, true);
						try { session.appendPrepared(timePoint, mips); }
						finally { mips.close(); }
					}
					halves.close();		// the canonical halves are done with before the composite exists
					if (needTiff) {
						composed = combineSelected(prepared, files, name);
						BatchTiffOutput output = BatchTiffOutput.prepare(composed, calibration,
								requests, parameter.tryGPU);
						try { output.write(outputRoot, parameter.saveSeparate, saveVolume, overwrite); }
						finally { output.close(); }
					}
					written++;
				} catch (Throwable failure) {
					failures++;
					IJ.log("Batch Channel Operation failed for " + name + ": " + failure);
					failure.printStackTrace();
				} finally {
					halves.close();
					BatchProcessingUtils.close(composed);
					for (PreparedChannels item : prepared) item.close();
				}
			}
			if (session != null) session.markComplete();
		} finally {
			if (session != null) session.close();
		}
		return new int[] { written, failures, 0 };
	}

	/**
	 * Operate the projection images themselves, for a result that has no volume.
	 * <p>
	 * Only a Z projection still holds the X-Y plane the alignment is defined in; X and Y
	 * projections of such a result are passed over, and say so.
	 */
	private int[] processProjections(Group group, AlignmentMatrixSet alignment, File outputRoot) {
		int written = 0, failures = 0;
		boolean overwrite = overwrite();
		if (!wantsTiff()) return new int[] { 0, 0, 0 };
		for (String wanted : wantedViews) {
			if (TiffResultDataset.VOLUME.equals(wanted)) continue;
			char axis = wanted.charAt(wanted.length() - 1);
			if (axis != 'Z') {
				IJ.log("Batch Channel Operation: " + wanted + " of " + group.name + " is not operated -"
						+ " without the deskewed volume only a Z projection can be split and aligned.");
				continue;
			}
			String key = storedViewKey(group.members.get(0), wanted);
			if (key == null) continue;
			List<List<TiffResultDataset.Frame>> frames = pairFrames(group, key);
			String type = key.substring(0, key.length() - 1);
			for (List<TiffResultDataset.Frame> point : frames) {
				if (Shutdown.stopping()) return new int[] { written, failures, 1 };
				List<File> files = filesOf(point);
				String name = outputName(files);
				String volumeName = name.replaceFirst("(?i)-" + type + axis + "projection$", "");
				File target = BatchTiffOutput.projectionFile(outputRoot, parameter.saveSeparate,
						volumeName, type, String.valueOf(axis));
				if (!overwrite && VolumeIO.isCompleteTiff(target)) continue;
				List<PreparedChannels> prepared = new ArrayList<PreparedChannels>();
				ImagePlus composed = null;
				try {
					for (File file : files) {
						ImagePlus input = VolumeIO.open(file.getAbsolutePath());
						if (input == null) throw new IOException("Could not open " + file);
						try { prepared.add(prepare(input, file.getName(), alignment)); }
						finally { BatchProcessingUtils.close(input); }
					}
					composed = combineSelected(prepared, files, name);
					BatchTiffOutput.write(composed, target, overwrite, "projection TIFF");
					written++;
				} catch (Throwable failure) {
					failures++;
					IJ.log("Batch Channel Operation failed for " + name + ": " + failure);
				} finally {
					BatchProcessingUtils.close(composed);
					for (PreparedChannels item : prepared) item.close();
				}
			}
		}
		return new int[] { written, failures, 0 };
	}

	/**
	 * A canonical OME-Zarr for one group, created or resumed.
	 * <p>
	 * Canonical means what the deskew writes: unflipped halves, with the alignment, the flip and
	 * the slot order recorded as metadata so the viewer composes this operation's result at
	 * view time. That needs the halves themselves, which only a whole-width result still has;
	 * an existing channel hyperstack has already been flipped and aligned, so it is written as
	 * TIFF only.
	 */
	private OmeZarrSession openSession(Group group, File outputRoot, Calibration calibration,
			AlignmentMatrixSet alignment) throws IOException {
		if (hasExistingChannels(group)) {
			IJ.log("Batch Channel Operation: " + group.name + " is an existing channel hyperstack, so"
					+ " no OME-Zarr is written - its halves have already been flipped and aligned.");
			return null;
		}
		File root = new File(outputRoot, zarrName(group) + ".ome.zarr");
		if (root.exists() && overwrite()) FileUtils.deleteDirectory(root);
		return new OmeZarrSession(root, group.members.get(0).getRoot(),
				provenance(group, root, calibration, alignment), Transform.identity(),
				parameter.tryGPU, true);
	}

	private OpmProvenance provenance(Group group, File root, Calibration calibration,
			AlignmentMatrixSet alignment) {
		OpmProvenance provenance = new OpmProvenance();
		provenance.contentKind = OpmProvenance.CONTENT_DESKEWED;
		provenance.datasetName = root.getName().replaceAll("(?i)[.]ome[.]zarr$", "");
		provenance.sourceFolder = group.members.get(0).getRoot().getAbsolutePath();
		double pixel = calibration.pixelWidth > 0 && !"pixel".equalsIgnoreCase(calibration.getUnit())
				? calibration.pixelWidth : 1.0;
		provenance.xyPixelSizeUm = pixel;
		provenance.deskewedVoxelSizeUm = new double[] { pixel, pixel, pixel };
		provenance.frameIntervalSeconds = Math.max(0, calibration.frameInterval);
		provenance.deskewMatrix = Transform.identity();
		provenance.alignMatrix = alignment.legacyMatrix();
		if (!alignment.isLegacy()) {
			provenance.alignMatrices.putAll(alignment.matrices());
			provenance.alignReference = alignment.reference();
			provenance.alignMatrixConvention = AlignmentMatrixSet.CONVENTION;
		}
		provenance.alignMatrixSource = parameter.alignmFile;
		File source = new File(parameter.alignmFile);
		if (source.isFile())
			provenance.alignMatrixModifiedUtc = OpmProvenance.utcTimestamp(source.lastModified());
		provenance.alignApplied = false;
		provenance.alignFlipHalf = flipHalf;
		provenance.alignInterpolate = interpolate;
		/* The slot order is what this result is; recording it as a combined layout is what makes
		 * the viewer open the store composed that way (DeskewChannelView). */
		provenance.deskewCombineChannels = true;
		for (String source2 : channelOrder)
			if (!SKIP_CHANNEL.equals(source2)) provenance.deskewChannelOrder.add(source2);
		return provenance;
	}

	/**
	 * Whether this result's volumes are already channels rather than a whole camera width.
	 *
	 * <p>The same question {@link #prepare} answers per image, asked of the result's recorded
	 * layout so that it can be answered before a pixel is opened. It used to read the input layout
	 * <i>setting</i>, so on {@code auto detect} - the default - a channel hyperstack was not
	 * recognised here: {@link #addCanonicalHalves} then cut each channel image in half and stored
	 * those as the canonical halves, which is a store of the wrong pixels under the right labels.
	 */
	private boolean hasExistingChannels(Group group) {
		if (EXISTING.equals(inputLayout)) return true;
		if (MIRRORED.equals(inputLayout)) return false;
		for (TiffResultDataset member : group.members) {
			TiffResultDataset.View view = member.getView(TiffResultDataset.VOLUME);
			if (view == null) continue;
			try {
				if (view.getLayout().channels > 1) return true;
			} catch (IOException unreadable) {
				// it will be opened in a moment anyway; let that report the trouble
			}
		}
		return false;
	}

	/** The unflipped left and right halves of a whole-width volume, as the canonical store keeps them. */
	private static void addCanonicalHalves(ImagePlus input, OpmTimepointProcessor.Result halves) {
		int halfWidth = (input.getWidth() + 1) / 2;
		halves.channels.add(crop(input, 0, halfWidth, "left"));
		halves.channels.add(crop(input, input.getWidth() - halfWidth, halfWidth, "right"));
	}

	private static ImagePlus crop(ImagePlus input, int x, int width, String side) {
		ImageStack stack = new ImageStack(width, input.getHeight());
		for (int index = 1; index <= input.getStackSize(); index++) {
			ImageProcessor plane = input.getStack().getProcessor(index);
			plane.setRoi(x, 0, width, input.getHeight());
			stack.addSlice(input.getStack().getSliceLabel(index), plane.crop());
			plane.resetRoi();
		}
		ImagePlus half = new ImagePlus(input.getTitle() + "-" + side, stack);
		half.setCalibration(input.getCalibration().copy());
		return half;
	}


	// ---- grouping and naming --------------------------------------------------------

	/** One group per result, or one per set of {@code _ChannelNNNN} results when combining. */
	List<Group> group(List<TiffResultDataset> results) {
		Map<String, Group> groups = new TreeMap<String, Group>();
		for (TiffResultDataset result : results) {
			String key = combineAcquisitionChannels
					? CHANNEL_TOKEN.matcher(result.getDisplayName()).replaceFirst("_Channel####")
					: result.getDisplayName();
			Group group = groups.get(key);
			if (group == null) { group = new Group(key); groups.put(key, group); }
			group.members.add(result);
		}
		for (Group group : groups.values())
			Collections.sort(group.members, new Comparator<TiffResultDataset>() {
				@Override public int compare(TiffResultDataset a, TiffResultDataset b) {
					return Integer.compare(channelOf(a.getDisplayName()), channelOf(b.getDisplayName()));
				}
			});
		return new ArrayList<Group>(groups.values());
	}

	/**
	 * The frames of one view that every member of a group has, time point by time point.
	 * <p>
	 * Paired by the time number in the file name, so a member that is a time point short
	 * leaves that time point out rather than shifting every later one against the others.
	 */
	static List<List<TiffResultDataset.Frame>> pairFrames(Group group, String view) {
		List<Map<Long, TiffResultDataset.Frame>> byTime = new ArrayList<Map<Long, TiffResultDataset.Frame>>();
		for (TiffResultDataset member : group.members) {
			Map<Long, TiffResultDataset.Frame> frames = new TreeMap<Long, TiffResultDataset.Frame>();
			TiffResultDataset.View stored = member.getView(storedViewKey(member, view));
			if (stored == null) return new ArrayList<List<TiffResultDataset.Frame>>();
			long index = 0;
			for (TiffResultDataset.Frame frame : stored.getFrames())
				frames.put(frame.timeNumber > 0 ? frame.timeNumber : index++, frame);
			byTime.add(frames);
		}
		List<List<TiffResultDataset.Frame>> paired = new ArrayList<List<TiffResultDataset.Frame>>();
		for (Long time : byTime.get(0).keySet()) {
			List<TiffResultDataset.Frame> point = new ArrayList<TiffResultDataset.Frame>();
			for (Map<Long, TiffResultDataset.Frame> member : byTime) {
				TiffResultDataset.Frame frame = member.get(time);
				if (frame != null) point.add(frame);
			}
			if (point.size() == byTime.size()) paired.add(point);
		}
		return paired;
	}

	/** The key a result actually stores a view under: {@code meanZ} is {@code avgZ} on disk. */
	static String storedViewKey(TiffResultDataset dataset, String view) {
		if (dataset.getView(view) != null) return view;
		for (String key : DataFolder.views(dataset))
			if (view.equals(DataFolder.canonicalView(key))) return key;
		return null;
	}

	/**
	 * The name one output time point is written under, so the result reads as one dataset.
	 * <p>
	 * A single file keeps its own name - its {@code -deskewed} suffix is what the viewer and
	 * every later batch step recognise. Combined files take the first name with its
	 * {@code _ChannelNNNN} widened to the range they cover, and nothing appended after it: a
	 * suffix after {@code -deskewed} is a name {@link TiffResultDataset} no longer knows.
	 */
	static String outputName(List<File> files) {
		String name = BatchProcessingUtils.baseName(files.get(0));
		if (files.size() < 2) return name;
		Matcher token = CHANNEL_TOKEN.matcher(name);
		if (!token.find()) return name;
		int first = Integer.MAX_VALUE, last = Integer.MIN_VALUE;
		for (File file : files) {
			int channel = BatchProcessingUtils.acquisitionChannel(file);
			if (channel < 0) continue;
			first = Math.min(first, channel);
			last = Math.max(last, channel);
		}
		return first == Integer.MAX_VALUE ? name
				: token.replaceFirst(String.format(Locale.US, "_Channels%04d-%04d", first, last));
	}

	/** The OME-Zarr name for a group: its dataset name with the time and view tokens gone. */
	private static String zarrName(Group group) {
		String name = group.members.get(0).getDisplayName();
		if (group.members.size() > 1) {
			int first = channelOf(group.members.get(0).getDisplayName());
			int last = channelOf(group.members.get(group.members.size() - 1).getDisplayName());
			Matcher token = CHANNEL_TOKEN.matcher(name);
			if (token.find() && first > 0)
				name = token.replaceFirst(String.format(Locale.US, "_Channels%04d-%04d", first, last));
		}
		return name.replaceAll("(?i)_Time(?=$|[-_.])", "").replaceAll("[-_]+$", "");
	}

	private static int channelOf(String name) {
		Matcher token = CHANNEL_TOKEN.matcher(name);
		if (!token.find()) return 0;
		try { return Integer.parseInt(token.group(1)); } catch (NumberFormatException e) { return 0; }
	}

	private static List<File> filesOf(List<TiffResultDataset.Frame> frames) {
		List<File> files = new ArrayList<File>();
		for (TiffResultDataset.Frame frame : frames) files.add(frame.file);
		return files;
	}

	/** Whether skip mode would still find every requested TIFF of a time point on disk. */
	private boolean tiffWritten(File outputRoot, String name, boolean saveVolume,
			List<ProjectionBatch.Request> requests) {
		if (saveVolume && !VolumeIO.isCompleteTiff(
				BatchTiffOutput.volumeFile(outputRoot, parameter.saveSeparate, name))) return false;
		for (ProjectionBatch.Request request : requests)
			if (!VolumeIO.isCompleteTiff(BatchTiffOutput.projectionFile(outputRoot,
					parameter.saveSeparate, name, request.type, String.valueOf(request.axis))))
				return false;
		return true;
	}

	/** The ticked projections, as requests the projection batch understands (mean is avg on disk). */
	private List<ProjectionBatch.Request> requestedProjections() {
		List<ProjectionBatch.Request> requests = new ArrayList<ProjectionBatch.Request>();
		for (String view : wantedViews) {
			if (TiffResultDataset.VOLUME.equals(view)) continue;
			String type = view.substring(0, view.length() - 1);
			requests.add(new ProjectionBatch.Request(view.substring(view.length() - 1),
					"mean".equals(type) ? "avg" : type));
		}
		return requests;
	}

	private static Calibration calibration(TiffResultDataset.View view) throws IOException {
		TiffResultDataset.Layout layout = view.getLayout();
		Calibration calibration = new Calibration();
		calibration.pixelWidth = layout.pixelWidth;
		calibration.pixelHeight = layout.pixelHeight;
		calibration.pixelDepth = layout.pixelDepth;
		calibration.setUnit(layout.unit == null ? "pixel" : layout.unit);
		calibration.frameInterval = layout.frameInterval;
		calibration.setTimeUnit("second");
		return calibration;
	}

	private File outputRoot(File input) {
		String typed = parameter.saveDir == null ? "" : parameter.saveDir.trim();
		if (!typed.isEmpty()) return new File(typed).getAbsoluteFile();
		File folder = OmeZarrDataset.resolveDatasetFolder(input).getAbsoluteFile();
		File parent = folder.getParentFile();
		return new File(parent == null ? folder : parent, folder.getName() + "-channel-operation");
	}

	private static boolean sameFolder(File a, File b) {
		try { return a.getCanonicalFile().equals(b.getCanonicalFile()); }
		catch (IOException e) { return a.getAbsoluteFile().equals(b.getAbsoluteFile()); }
	}

	private boolean wantsTiff() { return !Parameter.FORMAT_ZARR.equals(parameter.outputFormat); }
	private boolean wantsZarr() { return !Parameter.FORMAT_TIFF.equals(parameter.outputFormat); }
	private boolean overwrite() { return "overwrite".equals(parameter.fileExistStr); }

	private String selectionProblem() {
		if (selectedChannelCount() == 0) return "Select at least one output channel source.";
		boolean whole = false, half = false;
		for (String selected : channelOrder) {
			if (SKIP_CHANNEL.equals(selected)) continue;
			if (ChannelOperationSettings.isWholeSource(selected)) whole = true;
			else half = true;
		}
		if (whole && half) return "Whole-width and half-width sources cannot share one result.";
		if (wantedViews.isEmpty() && wantsTiff() && Parameter.FORMAT_TIFF.equals(parameter.outputFormat))
			return "Tick at least one TIFF result view to write.";
		return null;
	}


	// ---- the dialog -----------------------------------------------------------------

	/**
	 * The input is a result folder, scanned as the viewer scans one, and the dialog says what
	 * it found while it is still open: a line counting the datasets, and one check box per view
	 * greyed where no result can supply it. The scan runs off the event thread and only the
	 * newest one is shown, so typing a path does not stall the dialog.
	 */
	private boolean showDialog() {
		final ChannelOperationSettings channels = new ChannelOperationSettings();
		channels.load();
		final OpmDialog gd = new OpmDialog(TITLE);
		Parameter.styleDialog(gd);
		final int length = 55, inset = 95, section = 20;

		gd.setInsets(0, 15, 5);
		Parameter.addSection(gd, "Input setup:");
		gd.addDirectoryField("result folder", parameter.inputDir, length);
		final TextField folderField = Parameter.lastStringOrNumber(gd.getStringFields());
		gd.setInsets(0, inset, 0);
		gd.addMessage("Scanning for TIFF results and OME-Zarr datasets, as the OPM Data Viewer does...");
		final Label summary = (Label) gd.getMessage();
		gd.setInsets(5, inset, 0);
		gd.addMessage("TIFF result views to operate:");
		int firstView = gd.getCheckboxes() == null ? 0 : gd.getCheckboxes().size();
		gd.setInsets(0, inset, 0);
		gd.addCheckbox("deskewed volume", true);
		gd.setInsets(0, inset, 0);
		gd.addCheckboxGroup(1, 3, new String[] { "maxX", "maxY", "maxZ" }, new boolean[] { true, true, true });
		gd.setInsets(0, inset, 0);
		gd.addCheckboxGroup(1, 3, new String[] { "meanX", "meanY", "meanZ" }, new boolean[] { true, true, true });
		final List<Checkbox> viewBoxes = new ArrayList<Checkbox>();
		for (int i = firstView; i < gd.getCheckboxes().size(); i++)
			viewBoxes.add((Checkbox) gd.getCheckboxes().get(i));

		gd.setInsets(section, 15, 5);
		Parameter.addSection(gd, "Channels:");
		gd.addChoice("input layout", new String[] { AUTO, MIRRORED, EXISTING }, inputLayout);
		gd.addChoice("interpolation", Parameter.INTERPOLATION_OPTIONS,
				Parameter.interpolationChoice(channels.interpolate));
		gd.addFileField("align matrix", parameter.alignmFile, length);
		gd.setInsets(0, inset, 0);
		channels.addToDialog(gd, inset);

		gd.setInsets(section, 15, 5);
		Parameter.addSection(gd, "Output setup:");
		gd.addDirectoryField("save to", parameter.saveDir, length);
		gd.addChoice("format", Parameter.OUTPUT_FORMATS,
				Parameter.isOutputFormat(parameter.outputFormat) ? parameter.outputFormat : Parameter.FORMAT_TIFF);
		final Choice formatChoice = (Choice) gd.getChoices().lastElement();
		gd.setInsets(0, inset, 0);
		gd.addCheckbox("separate results to sub-folders", parameter.saveSeparate);
		final Checkbox chkSeparate = Parameter.lastCheckbox(gd);
		gd.addChoice("if result exists", new String[] { "skip", "overwrite" }, parameter.fileExistStr);
		gd.setInsets(5, inset, 0);
		gd.addMessage("An empty 'save to' writes beside the input, in <result folder>-channel-operation.");

		final DataFolder.Watch scanner = new DataFolder.Watch(new DataFolder.Scan() {
			@Override public DataFolder scan(File selection) { return DataFolder.scanResults(selection); }
		}, showScan(gd, summary, viewBoxes));
		gd.addDialogListener(new DialogListener() {
			@Override public boolean dialogItemChanged(GenericDialog dialog, AWTEvent event) {
				if (event == null || (event instanceof TextEvent && event.getSource() == folderField))
					scanner.request(folderField.getText());
				// the view layout below describes TIFF output; an OME-Zarr has its own
				Parameter.enable(chkSeparate, !Parameter.FORMAT_ZARR.equals(formatChoice.getSelectedItem()));
				return true;
			}
		});
		Parameter.enable(chkSeparate, !Parameter.FORMAT_ZARR.equals(formatChoice.getSelectedItem()));
		scanner.request(folderField.getText());

		gd.addHelp(Help.channelOperation);
		gd.showDialog();
		if (gd.wasCanceled()) return false;

		parameter.inputDir = gd.getNextString();
		wantedViews.clear();
		for (int i = 0; i < VIEW_KEYS.length; i++) {
			boolean ticked = gd.getNextBoolean();
			if (ticked && viewBoxes.get(i).isEnabled()) wantedViews.add(VIEW_KEYS[i]);
		}
		inputLayout = gd.getNextChoice();
		channels.interpolate = Parameter.isBilinear(gd.getNextChoice());
		parameter.alignmFile = gd.getNextString();
		channels.readFrom(gd);
		parameter.saveDir = gd.getNextString();
		parameter.outputFormat = gd.getNextChoice();
		parameter.saveSeparate = gd.getNextBoolean();
		parameter.fileExistStr = gd.getNextChoice();
		channels.store();

		interpolate = channels.interpolate;
		flipHalf = channels.flipHalf;
		combineAcquisitionChannels = channels.combineAcquisitionChannels;
		System.arraycopy(channels.channelOrder, 0, channelOrder, 0, channelOrder.length);
		return true;
	}

	/**
	 * What the dialog shows for one scan: the summary line and the view check boxes.
	 * <p>
	 * A view box is enabled where some result can supply that view - a Z projection from its own
	 * files or from a volume, an X or Y projection from a volume only - and ticked where the
	 * folder already holds it, so the default reproduces the results that are there.
	 */
	private static DataFolder.Show showScan(final GenericDialog dialog, final Label summary,
			final List<Checkbox> views) {
		return new DataFolder.Show() {
			@Override public void show(DataFolder found) {
				boolean volume = false;
				java.util.Set<String> kinds = new java.util.HashSet<String>();
				if (found != null) {
					for (TiffResultDataset result : found.results) volume |= result.hasVolume();
					kinds.addAll(found.viewKinds());
				}
				summary.setText(found == null ? "Choose the folder that holds the results."
						: found.summary() + (found.zarr.isEmpty() ? ""
								: " OME-Zarr: only its alignment metadata is replaced."));
				for (int i = 0; i < VIEW_KEYS.length && i < views.size(); i++) {
					String key = VIEW_KEYS[i];
					boolean zProjection = key.endsWith("Z") && !TiffResultDataset.VOLUME.equals(key);
					boolean available = volume || (zProjection && kinds.contains(key));
					views.get(i).setEnabled(available);
					views.get(i).setState(available && kinds.contains(key));
				}
				if (summary.getPreferredSize().width > summary.getWidth() && dialog.isShowing())
					dialog.pack();
			}
		};
	}


	// ---- the operation itself -------------------------------------------------------

	PreparedChannels prepare(ImagePlus input, String fileName, double[][] matrix) {
		return prepare(input, fileName, AlignmentMatrixSet.legacy(matrix));
	}

	PreparedChannels prepare(ImagePlus input, String fileName, AlignmentMatrixSet alignment) {
		boolean splitMirrored = MIRRORED.equals(inputLayout) ||
				(AUTO.equals(inputLayout) && input.getNChannels() == 1);
		if (splitMirrored) return splitMirrored(input, fileName, alignment);
		return alignExistingChannels(input, fileName,
				alignment == null ? null : alignment.legacyMatrix());
	}

	/**
	 * Split a whole camera width into its halves, mirroring and aligning each as the one
	 * placement rule says ({@link AlignmentMatrixSet#placement}).
	 * <p>
	 * The rule is the one Deskew Batch, Live and the viewer use, so a bare CSV means what it
	 * always meant here - the chosen half mirrored, the one matrix applied to it - and a tagged
	 * set gives every source of every file its own matrix against the shared reference.
	 */
	private PreparedChannels splitMirrored(ImagePlus input, String fileName, AlignmentMatrixSet alignment) {
		if (input.getWidth() < 2) throw new IllegalArgumentException("Image is too narrow to split.");
		PreparedChannels result = new PreparedChannels();
		int halfWidth = (input.getWidth() + 1) / 2;
		boolean flipLeft = FLIP_LEFT.equals(flipHalf);
		int acquisition = BatchProcessingUtils.acquisitionChannel(new File(fileName));
		if (acquisition < 1) acquisition = 1;
		AlignmentMatrixSet.Placement leftPlace = AlignmentMatrixSet.placement(alignment,
				ChannelOperationSettings.sourceKey(acquisition, true), flipLeft, halfWidth);
		AlignmentMatrixSet.Placement rightPlace = AlignmentMatrixSet.placement(alignment,
				ChannelOperationSettings.sourceKey(acquisition, false), flipLeft, halfWidth);
		ImageStack leftStack = new ImageStack(halfWidth, input.getHeight());
		ImageStack rightStack = new ImageStack(halfWidth, input.getHeight());
		for (int index = 1; index <= input.getStackSize(); index++) {
			ImageProcessor source = input.getStack().getProcessor(index);
			ImageProcessor leftSource = source.duplicate();
			leftSource.setRoi(0, 0, halfWidth, input.getHeight());
			ImageProcessor rightSource = source.duplicate();
			rightSource.setRoi(input.getWidth() - halfWidth, 0, halfWidth, input.getHeight());
			leftStack.addSlice(input.getStack().getSliceLabel(index), place(leftSource.crop(), leftPlace));
			rightStack.addSlice(input.getStack().getSliceLabel(index), place(rightSource.crop(), rightPlace));
		}

		int[] zt = inferSlicesAndFrames(input, fileName);
		String baseName = BatchProcessingUtils.baseName(new File(fileName));
		ImagePlus left = new ImagePlus(baseName + (flipLeft ? "-C-left-flipped-aligned" : "-C-left"), leftStack);
		ImagePlus right = new ImagePlus(baseName + (flipLeft ? "-C-right" : "-C-right-flipped-aligned"), rightStack);
		left.setDimensions(1, zt[0], zt[1]);
		right.setDimensions(1, zt[0], zt[1]);
		left.setOpenAsHyperStack(zt[0] > 1 || zt[1] > 1);
		right.setOpenAsHyperStack(zt[0] > 1 || zt[1] > 1);
		copyCalibration(input, left);
		copyCalibration(input, right);
		result.images.add(left);
		result.images.add(right);
		/* The whole width too, but only for a slot that asks for _ChannelNNNN-whole: the deskew
		 * shear acts in Y and Z only, so an unsplit deskewed width is already that source. Kept
		 * apart from the channels, and not made at all otherwise - it is a full copy. */
		if (selectsWholeWidth()) {
			result.whole = input.duplicate();
			result.whole.setTitle(baseName + "-C-whole");
			result.whole.setDimensions(1, zt[0], zt[1]);
			copyCalibration(input, result.whole);
		}
		result.slices = zt[0];
		result.frames = zt[1];
		return result;
	}

	private ImageProcessor place(ImageProcessor half, AlignmentMatrixSet.Placement placement) {
		if (placement.mirror) half.flipHorizontal();
		return placement.matrix == null ? half
				: SIFT.alignWithRigid2DMatrix(half, placement.matrix, interpolate);
	}

	/**
	 * Split a finished channel hyperstack, and align one side of each pair where a matrix says so.
	 *
	 * <p>Every channel comes back, in order, and {@link #combineSelected} gives them the slots
	 * {@code _Channel0001-left}, {@code -right}, {@code _Channel0002-left}, ... - which is the
	 * order a combined result is written in - so all of them can be reordered and dropped, not
	 * only the first two.
	 *
	 * <p><b>No matrix means no alignment</b>, not a refusal: these channels have been flipped and
	 * aligned already, and reordering or dropping them is a use of its own. A matrix given here is
	 * applied <i>on top</i> of the alignment in the pixels - it cannot undo it.
	 */
	private PreparedChannels alignExistingChannels(ImagePlus input, String fileName, double[][] matrix) {
		if (input.getNChannels() < 2)
			throw new IllegalArgumentException("Existing-channel mode needs an ImageJ hyperstack with at least two channels.");
		PreparedChannels result = new PreparedChannels();
		ImagePlus[] channels = ChannelSplitter.split(input);
		boolean alignLeft = FLIP_LEFT.equals(flipHalf);
		double[][] appliedMatrix = matrix == null ? null
				: alignLeft ? Transform.inverseAlignmentMatrix2D(matrix) : matrix;
		for (int i = 0; i < channels.length; i++) {
			channels[i].setTitle(BatchProcessingUtils.baseName(new File(fileName)) + "-C" + (i + 1));
			if (appliedMatrix != null && ((alignLeft && i % 2 == 0) || (!alignLeft && i % 2 == 1)))
				SIFT.alignStackSIFT2(channels[i], appliedMatrix, interpolate);
			result.images.add(channels[i]);
		}
		result.slices = Math.max(1, input.getNSlices());
		result.frames = Math.max(1, input.getNFrames());
		return result;
	}

	ImagePlus combine(List<PreparedChannels> prepared, String title) {
		if (prepared.isEmpty()) return null;
		int slices = prepared.get(0).slices;
		int frames = prepared.get(0).frames;
		List<ImagePlus> channels = new ArrayList<ImagePlus>();
		for (PreparedChannels item : prepared) {
			if (item.slices != slices || item.frames != frames)
				throw new IllegalArgumentException("Matching acquisition channels have different Z/T dimensions.");
			channels.addAll(item.images);
		}
		return combineChannels(channels, slices, frames, title);
	}

	ImagePlus combineSelected(List<PreparedChannels> prepared, List<File> sourceFiles, String title) {
		if (prepared.isEmpty() || prepared.size() != sourceFiles.size()) return null;
		int slices = prepared.get(0).slices;
		int frames = prepared.get(0).frames;
		Map<String, ImagePlus> sources = new LinkedHashMap<String, ImagePlus>();
		for (int i = 0; i < prepared.size(); i++) {
			PreparedChannels item = prepared.get(i);
			if (item.slices != slices || item.frames != frames)
				throw new IllegalArgumentException("Matching acquisition channels have different Z/T dimensions.");
			if (item.images.size() < 2)
				throw new IllegalArgumentException("Each acquisition channel must provide a left and right image.");
			int acquisitionChannel = BatchProcessingUtils.acquisitionChannel(sourceFiles.get(i));
			if (acquisitionChannel < 0) acquisitionChannel = i + 1;
			/* A split whole width is one acquisition channel's two halves. A finished channel
			 * hyperstack can hold more than two, and they take the slots in the order a combined
			 * result is written in - left, right, next acquisition channel's left, ... - so every
			 * channel can be reordered or dropped. Only the first two used to be reachable; the
			 * rest were silently left out of the result. */
			for (int channel = 0; channel < item.images.size(); channel++) {
				String key = ChannelOperationSettings.sourceKey(
						acquisitionChannel + channel / 2, channel % 2 == 0);
				if (sources.containsKey(key)) {
					IJ.log("Batch Channel Operation: two inputs both offer " + key
							+ "; the first one is used. Name the files _ChannelNNNN to tell them apart.");
					continue;
				}
				sources.put(key, item.images.get(channel));
			}
			if (item.whole != null)
				sources.put(ChannelOperationSettings.wholeSourceKey(acquisitionChannel), item.whole);
		}

		List<ImagePlus> channels = new ArrayList<ImagePlus>();
		for (String selected : channelOrder) {
			if (SKIP_CHANNEL.equals(selected)) continue;
			ImagePlus source = sources.get(selected);
			if (source == null) {
				IJ.log("Batch Channel Operation: selected source is unavailable and was skipped: " + selected);
				continue;
			}
			channels.add(source);
		}
		if (channels.isEmpty())
			throw new IllegalArgumentException("None of the selected channel sources is present in this file group.");
		return combineChannels(channels, slices, frames, title);
	}

	private ImagePlus combineChannels(List<ImagePlus> channels, int slices, int frames, String title) {
		ImagePlus first = channels.get(0);
		ImageStack stack = new ImageStack(first.getWidth(), first.getHeight());
		for (ImagePlus channel : channels) {
			if (channel.getWidth() != first.getWidth() || channel.getHeight() != first.getHeight())
				throw new IllegalArgumentException("Matching acquisition channels have different XY dimensions.");
		}
		for (int t = 1; t <= frames; t++) {
			for (int z = 1; z <= slices; z++) {
				for (ImagePlus channel : channels) {
					int index = channel.getStackIndex(1, z, t);
					stack.addSlice(channel.getTitle(), channel.getStack().getProcessor(index).duplicate());
				}
			}
		}
		ImagePlus result = new ImagePlus(title, stack);
		result.setDimensions(channels.size(), slices, frames);
		result.setOpenAsHyperStack(true);
		copyCalibration(first, result);
		return result;
	}

	/** Name the leading output channels; the slots past the ones given are skipped. */
	void setChannelOrder(String... sources) {
		if (sources == null || sources.length == 0 || sources.length > channelOrder.length)
			throw new IllegalArgumentException("Between 1 and " + channelOrder.length
					+ " channel selections are required, not "
					+ (sources == null ? 0 : sources.length) + ".");
		for (int i = 0; i < channelOrder.length; i++)
			channelOrder[i] = i < sources.length ? sources[i] : SKIP_CHANNEL;
	}

	void setFlipHalf(String selection) {
		if (!FLIP_RIGHT.equals(selection) && !FLIP_LEFT.equals(selection))
			throw new IllegalArgumentException("Unknown flip selection: " + selection);
		flipHalf = selection;
	}

	void setCombineAcquisitionChannels(boolean combine) {
		combineAcquisitionChannels = combine;
	}

	private boolean selectsWholeWidth() {
		for (String selected : channelOrder)
			if (ChannelOperationSettings.isWholeSource(selected)) return true;
		return false;
	}

	private int selectedChannelCount() {
		int count = 0;
		for (String selected : channelOrder) {
			if (!SKIP_CHANNEL.equals(selected)) count++;
		}
		return count;
	}

	private int[] inferSlicesAndFrames(ImagePlus input, String fileName) {
		int slices = Math.max(1, input.getNSlices());
		int frames = Math.max(1, input.getNFrames());
		String lower = fileName.toLowerCase();
		if (input.getNChannels() == 1 && frames == 1 && slices == input.getStackSize() &&
				(lower.contains("projection-timelapse") || lower.contains("projection-time-lapse") ||
				 lower.contains("projection_movie") || lower.contains("projection-movie"))) {
			frames = slices;
			slices = 1;
		}
		return new int[] { slices, frames };
	}

	private void copyCalibration(ImagePlus source, ImagePlus destination) {
		Calibration calibration = source.getCalibration();
		if (calibration != null) destination.setCalibration(calibration.copy());
	}

}
