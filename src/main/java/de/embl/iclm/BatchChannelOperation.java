package de.embl.iclm;

import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.measure.Calibration;
import ij.plugin.ChannelSplitter;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Splits the mirrored camera halves, aligns the selected half onto the unchanged
 * reference half, and combines matching acquisition files into a multichannel TIFF.
 */
public class BatchChannelOperation implements PlugIn {
	private static final String AUTO = "auto detect";
	private static final String MIRRORED = "mirrored left/right halves";
	private static final String EXISTING = "existing channel hyperstack (right already flipped)";
	static final String FLIP_RIGHT = "flip right half";
	static final String FLIP_LEFT = "flip left half";
	private static final String[] FLIP_OPTIONS = { FLIP_RIGHT, FLIP_LEFT };
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
	private File inputFolder;

	static final class PreparedChannels {
		final List<ImagePlus> images = new ArrayList<ImagePlus>();
		int slices;
		int frames;

		void close() {
			for (ImagePlus image : images) BatchProcessingUtils.close(image);
			images.clear();
		}
	}

	@Override
	public void run(String arg) {
		Party.commandStarted ( "Batch Processing > Channel Operation" );
		parameter = new Parameter("batch_channel");
		parameter.displayResult = false;
		loadChannelOrder();
		if (!showDialog()) return;
		if (selectedChannelCount() == 0) {
			IJ.error("Batch Channel Operation", "Select at least one output channel source.");
			return;
		}

		inputFolder = new File(parameter.inputDir);
		if (!inputFolder.isDirectory()) {
			IJ.error("Batch Channel Operation", "Input folder does not exist.");
			return;
		}
		double[][] matrix = IO.loadMatrixFromFile(parameter.alignmFile);
		if (!is2dMatrix(matrix)) {
			IJ.error("Batch Channel Operation", "The alignment CSV must contain at least a 2 x 3 matrix.");
			return;
		}

		List<File> files = BatchProcessingUtils.listTiffs(inputFolder, parameter.keywords, parameter.recursive);
		if (!parameter.saveToSame && parameter.saveDir != null && !parameter.saveDir.trim().isEmpty())
			files = BatchProcessingUtils.excludeTree(files, new File(parameter.saveDir));
		List<File> inputs = new ArrayList<File>();
		for (File file : files) {
			if (!BatchProcessingUtils.baseName(file).toLowerCase().endsWith("-aligned")) inputs.add(file);
		}
		if (inputs.isEmpty()) {
			IJ.error("Batch Channel Operation", "No matching TIFF image was found.");
			return;
		}

		final Map<String, List<File>> groups = groupFiles(inputs);
		final double[][] alignment = matrix;

		/* On a daemon thread, so ImageJ's non-daemon Executer thread cannot hold the JVM open
		 * after Fiji has closed, and so Batch Processing > Terminate can list and stop it. */
		boolean finished = Shutdown.runCancellable("OPM Batch Channel Operation", new Runnable() {
			@Override public void run() { process(groups, alignment); }
		});
		if (!finished)
			IJ.log("Batch Channel Operation stopped early: " + Shutdown.reason() + ".");
	}

	/**			Combine every acquisition group, stopping cleanly when asked to
	 * <p>		The checkpoint is once per group, which is the unit of work: a group's files
	 * 			are opened, combined and written as one result, so stopping between two groups
	 * 			leaves only whole results behind.
	 */
	private void process(Map<String, List<File>> groups, double[][] matrix) {
		boolean overwrite = "overwrite".equals(parameter.fileExistStr);
		int failures = 0;
		int completed = 0;
		boolean stopped = false;
		for (List<File> group : groups.values()) {
			if (Shutdown.stopping()) {
				stopped = true;
				IJ.log("Batch Channel Operation stopping after " + (completed + failures)
						+ " of " + groups.size() + " group(s): " + Shutdown.reason() + ".");
				break;
			}
			Collections.sort(group, new Comparator<File>() {
				@Override
				public int compare(File a, File b) {
					return Integer.compare(BatchProcessingUtils.acquisitionChannel(a),
							BatchProcessingUtils.acquisitionChannel(b));
				}
			});
			File first = group.get(0);
			File saveRoot = BatchProcessingUtils.saveRootFor(first, inputFolder, parameter.saveDir,
					parameter.saveToSame, parameter.recursive);
			File folder = parameter.saveSeparate ? new File(saveRoot, "channel-aligned") : saveRoot;
			String outputName = BatchProcessingUtils.channelGroupOutputName(group);
			File output = new File(folder, outputName + ".tif");
			if (output.exists() && !overwrite) {
				IJ.log("Batch Channel Operation skip existing result: " + output.getAbsolutePath());
				completed++;
				continue;
			}

			List<PreparedChannels> prepared = new ArrayList<PreparedChannels>();
			ImagePlus combined = null;
			try {
				for (File file : group) {
					IJ.showStatus("Channel operation: " + file.getName());
					ImagePlus input = VolumeIO.open(file.getAbsolutePath());
					if (input == null) throw new IllegalArgumentException("Could not open " + file.getName());
					try {
						prepared.add(prepare(input, file.getName(), matrix));
					} finally {
						BatchProcessingUtils.close(input);
					}
				}
				combined = combineSelected(prepared, group, outputName);
				if (!BatchProcessingUtils.saveTiff(combined, output, overwrite))
					throw new IllegalStateException("Could not save " + output.getAbsolutePath());
				completed++;
			} catch (Throwable t) {
				failures++;
				IJ.log("Batch Channel Operation failed for group " + first.getName() + ": " + t.getMessage());
				t.printStackTrace();
			} finally {
				BatchProcessingUtils.close(combined);
				for (PreparedChannels item : prepared) item.close();
			}
			IJ.showProgress(completed + failures, groups.size());
		}

		parameter.storeParam();
		IJ.showProgress(1.0);
		IJ.log("Batch Channel Operation " + (stopped ? "stopped" : "finished") + ": "
				+ completed + " group(s), " + failures + " failure(s).");
	}

	private boolean showDialog() {
		GenericDialogPlus gd = new PartyDialogPlus("Batch Processing - Channel Operation");
		Parameter.styleDialog( gd );
		int length = 42;
		gd.addDirectoryField("input folder...", parameter.inputDir, length);
		gd.addStringField("file name contains (comma-separated)", parameter.keywords, length);
		gd.addCheckbox("recursive", parameter.recursive);
		gd.addChoice("input layout", new String[] { AUTO, MIRRORED, EXISTING }, inputLayout);
		gd.addChoice("flip and align", FLIP_OPTIONS, flipHalf);
		gd.addFileField("alignment matrix (CSV)", parameter.alignmFile, length);
		gd.addCheckbox("combine matching _ChannelNNNN files", combineAcquisitionChannels);
		gd.addMessage("Output channel order and source:");
		for (int i = 0; i < channelOrder.length; i++)
			gd.addChoice(ordinal(i + 1) + " channel", CHANNEL_SOURCE_OPTIONS, channelOrder[i]);
		gd.addChoice("interpolation", Parameter.INTERPOLATION_OPTIONS,
				Parameter.interpolationChoice(interpolate));
		gd.addDirectoryField("save to...", parameter.saveDir, length);
		gd.addCheckbox("save result to the same (data) folder", parameter.saveToSame);
		gd.addCheckbox("separate results to sub-folders", parameter.saveSeparate);
		gd.addChoice("if result exists", new String[] { "skip", "overwrite" }, parameter.fileExistStr);
		gd.addMessage("Each _ChannelNNNN file supplies a left and a right source.\n" +
				"Mirrored input: the selected half is flipped/aligned; the other remains unchanged.\n" +
				"Existing hyperstack: the selected odd/even channel side is aligned to the other.\n" +
				"Sources are written in the selected order; unavailable and '-' sources are skipped.");
		gd.showDialog();
		if (gd.wasCanceled()) return false;

		parameter.inputDir = gd.getNextString();
		parameter.keywords = gd.getNextString();
		parameter.recursive = gd.getNextBoolean();
		inputLayout = gd.getNextChoice();
		flipHalf = gd.getNextChoice();
		parameter.alignmFile = gd.getNextString();
		combineAcquisitionChannels = gd.getNextBoolean();
		for (int i = 0; i < channelOrder.length; i++) channelOrder[i] = gd.getNextChoice();
		interpolate = Parameter.isBilinear(gd.getNextChoice());
		parameter.saveDir = gd.getNextString();
		parameter.saveToSame = gd.getNextBoolean();
		parameter.saveSeparate = gd.getNextBoolean();
		parameter.fileExistStr = gd.getNextChoice();
		storeChannelOrder();
		return true;
	}

	private Map<String, List<File>> groupFiles(List<File> files) {
		Map<String, List<File>> groups = new LinkedHashMap<String, List<File>>();
		for (File file : files) {
			String key = combineAcquisitionChannels ? BatchProcessingUtils.channelGroupKey(file) : file.getAbsolutePath();
			List<File> group = groups.get(key);
			if (group == null) {
				group = new ArrayList<File>();
				groups.put(key, group);
			}
			group.add(file);
		}
		return groups;
	}

	PreparedChannels prepare(ImagePlus input, String fileName, double[][] matrix) {
		boolean splitMirrored = MIRRORED.equals(inputLayout) ||
				(AUTO.equals(inputLayout) && input.getNChannels() == 1);
		if (splitMirrored) return splitMirrored(input, fileName, matrix);
		return alignExistingChannels(input, fileName, matrix);
	}

	private PreparedChannels splitMirrored(ImagePlus input, String fileName, double[][] matrix) {
		if (input.getWidth() < 2) throw new IllegalArgumentException("Image is too narrow to split.");
		PreparedChannels result = new PreparedChannels();
		int halfWidth = (input.getWidth() + 1) / 2;
		boolean flipLeft = FLIP_LEFT.equals(flipHalf);
		double[][] leftAlignment = flipLeft ? Transform.mirrorAlignmentMatrix2D(matrix, halfWidth) : null;
		ImageStack leftStack = new ImageStack(halfWidth, input.getHeight());
		ImageStack rightStack = new ImageStack(halfWidth, input.getHeight());
		for (int index = 1; index <= input.getStackSize(); index++) {
			ImageProcessor source = input.getStack().getProcessor(index);
			ImageProcessor leftSource = source.duplicate();
			leftSource.setRoi(0, 0, halfWidth, input.getHeight());
			ImageProcessor left = leftSource.crop();
			ImageProcessor rightSource = source.duplicate();
			rightSource.setRoi(input.getWidth() - halfWidth, 0, halfWidth, input.getHeight());
			ImageProcessor right = rightSource.crop();
			if (flipLeft) {
				left.flipHorizontal();
				left = SIFT.alignWithRigid2DMatrix(left, leftAlignment, interpolate);
			} else {
				right.flipHorizontal();
				right = SIFT.alignWithRigid2DMatrix(right, matrix, interpolate);
			}
			leftStack.addSlice(input.getStack().getSliceLabel(index), left);
			rightStack.addSlice(input.getStack().getSliceLabel(index), right);
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
		result.slices = zt[0];
		result.frames = zt[1];
		return result;
	}

	private PreparedChannels alignExistingChannels(ImagePlus input, String fileName, double[][] matrix) {
		if (input.getNChannels() < 2)
			throw new IllegalArgumentException("Existing-channel mode needs an ImageJ hyperstack with at least two channels.");
		PreparedChannels result = new PreparedChannels();
		ImagePlus[] channels = ChannelSplitter.split(input);
		boolean alignLeft = FLIP_LEFT.equals(flipHalf);
		double[][] appliedMatrix = alignLeft ? Transform.inverseAlignmentMatrix2D(matrix) : matrix;
		for (int i = 0; i < channels.length; i++) {
			channels[i].setTitle(BatchProcessingUtils.baseName(new File(fileName)) + "-C" + (i + 1));
			if ((alignLeft && i % 2 == 0) || (!alignLeft && i % 2 == 1))
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
			String prefix = String.format(Locale.US, "_Channel%04d", acquisitionChannel);
			sources.put(prefix + "-left", item.images.get(0));
			sources.put(prefix + "-right", item.images.get(1));
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

	private void loadChannelOrder() {
		String storedFlip = Prefs.get("opm.batchChannel.flipHalf", flipHalf);
		if (FLIP_RIGHT.equals(storedFlip) || FLIP_LEFT.equals(storedFlip)) flipHalf = storedFlip;
		for (int i = 0; i < channelOrder.length; i++) {
			String stored = Prefs.get("opm.batchChannel.output" + (i + 1), channelOrder[i]);
			if (isChannelSourceOption(stored)) channelOrder[i] = stored;
		}
	}

	private void storeChannelOrder() {
		Prefs.set("opm.batchChannel.flipHalf", flipHalf);
		for (int i = 0; i < channelOrder.length; i++)
			Prefs.set("opm.batchChannel.output" + (i + 1), channelOrder[i]);
	}

	private boolean isChannelSourceOption(String value) {
		for (String option : CHANNEL_SOURCE_OPTIONS) {
			if (option.equals(value)) return true;
		}
		return false;
	}

	private int selectedChannelCount() {
		int count = 0;
		for (String selected : channelOrder) {
			if (!SKIP_CHANNEL.equals(selected)) count++;
		}
		return count;
	}

	private String ordinal(int number) {
		switch (number) {
		case 1: return "1st";
		case 2: return "2nd";
		case 3: return "3rd";
		default: return number + "th";
		}
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

	private boolean is2dMatrix(double[][] matrix) {
		return matrix != null && matrix.length >= 2 && matrix[0] != null && matrix[1] != null &&
				matrix[0].length >= 3 && matrix[1].length >= 3;
	}
}
