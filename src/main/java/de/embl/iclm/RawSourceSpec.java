package de.embl.iclm;

/**
 * One displayed source of the Online Deskew View: which raw file (acquisition channel), which
 * part of its width, and how that part is placed.
 *
 * <p>The placement - mirror or not, and the 2 x 3 matrix applied after it - comes only from
 * {@link AlignmentMatrixSet#placement}, the single rule the batch, live and viewer paths share
 * (joint contract C1.4). Nothing here interprets a matrix file a second way.
 *
 * <p>Camera halves follow {@code Partition.separateImageLeftRight} exactly (C1.7): a half is
 * {@code floor(W / 2)} wide, the left one starts at 0 and the right one at {@code W - w}. For an
 * odd camera width that drops the centre column, deliberately, so a view and a processed result
 * show the same pixels.
 */
final class RawSourceSpec {

	enum Part { LEFT, RIGHT, WHOLE }

	/** Canonical source key, for example {@code _Channel0001-right}. */
	final String key;
	/** The {@code _ChannelNNNN} number of the raw file this part is read from. */
	final int acquisitionChannel;
	final Part part;
	/** Mirror the part horizontally, about its last pixel centre, before {@link #matrix}. */
	final boolean mirror;
	/** Source-to-reference 2 x 3 matrix on deskewed X and Y, or null for none. */
	final double[][] matrix;
	/** Display colour, 0xRRGGBB. */
	final int rgb;

	private RawSourceSpec(String key, int acquisitionChannel, Part part, boolean mirror, double[][] matrix, int rgb) {
		this.key = key;
		this.acquisitionChannel = acquisitionChannel;
		this.part = part;
		this.mirror = mirror;
		this.matrix = matrix == null ? null : Transform.copy(matrix);
		this.rgb = rgb;
	}

	/**
	 * A camera half, placed as the toolset places it for the chosen flip side.
	 *
	 * @param alignments			: the loaded matrices, or null for none
	 * @param acquisitionChannel	: the file's {@code _ChannelNNNN} number, 1 based
	 * @param left					: the left half rather than the right one
	 * @param flipLeft				: mirror the left halves instead of the right ones
	 * @param fullWidth				: the raw camera width W
	 * @param rgb					: display colour
	 */
	static RawSourceSpec half(AlignmentMatrixSet alignments, int acquisitionChannel, boolean left,
			boolean flipLeft, long fullWidth, int rgb) {
		String key = ChannelOperationSettings.sourceKey(acquisitionChannel, left);
		AlignmentMatrixSet.Placement placement = AlignmentMatrixSet.placement(alignments, key, flipLeft,
				(int) halfWidth(fullWidth));
		return new RawSourceSpec(key, acquisitionChannel, left ? Part.LEFT : Part.RIGHT,
				placement.mirror, placement.matrix, rgb);
	}

	/** The whole camera width of one file: never mirrored; its own matrix if a tagged set has one. */
	static RawSourceSpec whole(AlignmentMatrixSet alignments, int acquisitionChannel, int rgb) {
		String key = ChannelOperationSettings.wholeSourceKey(acquisitionChannel);
		AlignmentMatrixSet.Placement placement = AlignmentMatrixSet.placement(alignments, key, false, 1);
		return new RawSourceSpec(key, acquisitionChannel, Part.WHOLE, false, placement.matrix, rgb);
	}

	/** Width of one camera half: {@code floor(W / 2)}, as {@code Partition.separateImageLeftRight}. */
	static long halfWidth(long fullWidth) {
		return fullWidth / 2;
	}

	/** First raw column of this part. */
	long cropMin(long fullWidth) {
		return part == Part.RIGHT ? fullWidth - halfWidth(fullWidth) : 0;
	}

	/** Width of this part in raw columns. */
	long cropWidth(long fullWidth) {
		return part == Part.WHOLE ? fullWidth : halfWidth(fullWidth);
	}

	@Override
	public String toString() {
		return key + (mirror ? " mirrored" : "") + (matrix != null ? " aligned" : "");
	}
}
