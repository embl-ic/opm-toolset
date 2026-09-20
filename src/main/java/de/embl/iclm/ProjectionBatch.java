package de.embl.iclm;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;
import net.haesleinhuepf.clij2.CLIJ2;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes a set of 16-bit max/mean orthogonal projections with bounded memory.
 * A GPU source volume is uploaded once for the entire set instead of once per MIP.
 */
final class ProjectionBatch {

	static final class Request {
		final char axis;
		final String type;

		Request(String axis, String type) {
			if (axis == null || axis.length() != 1)
				throw new IllegalArgumentException("Projection axis must be X, Y or Z.");
			this.axis = Character.toUpperCase(axis.charAt(0));
			this.type = type == null ? "" : type.toLowerCase();
		}

		boolean mean() { return "avg".equals(type) || "mean".equals(type); }
	}

	private ProjectionBatch() { }

	/**
	 * The 2-D size a CLIJ2 projection along {@code axis} writes into, in CLIJ order.
	 * <p>
	 * Along X it is {depth, height}, not {height, depth}: the kernel writes pixel (z, y) and
	 * iterates over the destination's own size. A destination created the other way round made
	 * the fast deskew path project only the first {@code depth} rows, reading z past the last
	 * slice clamped to it - a dim, partly blank maxX that was still a valid TIFF. Every path that
	 * sizes a CLIJ2 projection takes it from here.
	 *
	 * @param dimensions		: the volume, {width, height, depth}
	 */
	static long[] outputDimensions(long[] dimensions, char axis) {
		if (axis == 'X') return new long[] { dimensions[2], dimensions[1] };
		if (axis == 'Y') return new long[] { dimensions[0], dimensions[2] };
		return new long[] { dimensions[0], dimensions[1] };
	}

	static boolean supports(ImagePlus image, List<Request> requests) {
		if (image == null || image.getBitDepth() != 16 || image.getNFrames() != 1
				|| requests == null || requests.isEmpty()) return false;
		for (Request request : requests)
			if ((request.axis != 'X' && request.axis != 'Y' && request.axis != 'Z')
					|| (!"max".equals(request.type) && !request.mean())) return false;
		return true;
	}

	/** Return one hyperstack projection per request, in request order. */
	static List<ImagePlus> compute(ImagePlus image, List<Request> requests, boolean tryGpu) {
		if (!supports(image, requests))
			throw new IllegalArgumentException("ProjectionBatch supports 16-bit max/mean XYZ projections only.");
		int channels = Math.max(1, image.getNChannels());
		List<List<ImagePlus>> byRequest = new ArrayList<List<ImagePlus>>();
		for (int request = 0; request < requests.size(); request++)
			byRequest.add(new ArrayList<ImagePlus>());

		try {
			for (int channel = 1; channel <= channels; channel++) {
				ImagePlus volume = Utils.getImageChunk(image, channel, 1);
				List<ImagePlus> projections = tryGpu ? gpu(volume, requests) : null;
				if (projections == null) projections = cpu(volume, requests);
				for (int request = 0; request < projections.size(); request++)
					byRequest.get(request).add(projections.get(request));
			}

			List<ImagePlus> result = new ArrayList<ImagePlus>();
			for (List<ImagePlus> channelProjections : byRequest) {
				ImagePlus first = channelProjections.get(0);
				ImageStack stack = new ImageStack(first.getWidth(), first.getHeight());
				for (ImagePlus projection : channelProjections)
					stack.addSlice(projection.getProcessor());
				ImagePlus combined = new ImagePlus("projection", stack);
				combined.setDimensions(channels, 1, 1);
				if (channels > 1) combined.setOpenAsHyperStack(true);
				result.add(combined);
			}
			return result;
		} catch (Throwable failure) {
			for (List<ImagePlus> projections : byRequest) close(projections);
			if (failure instanceof RuntimeException) throw (RuntimeException) failure;
			if (failure instanceof Error) throw (Error) failure;
			throw new RuntimeException(failure);
		}
	}

	/** One upload, six small projection downloads, then release all GPU buffers. */
	private static List<ImagePlus> gpu(ImagePlus volume, List<Request> requests) {
		CLIJ2 clij2 = null;
		List<ImagePlus> result = new ArrayList<ImagePlus>();
		try {
			if (!GPU.checkImageSize(volume)) return null;
			clij2 = CLIJ2.getInstance();
			ClearCLBuffer source = clij2.push(volume);
			long[] dimensions = source.getDimensions();
			for (Request request : requests) {
				long[] outputDimensions = outputDimensions(dimensions, request.axis);
				ClearCLBuffer destination = clij2.create(outputDimensions, source.getNativeType());
				try {
					if (request.axis == 'X') {
						if (request.mean()) clij2.meanXProjection(source, destination);
						else clij2.maximumXProjection(source, destination);
					} else if (request.axis == 'Y') {
						if (request.mean()) clij2.meanYProjection(source, destination);
						else clij2.maximumYProjection(source, destination);
					} else {
						if (request.mean()) clij2.meanZProjection(source, destination);
						else clij2.maximumZProjection(source, destination);
					}
					result.add(clij2.pull(destination));
				} finally {
					clij2.release(destination);
				}
			}
			return result;
		} catch (Throwable failure) {
			close(result);
			IJ.log("OPM projection batch: GPU projection unavailable; using bounded-memory CPU: "
					+ failure.getMessage());
			return null;
		} finally {
			// This also releases the one source buffer if conversion or a kernel failed midway.
			if (clij2 != null) try { clij2.clear(); } catch (Throwable ignored) { }
		}
	}

	/** Single voxel traversal; only small 2-D projection and accumulator arrays are allocated. */
	private static List<ImagePlus> cpu(ImagePlus volume, List<Request> requests) {
		int width = volume.getWidth(), height = volume.getHeight(), depth = volume.getStackSize();
		boolean maxX = false, meanX = false, maxY = false, meanY = false, maxZ = false, meanZ = false;
		for (Request request : requests) {
			boolean mean = request.mean();
			if (request.axis == 'X') { if (mean) meanX = true; else maxX = true; }
			if (request.axis == 'Y') { if (mean) meanY = true; else maxY = true; }
			if (request.axis == 'Z') { if (mean) meanZ = true; else maxZ = true; }
		}
		short[] xMax = maxX ? new short[depth * height] : null;
		long[] xSum = meanX ? new long[depth * height] : null;
		short[] yMax = maxY ? new short[width * depth] : null;
		long[] ySum = meanY ? new long[width * depth] : null;
		short[] zMax = maxZ ? new short[width * height] : null;
		long[] zSum = meanZ ? new long[width * height] : null;

		for (int z = 0; z < depth; z++) {
			ImageProcessor processor = volume.getStack().getProcessor(z + 1);
			short[] pixels = (short[]) processor.getPixels();
			for (int y = 0; y < height; y++) {
				int row = y * width;
				int xMaximum = 0;
				long xTotal = 0;
				for (int x = 0; x < width; x++) {
					int value = pixels[row + x] & 0xffff;
					if (maxX && value > xMaximum) xMaximum = value;
					if (meanX) xTotal += value;
					int yIndex = z * width + x;
					if (maxY && value > (yMax[yIndex] & 0xffff)) yMax[yIndex] = (short) value;
					if (meanY) ySum[yIndex] += value;
					int zIndex = row + x;
					if (maxZ && value > (zMax[zIndex] & 0xffff)) zMax[zIndex] = (short) value;
					if (meanZ) zSum[zIndex] += value;
				}
				int xIndex = y * depth + z;
				if (maxX) xMax[xIndex] = (short) xMaximum;
				if (meanX) xSum[xIndex] = xTotal;
			}
		}

		List<ImagePlus> result = new ArrayList<ImagePlus>();
		for (Request request : requests) {
			int outputWidth, outputHeight, divisor;
			short[] pixels;
			if (request.axis == 'X') {
				outputWidth = depth; outputHeight = height; divisor = width;
				pixels = request.mean() ? mean(xSum, divisor) : xMax;
			} else if (request.axis == 'Y') {
				outputWidth = width; outputHeight = depth; divisor = height;
				pixels = request.mean() ? mean(ySum, divisor) : yMax;
			} else {
				outputWidth = width; outputHeight = height; divisor = depth;
				pixels = request.mean() ? mean(zSum, divisor) : zMax;
			}
			result.add(new ImagePlus("projection",
					new ShortProcessor(outputWidth, outputHeight, pixels, null)));
		}
		return result;
	}

	private static short[] mean(long[] sums, int divisor) {
		short[] pixels = new short[sums.length];
		for (int index = 0; index < sums.length; index++)
			pixels[index] = (short) Math.min(65535L, (sums[index] + divisor / 2L) / divisor);
		return pixels;
	}

	private static void close(List<ImagePlus> images) {
		for (ImagePlus image : images) BatchProcessingUtils.close(image);
	}
}
