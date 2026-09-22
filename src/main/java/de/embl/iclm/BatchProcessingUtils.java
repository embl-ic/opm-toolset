package de.embl.iclm;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ImageProcessor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;

final class BatchProcessingUtils {
	private static final String[] TIFF_EXTENSIONS = { "tif", "tiff" };
	private static final Pattern CHANNEL_PATTERN = Pattern.compile("(?i)_Channel(\\d+)");
	private static final Pattern TIME_PATTERN = Pattern.compile("(?i)_Time\\d+");

	private BatchProcessingUtils() { }

	static List<File> listTiffs(File root, String keywordText, boolean recursive) {
		return listTiffs(root, keywordText, null, recursive);
	}

	/**
	 * List the TIFFs under a folder that the include filter accepts and the exclude filter does
	 * not reject.
	 *
	 * <p>The exclude list exists because an acquisition folder legitimately contains TIFFs the
	 * run must not touch - a previous result written beside the data, a snapshot, a reference
	 * image. Naming them positively in the include list means listing every kind of file that
	 * should be processed instead, which is the harder half of the same question.
	 */
	static List<File> listTiffs(File root, String keywordText, String excludeText, boolean recursive) {
		List<File> result = new ArrayList<File>();
		if (root == null || !root.isDirectory()) return result;
		String[] keywords = splitKeywords(keywordText);
		String[] excluded = splitKeywords(excludeText);
		for (File file : FileUtils.listFiles(root, TIFF_EXTENSIONS, recursive)) {
			if (matchesKeywords(file.getName(), keywords) && !matchesAny(file.getName(), excluded))
				result.add(file);
		}
		Collections.sort(result, new Comparator<File>() {
			@Override
			public int compare(File a, File b) {
				return naturalCompare(a.getAbsolutePath(), b.getAbsolutePath());
			}
		});
		return result;
	}

	static List<File> excludeTree(List<File> files, File excludedRoot) {
		if (excludedRoot == null) return files;
		List<File> result = new ArrayList<File>();
		for (File file : files) {
			if (!isInside(file, excludedRoot)) result.add(file);
		}
		return result;
	}

	static boolean isInside(File file, File directory) {
		try {
			String filePath = file.getCanonicalPath();
			String directoryPath = directory.getCanonicalPath();
			if (!directoryPath.endsWith(File.separator)) directoryPath += File.separator;
			return filePath.startsWith(directoryPath);
		} catch (IOException e) {
			return false;
		}
	}

	/**			Where a result goes when the input folder tree is reproduced under a result folder
	 * <p>		Where "save result to the same (data) folder" would put it - the {@code result}
	 * 			folder beside the input file - moved under {@code saveRoot}, with the input folder's
	 * 			whole path kept and only its root dropped:
	 * 			{@code E:\OPM\3_timelapse_0\x.tiff} under {@code I:\Group\New folder} is
	 * 			{@code I:\Group\New folder\OPM\3_timelapse_0\result}. The root dropped is a drive
	 * 			letter, a UNC {@code \\server\share\} or {@code /}.
	 * <p>		It depends on the file's own path and nothing else - not on which folder was
	 * 			announced first, how the file was found or which session found it - so every file
	 * 			of one acquisition folder, every session over it and a resumed run all agree. The
	 * 			path is not resolved on disk: a mapped drive stays the letter it was given, and a
	 * 			long tree is the user's choice.
	 *
	 * @param inputFile		: an input file; only its folder is used
	 * @param saveRoot		: the configured result folder
	 * <p>
	 * @return				: the result folder for that input folder
	 */
	static File mirroredResultFolder(File inputFile, File saveRoot) {
		Path folder = inputFile.getAbsoluteFile().toPath().normalize().getParent();
		if (folder == null) return new File(saveRoot, "result");
		Path root = folder.getRoot();
		String relative = (root == null ? folder : root.relativize(folder)).toString();
		File mirrored = relative.isEmpty() ? saveRoot : new File(saveRoot, relative);
		return new File(mirrored, "result");
	}

	/**			Where a batch result goes when the input folder tree is reproduced under a result folder
	 * <p>		Batch has a well defined input: the folder the user chose. So the tree reproduced is the
	 * 			one below it - {@code <input>\A\B\x.tif} goes to {@code <saveRoot>\A\B} - rather than
	 * 			Live's whole path less the drive ({@link #mirroredResultFolder}), where the input is a
	 * 			stream of announced paths with no chosen root. A file in the input folder itself, or
	 * 			outside it, goes to {@code saveRoot}. Paths are compared as written, not resolved.
	 *
	 * @param inputFile		: an input file; only its folder is used
	 * @param inputRoot		: the input folder the batch was pointed at
	 * @param saveRoot		: the batch's result folder
	 */
	static File mirroredUnder(File inputFile, File inputRoot, File saveRoot) {
		Path folder = inputFile.getAbsoluteFile().toPath().normalize().getParent();
		Path root = inputRoot.getAbsoluteFile().toPath().normalize();
		if (folder == null || !folder.startsWith(root)) return saveRoot;
		String relative = root.relativize(folder).toString();
		return relative.isEmpty() ? saveRoot : new File(saveRoot, relative);
	}

	/** Files grouped by the folder they are in, folders and files in the order given. */
	static Map<File, List<File>> byFolder(List<File> files) {
		Map<File, List<File>> groups = new LinkedHashMap<File, List<File>>();
		for (File file : files) {
			File folder = file.getAbsoluteFile().getParentFile();
			List<File> group = groups.get(folder);
			if (group == null) { group = new ArrayList<File>(); groups.put(folder, group); }
			group.add(file);
		}
		return groups;
	}

	static File saveRootFor(File inputFile, File inputRoot, String configuredSaveDir,
			boolean saveToSame, boolean recursive) {
		if (saveToSame || configuredSaveDir == null || configuredSaveDir.trim().isEmpty())
			return new File(inputFile.getParentFile(), "result");
		File configured = new File(configuredSaveDir);
		if (!recursive || inputRoot == null) return configured;
		try {
			String rootPath = inputRoot.getCanonicalPath();
			String parentPath = inputFile.getParentFile().getCanonicalPath();
			if (parentPath.equals(rootPath)) return configured;
			if (parentPath.startsWith(rootPath + File.separator))
				return new File(configured, parentPath.substring(rootPath.length() + 1));
		} catch (IOException ignored) { }
		return configured;
	}

	static String baseName(File file) {
		String name = file.getName();
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	static int acquisitionChannel(File file) {
		Matcher matcher = CHANNEL_PATTERN.matcher(baseName(file));
		if (!matcher.find()) return -1;
		try {
			return Integer.parseInt(matcher.group(1));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	static String acquisitionChannelToken(File file) {
		Matcher matcher = CHANNEL_PATTERN.matcher(baseName(file));
		return matcher.find() ? matcher.group() : "Channel0000";
	}

	static String channelGroupKey(File file) {
		String name = CHANNEL_PATTERN.matcher(baseName(file)).replaceFirst("_Channel####");
		return file.getParentFile().getAbsolutePath() + File.separator + name;
	}

	static String channelGroupOutputName(List<File> files) {
		String name = baseName(files.get(0));
		Matcher matcher = CHANNEL_PATTERN.matcher(name);
		if (!matcher.find()) return name + "-channels-aligned";
		int first = Integer.MAX_VALUE;
		int last = Integer.MIN_VALUE;
		for (File file : files) {
			int channel = acquisitionChannel(file);
			if (channel >= 0) {
				first = Math.min(first, channel);
				last = Math.max(last, channel);
			}
		}
		String replacement = first == Integer.MAX_VALUE ? "_Channels" :
				String.format(Locale.US, "_Channels%04d-%04d", first, last);
		return matcher.replaceFirst(replacement) + "-aligned";
	}

	static String movieBaseName(File file) {
		String name = TIME_PATTERN.matcher(baseName(file)).replaceFirst("");
		return name.replaceAll("(?i)_Frames_\\d+_\\d+", "");
	}

	static boolean saveTiff(ImagePlus image, File output, boolean overwrite) {
		if (image == null || output == null) return false;
		if (output.exists() && !overwrite) return false;
		File parent = output.getParentFile();
		if (parent != null) parent.mkdirs();
		return VolumeIO.saveTiff(image, output);
	}

	static void close(ImagePlus image) {
		if (image == null) return;
		image.changes = false;
		image.close();
		image.flush();
	}

	private static String[] splitKeywords(String keywordText) {
		if (keywordText == null || keywordText.trim().isEmpty()) return new String[0];
		String[] raw = keywordText.split(",");
		List<String> cleaned = new ArrayList<String>();
		for (String keyword : raw) {
			keyword = keyword.trim();
			if (!keyword.isEmpty()) cleaned.add(keyword.toLowerCase(Locale.ROOT));
		}
		return cleaned.toArray(new String[cleaned.size()]);
	}

	private static boolean matchesKeywords(String name, String[] keywords) {
		if (keywords.length == 0) return true;
		return matchesAny(name, keywords);
	}

	/** Whether any of the (already lower-cased) fragments occurs in the name; empty matches none. */
	static boolean matchesAny(String name, String[] fragments) {
		if (fragments == null || fragments.length == 0) return false;
		String lowerName = name.toLowerCase(Locale.ROOT);
		for (String fragment : fragments) {
			if (lowerName.contains(fragment)) return true;
		}
		return false;
	}

	/** Whether a file name passes a comma-separated include list and a comma-separated exclude list. */
	static boolean accepts(String name, String includeText, String excludeText) {
		return matchesKeywords(name, splitKeywords(includeText))
				&& !matchesAny(name, splitKeywords(excludeText));
	}

	private static int naturalCompare(String a, String b) {
		int ia = 0;
		int ib = 0;
		while (ia < a.length() && ib < b.length()) {
			char ca = a.charAt(ia);
			char cb = b.charAt(ib);
			if (Character.isDigit(ca) && Character.isDigit(cb)) {
				int enda = ia;
				int endb = ib;
				while (enda < a.length() && Character.isDigit(a.charAt(enda))) enda++;
				while (endb < b.length() && Character.isDigit(b.charAt(endb))) endb++;
				String na = a.substring(ia, enda).replaceFirst("^0+(?!$)", "");
				String nb = b.substring(ib, endb).replaceFirst("^0+(?!$)", "");
				if (na.length() != nb.length()) return na.length() < nb.length() ? -1 : 1;
				int numberCompare = na.compareTo(nb);
				if (numberCompare != 0) return numberCompare;
				ia = enda;
				ib = endb;
				continue;
			}
			int compare = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
			if (compare != 0) return compare;
			ia++;
			ib++;
		}
		return Integer.compare(a.length(), b.length());
	}

	static final class TimeLapseBuilder {
		private ImageStack stack;
		private Calibration calibration;
		private int width;
		private int height;
		private int channels;
		private int slices;
		private int frames;

		void append(ImagePlus image, String label) {
			if (image == null) return;
			int imageChannels = Math.max(1, image.getNChannels());
			int imageSlices = Math.max(1, image.getNSlices());
			int imageFrames = Math.max(1, image.getNFrames());
			if (stack == null) {
				width = image.getWidth();
				height = image.getHeight();
				channels = imageChannels;
				slices = imageSlices;
				stack = new ImageStack(width, height);
				calibration = image.getCalibration() == null ? null : image.getCalibration().copy();
			} else if (width != image.getWidth() || height != image.getHeight() ||
					channels != imageChannels || slices != imageSlices) {
				throw new IllegalArgumentException("Projection dimensions changed within a time-lapse group.");
			}
			for (int t = 1; t <= imageFrames; t++) {
				for (int z = 1; z <= imageSlices; z++) {
					for (int c = 1; c <= imageChannels; c++) {
						int index = image.getStackIndex(c, z, t);
						ImageProcessor processor = image.getStack().getProcessor(index).duplicate();
						stack.addSlice(label, processor);
					}
				}
			}
			frames += imageFrames;
		}

		ImagePlus build(String title) {
			if (stack == null) return null;
			ImagePlus result = new ImagePlus(title, stack);
			result.setDimensions(channels, slices, frames);
			result.setOpenAsHyperStack(channels > 1 || slices > 1 || frames > 1);
			if (calibration != null) result.setCalibration(calibration);
			return result;
		}
	}
}
