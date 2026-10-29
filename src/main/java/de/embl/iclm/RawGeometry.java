package de.embl.iclm;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import net.imglib2.realtransform.AffineTransform3D;

/**
 * The deskew inputs of a raw OPM acquisition, where they came from, and the source-to-world
 * transform they define for the Online Deskew View.
 *
 * <p>Immutable. A changed parameter is a new instance with a new {@link #revision}, so a render
 * request built against one geometry can never be drawn with another (joint contract C1.10).
 *
 * <p>The transform of one displayed source maps its raw voxel indices to micrometres. With
 * column vectors, the rightmost factor applied first (contract C1.1):
 * <pre>    T = S * M * F * D_eff * P_l</pre>
 * <ul>
 * <li>{@code D} is {@link Transform#deskew} built with the height of the file being shown and
 *     then {@link Transform#autoCenter}ed. It is never taken through
 *     {@code Parameter.updateDeskewMatrix}, whose absolute Z translation moves every
 *     negative-angle volume outside its own grid (C1.2);</li>
 * <li>{@code D_eff = H^-1 D H}, H a translation by half a voxel on every axis. The toolset's
 *     stored deskew is CLIJ2's buffer kernel, which evaluates the matrix at voxel index + 1/2;
 *     with this conjugation a nearest-neighbour view reproduces that output bit for bit (C1.3);</li>
 * <li>{@code F} mirrors X about the last pixel centre, {@code w - 1}, when the placement mirrors
 *     the part; {@code M} is the placement's 2 x 3 source-to-reference matrix acting on deskewed
 *     X and Y with Z unchanged (C1.4). Both come from {@link RawSourceSpec};</li>
 * <li>{@code S} scales the isotropic deskewed grid to micrometres by the pixel size - the stage
 *     step is already inside {@code D} and is never applied again (C1.5);</li>
 * <li>{@code P_l} maps the voxel centres of a coarser level to level 0 (C1.6).</li>
 * </ul>
 */
final class RawGeometry {

	private static final AtomicLong REVISIONS = new AtomicLong();

	/** Scan step between raw planes, in micrometres. */
	final double dzUm;
	/** Camera pixel size at the sample, in micrometres; also the deskewed voxel pitch. */
	final double dxyUm;
	/** OPM angle in degrees, as {@link Transform#deskew} takes it. */
	final double angleDeg;
	/** Where these values came from, shown to the user: a file path, or "manual". */
	final String origin;
	/** Unique per instance, so dependent requests can tell geometries apart. */
	final long revision;

	RawGeometry(double dzUm, double dxyUm, double angleDeg, String origin) {
		if (!(dzUm > 0) || Double.isInfinite(dzUm))
			throw new IllegalArgumentException("The scan step must be a positive number of micrometres, not " + dzUm);
		if (!(dxyUm > 0) || Double.isInfinite(dxyUm))
			throw new IllegalArgumentException("The pixel size must be a positive number of micrometres, not " + dxyUm);
		/* At 0 degrees the deskew has no Z extent and the matrix is singular; at 90 or beyond it
		 * describes no OPM. Both would only produce an empty or degenerate view. */
		if (Double.isNaN(angleDeg) || Math.abs(angleDeg) < 0.5 || Math.abs(angleDeg) >= 89.5)
			throw new IllegalArgumentException("The OPM angle must lie between 0.5 and 89.5 degrees in magnitude, not " + angleDeg);
		this.dzUm = dzUm;
		this.dxyUm = dxyUm;
		this.angleDeg = angleDeg;
		this.origin = origin == null ? "unknown" : origin;
		this.revision = REVISIONS.incrementAndGet();
	}

	/**
	 * Read the geometry from an acquisition's {@code ExperimentalParameters.txt}.
	 *
	 * @param file	: the parameter file
	 * @return		: the geometry, its origin naming the file
	 * @throws IllegalArgumentException when the file cannot be read or lacks the deskew values
	 */
	static RawGeometry fromExperimentalParameters(File file) {
		double[] values = file == null ? null : IO.loadExperimentalParametersFromFile(file.getAbsolutePath());
		if (values == null)
			throw new IllegalArgumentException("No deskew geometry could be read from " + file);
		// IO returns the pixel size and the step in nanometres
		return new RawGeometry(values[1] / 1e3, values[0] / 1e3, values[2], file.getAbsolutePath());
	}

	/**
	 * The toolset's forward deskew for one raw file, in its own coordinates: {@link Transform#deskew}
	 * for the file's height, then {@link Transform#autoCenter} over the file's extent.
	 *
	 * @param width		: raw width in pixels
	 * @param height	: raw height (camera rows) of this file
	 * @param depth		: number of raw planes
	 * @return			: a fresh 4 x 4 matrix
	 */
	double[][] deskewMatrix(long width, long height, long depth) {
		double[][] matrix = Transform.deskew(dzUm, dxyUm, angleDeg, height);
		return Transform.autoCenter(new long[] { width, height, depth }, matrix);
	}

	/**
	 * The deskewed grid of one raw file, sized by the rule every deskew path in the toolset uses
	 * ({@link Transform#getTransformedDim}), so a view and a stored result agree on extent.
	 */
	long[] deskewedGrid(long width, long height, long depth) {
		return Transform.getTransformedDim(new long[] { width, height, depth },
				Transform.deskew(dzUm, dxyUm, angleDeg, height), true);
	}

	/**
	 * Source-to-world transform of one displayed part of a raw file.
	 *
	 * @param fileDims		: the raw file's width, height and depth at level 0
	 * @param spec			: which part is shown and how it is placed
	 * @param levelFactors	: downsampling factors of the level, {1, 1, 1} for level 0
	 * @return				: {@code S * M * F * D_eff * P_l}, a new object
	 */
	AffineTransform3D sourceToWorld(long[] fileDims, RawSourceSpec spec, double[] levelFactors) {
		AffineTransform3D t = levelToBase(levelFactors);
		t.preConcatenate(halfVoxelConjugate(deskewMatrix(fileDims[0], fileDims[1], fileDims[2])));
		AlignmentMatrixSet.Placement placement = spec.placement(fileDims[0]);
		if (placement.mirror) t.preConcatenate(mirrorX(spec.cropWidth(fileDims[0])));
		if (placement.matrix != null) t.preConcatenate(xyPlacement(placement.matrix));
		AffineTransform3D scale = new AffineTransform3D();
		scale.scale(dxyUm);
		t.preConcatenate(scale);
		return t;
	}

	/** {@code H^-1 m H}: the matrix CLIJ2 applies at voxel index + 1/2, for integer-centred voxels. */
	static AffineTransform3D halfVoxelConjugate(double[][] m) {
		AffineTransform3D t = new AffineTransform3D();
		t.set(m[0][0], m[0][1], m[0][2], m[0][3],
				m[1][0], m[1][1], m[1][2], m[1][3],
				m[2][0], m[2][1], m[2][2], m[2][3]);
		AffineTransform3D h = new AffineTransform3D();
		h.translate(0.5, 0.5, 0.5);
		AffineTransform3D result = h.copy();
		result.preConcatenate(t);
		result.preConcatenate(h.inverse());
		return result;
	}

	/** ImageJ's horizontal flip, {@code x -> width - 1 - x}. */
	static AffineTransform3D mirrorX(long width) {
		AffineTransform3D f = new AffineTransform3D();
		f.set(-1, 0, 0, width - 1.0, 0, 1, 0, 0, 0, 0, 1, 0);
		return f;
	}

	/** A 2 x 3 source-to-reference matrix acting on X and Y of every plane, Z unchanged. */
	static AffineTransform3D xyPlacement(double[][] m) {
		AffineTransform3D a = new AffineTransform3D();
		a.set(m[0][0], m[0][1], 0, m[0][2], m[1][0], m[1][1], 0, m[1][2], 0, 0, 1, 0);
		return a;
	}

	/**
	 * From the voxel centres of a level downsampled by block averaging to level-0 voxel centres:
	 * {@code x0 = f * x + (f - 1) / 2} on every axis (C1.6).
	 */
	static AffineTransform3D levelToBase(double[] factors) {
		AffineTransform3D p = new AffineTransform3D();
		if (factors == null) return p;
		p.set(factors[0], 0, 0, 0.5 * (factors[0] - 1),
				0, factors[1], 0, 0.5 * (factors[1] - 1),
				0, 0, factors[2], 0.5 * (factors[2] - 1));
		return p;
	}

	@Override
	public String toString() {
		return String.format(Locale.US, "%.4g um step, %.4g um pixel, %.4g deg (%s)", dzUm, dxyUm, angleDeg, origin);
	}
}
