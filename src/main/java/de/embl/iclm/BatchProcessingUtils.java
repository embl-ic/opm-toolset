package de.embl.iclm;

import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.process.ImageProcessor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;

final class BatchProcessingUtils {
	private static final String[] TIFF_EXTENSIONS = { "tif", "tiff" };
	private static final Pattern CHANNEL_PATTERN = Pattern.compile("(?i)_Channel(\\d+)");
	private static final Pattern TIME_PATTERN = Pattern.compile("(?i)_Time\\d+");

	private BatchProcessingUtils() { }

	static List<File> listTiffs(File root, String keywordText, boolean recursive) {
		List<File> result = new ArrayList<File>();
		if (root == null || !root.isDirectory()) return result;
		String[] keywords = splitKeywords(keywordText);
		for (File file : FileUtils.listFiles(root, TIFF_EXTENSIONS, recursive)) {
			if (matchesKeywords(file.getName(), keywords)) result.add(file);
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
		FileSaver saver = new FileSaver(image);
		if (image.getStackSize() > 1) return saver.saveAsTiffStack(output.getAbsolutePath());
		return saver.saveAsTiff(output.getAbsolutePath());
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
		String lowerName = name.toLowerCase(Locale.ROOT);
		for (String keyword : keywords) {
			if (lowerName.contains(keyword)) return true;
		}
		return false;
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
