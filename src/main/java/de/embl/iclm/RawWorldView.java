package de.embl.iclm;

import net.imglib2.realtransform.AffineTransform3D;

/** World-plane camera orientations. Source deskew, folding and registration are untouched. */
enum RawWorldView {

	XY("World XY", new double[] { 1, 0, 0, 0, 1, 0, 0, 0, 1 }),
	XZ("World XZ", new double[] { 1, 0, 0, 0, 0, 1, 0, -1, 0 }),
	YZ("World YZ", new double[] { 0, 1, 0, 0, 0, 1, 1, 0, 0 });

	final String label;
	/** Rows: screen right, screen down, positive depth; all bases are right-handed. */
	private final double[] basis;
	private static final double TOLERANCE = 1e-6;

	RawWorldView(String label, double[] basis) {
		this.label = label;
		this.basis = basis;
	}

	/**
	 * Preserve the world point at the canvas centre and the camera's uniform zoom. A BDV camera
	 * is a positive uniform scale times a rotation; source-to-world shear belongs to the sources.
	 * Reject a malformed camera rather than silently altering its scale or reflection. No inputs
	 * are mutated, and no image or file is accessed.
	 */
	AffineTransform3D orient(AffineTransform3D current, int width, int height) {
		if (width <= 0 || height <= 0) throw new IllegalArgumentException("The canvas has no size yet");
		double[] a = current.getRowPackedCopy();
		for (double v : a)
			if (!Double.isFinite(v)) throw new IllegalArgumentException("The camera contains a non-finite value");
		double scale = Math.hypot(Math.hypot(a[0], a[1]), a[2]);
		if (!(scale > 0) || !Double.isFinite(scale)) throw new IllegalArgumentException("The camera has no finite zoom");
		double[][] unit = new double[3][3];
		for (int i = 0; i < 3; i++)
			for (int j = 0; j < 3; j++) unit[i][j] = a[4 * i + j] / scale;
		for (int i = 0; i < 3; i++)
			for (int j = i; j < 3; j++) {
				double dot = 0;
				for (int k = 0; k < 3; k++) dot += unit[i][k] * unit[j][k];
				if (!Double.isFinite(dot) || Math.abs(dot - (i == j ? 1 : 0)) > TOLERANCE)
					throw new IllegalArgumentException("The camera must have uniform zoom and perpendicular axes");
			}
		double determinant = unit[0][0] * (unit[1][1] * unit[2][2] - unit[1][2] * unit[2][1])
				- unit[0][1] * (unit[1][0] * unit[2][2] - unit[1][2] * unit[2][0])
				+ unit[0][2] * (unit[1][0] * unit[2][1] - unit[1][1] * unit[2][0]);
		if (!(determinant > 0)) throw new IllegalArgumentException("The camera axes are reflected");
		double[] centre = { width / 2.0, height / 2.0, 0 }, anchor = new double[3];
		current.inverse().apply(centre, anchor);
		double[] coefficients = new double[12];
		for (int i = 0; i < 3; i++) {
			double translation = centre[i];
			for (int j = 0; j < 3; j++) {
				coefficients[4 * i + j] = scale * basis[3 * i + j];
				translation -= coefficients[4 * i + j] * anchor[j];
			}
			if (!Double.isFinite(translation)) throw new IllegalArgumentException("The camera centre is not finite");
			coefficients[4 * i + 3] = translation;
		}
		AffineTransform3D result = new AffineTransform3D();
		// Individual element setters invert after each write: swapping axes that way can be singular.
		result.set(coefficients);
		return result;
	}
}
