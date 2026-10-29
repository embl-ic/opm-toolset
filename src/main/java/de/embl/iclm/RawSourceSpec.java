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
	/** Opening-width placement for diagnostics/legacy callers; transforms use {@link #placement(long)}. */
	final double[][] matrix;
	/** Display colour, 0xRRGGBB. */
	final int rgb;
	/** Frozen placement inputs. The current file's width is resolved when its transform is captured. */
	private final AlignmentMatrixSet alignments;
	private final boolean flipLeft;

	private RawSourceSpec(String key, int acquisitionChannel, Part part, AlignmentMatrixSet alignments,
			boolean flipLeft, long openingWidth, int rgb) {
		this.key = key;
		this.acquisitionChannel = acquisitionChannel;
		this.part = part;
		this.alignments = alignments == null ? null : alignments.isLegacy()
				? AlignmentMatrixSet.legacy(alignments.legacyMatrix())
				: AlignmentMatrixSet.tagged(alignments.reference(), alignments.matrices());
		this.flipLeft = flipLeft;
		AlignmentMatrixSet.Placement placement = placement(openingWidth);
		this.mirror = placement.mirror;
		this.matrix = placement.matrix;
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
		return new RawSourceSpec(key, acquisitionChannel, left ? Part.LEFT : Part.RIGHT, alignments, flipLeft, fullWidth, rgb);
	}

	/** The whole camera width of one file: never mirrored; its own matrix if a tagged set has one. */
	static RawSourceSpec whole(AlignmentMatrixSet alignments, int acquisitionChannel, int rgb) {
		String key = ChannelOperationSettings.wholeSourceKey(acquisitionChannel);
		return new RawSourceSpec(key, acquisitionChannel, Part.WHOLE, alignments, false, 2, rgb);
	}

	/** Placement for this captured file, including a late arrival or replacement of another width. */
	AlignmentMatrixSet.Placement placement(long fullWidth) {
		return AlignmentMatrixSet.placement(alignments, key, flipLeft, (int) halfWidth(fullWidth));
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
