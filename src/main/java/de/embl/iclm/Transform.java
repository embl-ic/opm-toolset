package de.embl.iclm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import Jama.Matrix;
import ij.IJ;
import ij.ImagePlus;

import ij.WindowManager;
import ij.plugin.PlugIn;
import net.imglib2.realtransform.AffineTransform3D;



public class Transform implements PlugIn {
	private Parameter parameter = null;
	
	@Override
	public void run(String arg) {
		Debug.commandStarted ( "Utilities > Transform" );
		if (null == WindowManager.getCurrentImage()) return;
		
		parameter = new Parameter("transform");
		parameter.impInput = IJ.getImage();
		if ( !parameter.volume_transform() ) return;
		if ( 0 == parameter.nTransform ) return;
		String name = Utils.getName(parameter.impInput);
		String transformName = name;
		

		// timing the start
		
		parameter.autoPartition = true;
		
		// perform transformation on the input volume, for each transformation matrix
		for (int i=0; i<parameter.nTransform; i++) {
			if (!parameter.apply.get(i)) continue;
			
			String type = parameter.type.get(i);
			String axis = parameter.axis.get(i);
			double value = parameter.value.get(i);
			
			parameter.deskewMatrix = parseTransformation ( type, axis, value );
			if (parameter.doInverse) parameter.deskewMatrix = inverse( parameter.deskewMatrix );
			parameter.axisPartition = getPartitionAxis ( type, axis, value );
			parameter.axisCombine = parameter.axisPartition;
			transformName += getTransformName ( type, axis, value );
			
			ImagePlus imp_transformed = GPU.transform(parameter.impInput, parameter.deskewMatrix);
			parameter.impInput = imp_transformed;
			
			if (!parameter.display.get(i)) continue;
			
			if (transformName.length() < 55)	imp_transformed.setTitle( transformName );
			else 								imp_transformed.setTitle( name + "-transformed" );
			
			imp_transformed.show();
			imp_transformed.setZ((int)Math.round(imp_transformed.getNSlices()/2));
			imp_transformed.setDisplayRange(parameter.impInput.getDisplayRangeMin(), parameter.impInput.getDisplayRangeMax());
			imp_transformed.changes = false;
		}
		
		Utils.collectGarbage();
		// report script runtime
	}
	
	
	/**		Build the affine matrix for one entry of the volume transform dialog
	 * <br>		Each dialog row names a transformation type, an axis, and an amount; this turns
	 * <br>		that triplet into the 4 x 4 matrix the row stands for.
	 *
	 * @param type	: translate, scale, rotate, shear_X, shear_Y or shear_Z
	 * @param axis	: axis the transformation acts on: X, Y or Z
	 * @param value	: amount: pixels for translate, factor for scale, degrees for rotate
	 * <p>
	 * @return		: the transformation matrix, or null when the type and axis do not combine
	 */
	public static double[][] parseTransformation (
			String type,
			String axis,
			Double value
			) {
		if (null == type || null == axis || null == value) return null;
		/* Every branch returns or breaks. Shearing a plane along its own normal is not one of
		 * the offered transformations, so an axis that does not belong to the requested shear
		 * yields null rather than falling through into the next shear type. */
		switch (type) {
		case "translate":					return translate (axis, value);
		case "scale":						return scale (axis, value);
		case "rotate":						return rotate (axis, value);
		case "shear_X":
			if (axis.equals("Y"))			return shear ("X", 0, value, 0);
			else if (axis.equals("Z"))		return shear ("X", 0, 0, value);
			break;
		case "shear_Y":
			if (axis.equals("X")) 			return shear ("Y", value, 0, 0);
			else if (axis.equals("Z")) 		return shear ("Y", 0, 0, value);
			break;
		case "shear_Z":
			if (axis.equals("X")) 			return shear ("Z", value, 0, 0);
			else if (axis.equals("Y")) 		return shear ("Z", 0, value, 0);
			break;
		}
		return null;
	}


	/**			Pick an axis the volume can be cut along for one single transformation
	 * <br>		A partition axis is only usable when the transformation does not mix that axis
	 * <br>		with the others, so each part can be transformed on its own and the results
	 * <br>		concatenated again.
	 *
	 * @param type	: transformation type: translate, scale, rotate, shear_X, shear_Y, shear_Z
	 * @param axis	: axis the transformation acts on: X, Y or Z
	 * @param value	: transformation amount; only its presence is checked here
	 * <p>
	 * @return		: axis to partition along, or null when no axis is safe to cut
	 */
	public static String getPartitionAxis (
			String type,
			String axis,
			Double value
			) {
		if (null == type || null == axis || null == value) return null;
		switch (type) {
		case "translate":
		case "scale":
			return axis.equals("X") ? "Y" : "X";
		case "rotate":						return axis;
		case "shear_X":
			if (axis.equals("Y"))			return "Z";
			else if (axis.equals("Z"))		return "Y";
			break;
		case "shear_Y":
			if (axis.equals("X")) 			return "Z";
			else if (axis.equals("Z")) 		return "X";
			break;
		case "shear_Z":
			if (axis.equals("X")) 			return "Y";
			else if (axis.equals("Y")) 		return "X";
			break;
		}
		return null;
	}
	
	/**		Name one transformation the way it should appear in a result image title
	 *
	 * @param type	: translate, scale, rotate, shear_X, shear_Y or shear_Z
	 * @param axis	: axis the transformation acts on: X, Y or Z
	 * @param value	: amount, appended to the name so the title records what was applied
	 * <p>
	 * @return		: short name such as "rotateZ-30.0", or null for an invalid combination
	 */
	public static String getTransformName (
			String type,
			String axis,
			Double value
			) {
		String name =  "-" + type + axis + "(" + value + ")";
		return name;
	}
	
	
	/**		Apply the transformation the dialog has assembled, GPU first and CPU second
	 * <br>		The matrix comes from parameter, already combined from every enabled dialog row
	 * <br>		and inverted when the dialog asked for the inverse transform.
	 *
	 * @param imp		: input volume
	 * @param parameter	: holds the combined matrix, the partition settings and doInverse
	 * @param tryGPU	: attempt the GPU path first; the CPU path runs when it returns null
	 * <p>
	 * @return			: transformed volume, or null when both paths failed
	 */
	public static ImagePlus transform (
			ImagePlus imp,
			Parameter parameter,
			boolean tryGPU
			) {
		if (null == imp) return null;
		double[][] matrix = parameter.deskewMatrix;
		boolean doVirtual = parameter.doVirtual;
		
		if (tryGPU)
			return GPU.transform ( imp, matrix );
		else
			return CPU.transform ( imp, matrix, doVirtual);
	}
	

	/**		Create an identity matrix as a 4 by 4 double array
	 * <p>
	 * @return	: a fresh 4 x 4 homogeneous identity matrix, safe for the caller to modify
	 */
	public static double[][] identity () {
		double [][] matrix = {{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}, {0, 0, 0, 1}};
		return matrix;
	}
	
	/**		Create copy of the input matrix
	 * <br>	Deep, so the copy can be modified without touching the caller's matrix. Several
	 * <br>	transform paths adjust translation entries in place, which is only safe on a copy.
	 *
	 * @param matrix	: matrix to copy
	 * <p>
	 * @return			: an independent copy, or null when the input was null
	 */
	public static double[][] copy (double[][] matrix) {
		if (null == matrix) return null;
		int nRow = matrix.length;
		int nCol = matrix[0].length;
		double [][] matrix_copy = new double[nRow][nCol];
		for (int i=0; i<nRow; i++) {
			for (int j=0; j<nCol; j++) {
				matrix_copy[i][j] = matrix[i][j];
			}
		}
		return matrix_copy;
	}
	
	/** 	Inverse a transformation matrix
	 * <br>	Used to turn a forward deskew into the backward mapping a sampler needs, and to
	 * <br>	offer the "inverse transform" option in the dialogs.
	 *
	 * @param matrix	: 4 x 4 transformation matrix
	 * <p>
	 * @return			: the inverted matrix, or null when the matrix is singular
	 */
	public static double[][] inverse (double[][] matrix) {
		return new Matrix(matrix).inverse().getArray();
	}

	/**
	 * Invert a 2 x 3 ImageJ/SIFT affine alignment matrix while retaining the
	 * compact 2 x 3 representation.
	 *
	 * @param matrix source-to-target affine transform
	 * @return target-to-source affine transform
	 */
	public static double[][] inverseAlignmentMatrix2D (double[][] matrix) {
		double[][] homogeneous = alignmentMatrix2DToHomogeneous(matrix);
		return homogeneousToAlignmentMatrix2D(new Matrix(homogeneous).inverse().getArray());
	}

	/**
	 * Convert a matrix that aligns a horizontally flipped right half onto the
	 * left half into the matrix needed to align a horizontally flipped left half
	 * onto the unchanged right half.
	 *
	 * The conversion is F x inverse(matrix) x F. F is a horizontal reflection
	 * around the pixel coordinates [0, width - 1].
	 *
	 * @param matrix right-flipped-to-left 2 x 3 alignment matrix
	 * @param width width in pixels of one camera half
	 * @return left-flipped-to-right 2 x 3 alignment matrix
	 */
	public static double[][] mirrorAlignmentMatrix2D (double[][] matrix, int width) {
		if (width < 1) throw new IllegalArgumentException("Image-half width must be positive.");
		double edge = width - 1.0;
		double[][] flip = {
			{ -1.0, 0.0, edge },
			{ 0.0, 1.0, 0.0 },
			{ 0.0, 0.0, 1.0 }
		};
		double[][] forward = alignmentMatrix2DToHomogeneous(matrix);
		double[][] inverse = new Matrix(forward).inverse().getArray();
		double[][] mirrored = new Matrix(flip).times(new Matrix(inverse)).times(new Matrix(flip)).getArray();
		return homogeneousToAlignmentMatrix2D(mirrored);
	}

	/**
	 * Take a right-flipped-to-reference alignment into the frame of an unflipped right half.
	 *
	 * The result is F x inverse(anchor) x matrix x F, with F the horizontal reflection around
	 * the pixel coordinates [0, width - 1]; see {@link AlignmentMatrixSet#placement} for why.
	 * A null matrix or anchor stands for the identity, so
	 * {@code reframeAlignmentMatrix2D(null, M, w)} equals {@link #mirrorAlignmentMatrix2D}.
	 *
	 * @param matrix the stored source-to-reference 2 x 3 matrix, or null
	 * @param anchor the stored matrix of the right half that becomes the fixed frame, or null
	 * @param width width in pixels of one camera half
	 * @return the 2 x 3 matrix for the same source when the left halves are mirrored instead
	 */
	public static double[][] reframeAlignmentMatrix2D (double[][] matrix, double[][] anchor, int width) {
		if (width < 1) throw new IllegalArgumentException("Image-half width must be positive.");
		double edge = width - 1.0;
		Matrix flip = new Matrix(new double[][] {
			{ -1.0, 0.0, edge },
			{ 0.0, 1.0, 0.0 },
			{ 0.0, 0.0, 1.0 }
		});
		Matrix identity = Matrix.identity(3, 3);
		Matrix source = matrix == null ? identity : new Matrix(alignmentMatrix2DToHomogeneous(matrix));
		Matrix frame = anchor == null ? identity : new Matrix(alignmentMatrix2DToHomogeneous(anchor)).inverse();
		return homogeneousToAlignmentMatrix2D(flip.times(frame).times(source).times(flip).getArray());
	}

	private static double[][] alignmentMatrix2DToHomogeneous (double[][] matrix) {
		if (matrix == null || matrix.length < 2 || matrix[0] == null || matrix[1] == null ||
				matrix[0].length < 3 || matrix[1].length < 3)
			throw new IllegalArgumentException("A 2 x 3 alignment matrix is required.");
		return new double[][] {
			{ matrix[0][0], matrix[0][1], matrix[0][2] },
			{ matrix[1][0], matrix[1][1], matrix[1][2] },
			{ 0.0, 0.0, 1.0 }
		};
	}

	private static double[][] homogeneousToAlignmentMatrix2D (double[][] matrix) {
		return new double[][] {
			{ matrix[0][0], matrix[0][1], matrix[0][2] },
			{ matrix[1][0], matrix[1][1], matrix[1][2] }
		};
	}
	
	
	/**		Compute cross product of two transformation matrix
	 * <br>	The order of multiplication matters. 
	 * <br>	m3 = m1 × m2: 
	 * <br>	means transform with m3, equals transform first with m2, and then m1
	 * <p>
	 * @param matrix_1			: input matrix m1, as 2D double array
	 * @param matrix_2			: input matrix m2, as 2D double array
	 * <p>
	 * @return matrix_3			: output matrix m3, as cross product: m3 = m1 × m2, also as 2D double array
	 */
	public static double[][] crossproduct (double[][] matrix_1, double[][] matrix_2) {
		if (null == matrix_1 || null == matrix_2) return null;
		Matrix m1 = new Matrix(matrix_1);
		Matrix m2 = new Matrix(matrix_2);
		return m1.times(m2).getArray();
	}
	
	
	/**		Translation transformation
	 *
	 * @param translate_x	: shift along X, in pixels
	 * @param translate_y	: shift along Y, in pixels
	 * @param translate_z	: shift along Z, in pixels
	 * <p>
	 * @return				: 4 x 4 translation matrix
	 */
	public static double[][] translate (
			double translate_x,
			double translate_y,
			double translate_z
			) {
		double [][] matrix = identity();
		matrix[0][3] = translate_x;
		matrix[1][3] = translate_y;
		matrix[2][3] = translate_z;
		return matrix;
	}
	/**		Translation transformation along one axis
	 *
	 * @param axis	: X, Y or Z
	 * @param value	: shift along that axis, in pixels
	 * <p>
	 * @return		: 4 x 4 translation matrix; identity when the axis is not recognised
	 */
	public static double[][] translate (
			String axis,
			double value
			) {
		switch (axis.toLowerCase()) {
		case "x":
			return translate ( value, 0, 0 );
			
		case "y":
			return translate ( 0, value, 0 );
			
		case "z":
			return translate ( 0, 0, value );
			
		default:
			return identity();
		}
	}
	
	/**		Scaling transformation (Mirror transformation if scale factor is negative)
	 * <br>	A factor of exactly 0 would collapse the axis and make the matrix singular, so it
	 * <br>	is replaced by the smallest positive double.
	 *
	 * @param scale_x	: factor along X; 2.0 means twice the original size
	 * @param scale_y	: factor along Y
	 * @param scale_z	: factor along Z
	 * <p>
	 * @return			: 4 x 4 scaling matrix
	 */
	public static double[][] scale (
			double scale_x,
			double scale_y,
			double scale_z
			) {
		double [][] matrix = identity();
		if (0 == scale_x) scale_x = Double.MIN_VALUE;
		if (0 == scale_y) scale_y = Double.MIN_VALUE;
		if (0 == scale_z) scale_z = Double.MIN_VALUE;
		matrix[0][0] = scale_x;
		matrix[1][1] = scale_y;
		matrix[2][2] = scale_z;
		return matrix;
	}
	/**		Scaling transformation along one axis
	 *
	 * @param axis	: X, Y or Z
	 * @param value	: factor along that axis; negative mirrors the axis
	 * <p>
	 * @return		: 4 x 4 scaling matrix; identity when the axis is not recognised
	 */
	public static double[][] scale (
			String axis,
			double value
			) {
		switch (axis.toLowerCase()) {
		case "x":
			return scale ( value, 1.0, 1.0 );
			
		case "y":
			return scale ( 1.0, value, 1.0 );
			
		case "z":
			return scale ( 1.0, 1.0, value );
			
		default:
			return identity();
		}
	}
	
	/**		Rotation transformation about one axis
	 * <br>	Follows the right hand rule, the same convention as the OPM angle in deskew(...).
	 *
	 * @param axis	: axis to rotate about: X, Y or Z
	 * @param angle	: rotation angle in degrees
	 * <p>
	 * @return		: 4 x 4 rotation matrix; identity when the axis is not recognised
	 */
	public static double[][] rotate(
			String axis, 
			double angle
			) {
		double [][] matrix = identity();
		double sin_theta = Utils.sin(angle);
		double cos_theta = Utils.cos(angle);
		switch (axis.toLowerCase()) {
		case "x":	// rotation in YZ plane, following right hand rule
			matrix[1][1] = cos_theta;
			matrix[1][2] = -sin_theta;
			matrix[2][1] = sin_theta;
			matrix[2][2] = cos_theta;
			break;
			
		case "y":	// rotation in XZ plane, following right hand rule
			matrix[0][0] = cos_theta;
			matrix[0][2] = sin_theta;
			matrix[2][0] = -sin_theta;
			matrix[2][2] = cos_theta;
			break;
			
		case "z":	// rotation in XY plane, following right hand rule
			matrix[0][0] = cos_theta;
			matrix[0][1] = -sin_theta;
			matrix[1][0] = sin_theta;
			matrix[1][1] = cos_theta;
			break;
		}
		return matrix;
	}
	
	/**		Shearing transformation
	 * <br>	The axis names the direction along which the shear accumulates; the three amounts
	 * <br>	say how the coordinates move per unit of it. The deskew is a shear of Y along Z.
	 *
	 * @param axis		: axis along which the shear accumulates: X, Y or Z
	 * @param shear_x	: displacement of X per unit of that axis
	 * @param shear_y	: displacement of Y per unit of that axis
	 * @param shear_z	: displacement of Z per unit of that axis
	 * <p>
	 * @return			: 4 x 4 shear matrix
	 */
	public static double[][] shear (
			String axis,
			double shear_x,
			double shear_y,
			double shear_z
			) {
		double [][] matrix = identity();
		switch (axis.toLowerCase()) {
		case "x":	// shearing in X axis
			matrix[0][1] = shear_y;
			matrix[0][2] = shear_z;
			break;
		case "y":	// shearing in Y axis
			matrix[1][0] = shear_x;
			matrix[1][2] = shear_z;
			break;
		case "z":	// shearing in Z axis
			matrix[2][0] = shear_x;
			matrix[2][1] = shear_y;
			break;
		}
		return matrix;
	}
	
	/**		Affine transformation
	 * <br>		as serial cross product of input tranformation matrix list.
	 * <br>		The list is inversed, so that combined transformation is in the order of the list.
	 * <br>		e.g.: input list: { m1,  m2,  m3,  m4,  m5 }
	 * <br>		matrix_final = m5 × m4 × m3 × m2 × m1
	 * <br>		means transform in the order of m1, m2, m3, m4, and then m5.
	 * 
	 * @param matrix_list			: input transformation matrix list, as list of 2D double array
	 * <p>
	 * @return matrix_product		: output matrix, as serial cross product: m = m1 × m2 × m3 × m4..., as 2D double array
	 */
	public static double[][] affine (
			List<double[][]> matrix_list
			) {
		Collections.reverse(matrix_list);
		double [][] matrix = identity();
		Iterator<double[][]> iter = matrix_list.iterator(); 
	    while (iter.hasNext()) {
	    	matrix = crossproduct( matrix, iter.next() );
	    }
		return matrix;
	}
	
	/**		The Z row scale of the deskew matrix
	 * <br>	Its theoretical value is exactly 0: the deskewed grid is measured in camera pixels,
	 * <br>	so one stage step contributes nothing along output Z beyond the shear and the
	 * <br>	rotation already in the matrix. It is kept as the smallest positive double rather
	 * <br>	than 0 because a row of exact zeros has been rejected downstream as a singular
	 * <br>	matrix; numerically the two are indistinguishable at every use site, since the
	 * <br>	determinant is carried by (dz_step / dxy) * sinθ either way.
	 * <p>	TransformDeskewTest pins this, so a future tidy-up cannot change it unnoticed.
	 */
	protected static final double Z_SCALE_NEAR_ZERO = Double.MIN_VALUE;

	/**		Deskew transformation
	 * <br>		directly construct the combined deskew affine transform matrix
	 * <br>		designed specifically for EMBL IC in house build OPM system (by Dr. Rory Power)
	 * <br>		https://www.embl.org/about/info/imaging-centre/light-microscopy-services/
	 * <p>		The matrix maps the raw XY-Z stack onto an output grid measured in camera
	 * <br>		pixels, so the result is isotropic at dxy in X, Y and Z; the stage step and the
	 * <br>		OPM angle set the transformed bounds, not the output voxel pitch.
	 *
	 * @param dzstep				: Z step size, in the same unit as dxy
	 * @param dxy					: XY pixel size, in the same unit as dzstep
	 * @param opmAngle				: OPM angle in degree: θ
	 * @param imageHeight			: OPM raw data image height: size of Y axis
	 * <p>
	 * @return						: combined affine transform (deskew) matrix, as 2D double array
	 */
	public static double[][] deskew (
			double dzstep,
			double dxy,
			double opmAngle,	//e.g.: -25.0 degree
			double imageHeight
			) {
		/* compute affine transform values
		 *  1: shear Y along Z for: dz_step * cos0
		 *  2: scale Z for: dz_step * sin0 / dxy
		 *  3: rotate around X for: -0 (follow right hand law)
		 */
		double cos_theta = Utils.cos(opmAngle);		// cos0
		double sin_theta = Utils.sin(opmAngle);		// sin0				// α
		double translate_z = imageHeight * sin_theta;	// h * sin0
		// construct the affine transform matrix
		double [][] matrix = identity();

		matrix[1][1] = cos_theta;
		matrix[1][2] = dzstep/dxy;
		matrix[2][1] = -sin_theta;
		matrix[2][2] = Z_SCALE_NEAR_ZERO;
		matrix[2][3] = translate_z;

		return matrix;
	}

	/**			Reverse compute input parameters from deskew matrix
	 * <br>		The inverse of deskew(...): recovers the acquisition geometry from a matrix that
	 * <br>		was loaded from file, so a saved matrix can repopulate the dialog. Returns null
	 * <br>		when sinθ and cosθ in the matrix disagree by more than 1%, which means the matrix
	 * <br>		is not a deskew matrix of this form.
	 *
	 * @param matrix			: 3 x 4 deskew matrix, as produced by deskew(...)
	 * @param newImageHeight	: raw image height the recovered Z translation should apply to
	 * <p>
	 * @return double[] {dzstep_dxy, opmAngle, matrix[2][3]}
	 */
	public static double[] reverse_deskew (
			double[][] matrix,
			double newImageHeight
			) {
		if (null == matrix) return null;
		double sin_theta = -matrix[2][1];
		double cos_theta = matrix[1][1];
		double opmAngle1 = Utils.arccos ( cos_theta ); // 0 ~ 180 (by constrain, can be only 0 ~ 90, so only positive value)
		double opmAngle2 = Utils.arcsin ( sin_theta ); // -90 ~ 90
		double factor = Math.abs( opmAngle1 / opmAngle2 );
		if (factor > 1.01 || factor < 0.99) return null;	// allow only 1% mismatch between cos and sin
		double opmAngle = (double)Math.round( 10* Math.sqrt( Math.abs(opmAngle1 * opmAngle2) ) ) / (double)10;
		if (opmAngle2 < 0) opmAngle = -opmAngle;
		double dzstep_dxy = matrix[1][2];
		double m23 = newImageHeight * Utils.sin( opmAngle );
		return new double[] {dzstep_dxy, opmAngle, m23};
	}
	
	
	/**			Calculate volume dimension after transformation
	 * <p>		based on min and max of xyz coordinates
	 *
	 * @param cor_minmax	: 2 x 3 array of {min, max} for X, Y and Z after transformation
	 * <p>
	 * @return				: output volume size {X, Y, Z}, each rounded up to whole voxels
	 */
	public static long[] getTransformedDim (
			double[][] cor_minmax
			) {
		long xDim = (long) Math.ceil( cor_minmax[1][0] - cor_minmax[0][0] );
		long yDim = (long) Math.ceil( cor_minmax[1][1] - cor_minmax[0][1] );
		long zDim = (long) Math.ceil( cor_minmax[1][2] - cor_minmax[0][2] );
		return new long[] {xDim, yDim, zDim};
	}
	/**			Calculate voume dimension after transformation
	 * <p>		based on input volume dimension, transform matrix
	 * 			do not perform automatic recenter
	 *
	 * @param dims				: input volume size, in ImageJ XYCZT order
	 * @param matrix			: 4 x 4 transformation matrix
	 * @param autoCenterMatrix	: also shift the matrix so the result starts at the origin
	 * <p>
	 * @return					: output volume size {X, Y, Z}
	 */
	public static long[] getTransformedDim (
			long[] dims,
			double[][] matrix,
			boolean autoCenterMatrix
			) {
		double[][] cor_minmax = getCoordMinMax ( dims, matrix );
		if (autoCenterMatrix) 
			matrix = autoCenter ( cor_minmax, matrix );
		return getTransformedDim ( cor_minmax );
	}
	public static long[] getTransformedDim (
			int[] dims,
			double[][] matrix,
			boolean autoCenterMatrix
			) {
		long[] dims_long = Arrays.stream(dims).asLongStream().toArray();
		return getTransformedDim ( dims_long, matrix, autoCenterMatrix );
	}
	/**			Calculate voume dimension after transformation
	 * <p>		based on input volume dimension, transform matrix
	 * 			automatically recenter
	 *
	 * @param dims		: input volume size, in ImageJ XYCZT order
	 * @param matrix	: 4 x 4 transformation matrix, recentred in place
	 * <p>
	 * @return			: output volume size {X, Y, Z}
	 */
	public static long[] getTransformedDim (
			long[] dims,
			double[][] matrix
			) {
		return getTransformedDim ( dims, matrix, false );
	}

	

	
	
	/**			get possible partition axis from tranformation matrix
	 * <br>		An axis can be cut only where the matrix does not mix it with the other two, so
	 * <br>		that each part transforms on its own and the parts concatenate again. The sign
	 * <br>		says whether the parts have to be recombined in reverse order.
	 *
	 * @param matrix	: 4 x 4 transformation matrix
	 * @param minValue	: how small an off diagonal entry counts as no mixing
	 * <p>
	 * @return			: "X+", "X-", "Y+", "Y-", "Z+", "Z-", or null when no axis is safe
	 */
	public static String getTransformPartitionAxis (
			double[][] matrix,
			double minValue
			) {
		// check X axis for partition possibility
		boolean b01 = Math.abs( matrix[0][1] ) <= minValue;					// m01 small enough 
		boolean b02 = Math.abs( matrix[0][2] ) <= minValue; 				// m02 small enough
		if ( b01 && b02 ) return ( matrix[0][0] < 0 ? "X-" : "X+" );	// if m00 negative, reverse X
		// check Y axis for partition possibility
		boolean b10 = Math.abs( matrix[1][0] ) <= minValue;					// m10 small enough 
		boolean b12 = Math.abs( matrix[1][2] ) <= minValue; 				// m12 small enough
		if ( b10 && b12 ) return ( matrix[1][1] < 0 ? "Y-" : "Y+" );	// if m11 negative, reverse Y
		// check Z axis for partition possibility
		boolean b20 = Math.abs( matrix[2][0] ) <= minValue;					// m20 small enough 
		boolean b21 = Math.abs( matrix[2][1] ) <= minValue; 				// m21 small enough
		if ( b20 && b21 ) return ( matrix[2][2] < 0 ? "Z-" : "Z+" );	// if m22 negative, reverse Z
		// no axis is suitable for partition, return null
		return null;
	}
	public static String getTransformPartitionAxis (
			double[][] matrix
			) {
		return getTransformPartitionAxis ( matrix, 1e-4 );	// default min value 0.002
	}
	
	
	/**		Shift a transformation so the transformed volume starts at the origin
	 * <br>		A rotation or a shear moves part of the volume to negative coordinates, which a
	 * <br>		pixel grid cannot hold; the translation column absorbs that offset.
	 *
	 * @param cor_minmax	: 2 x 3 array of {min, max} for X, Y and Z after transformation
	 * @param matrix		: 4 x 4 transformation matrix, modified in place
	 * <p>
	 * @return				: the same matrix, with its translation adjusted
	 */
	public static double[][] autoCenter (
			double[][] cor_minmax,
			double[][] matrix
			) {
		double x_min = cor_minmax[0][0];
		double y_min = cor_minmax[0][1];
		double z_min = cor_minmax[0][2];
		
		double m03 = matrix[0][3]; double m13 = matrix[1][3]; double m23 = matrix[2][3];
		if (x_min < 0) m03 -= x_min; if (y_min < 0) m13 -= y_min; if (z_min < 0) m23 -= z_min;
		matrix[0][3] = m03;		matrix[1][3] = m13;		matrix[2][3] = m23;
		return matrix;
	}
	public static double[][] autoCenter (
			long[] dims,
			double[][] matrix
			) {
		double[][] cor_minmax = getCoordMinMax ( dims, matrix );
		return autoCenter ( cor_minmax, matrix );
	}
	
	/**		Transform the eight corners of a volume and collect their bounding box
	 * <br>		An affine transform maps a box to a parallelepiped, so the corners are enough to
	 * <br>		find the extent of the result.
	 *
	 * @param dims		: input volume size, in ImageJ XYCZT order
	 * @param matrix	: 4 x 4 transformation matrix
	 * <p>
	 * @return			: 2 x 3 array of {min, max} for X, Y and Z
	 */
	public static double[][] getCoordMinMax (
			long[] dims,
			double[][] matrix
			) {
		// get the width, height, and depth of input volume dimension
		double w = (double) dims[0]; double h = (double) dims[1]; double d = (double) dims[2];
		if (1 == d && dims.length>4) d = (double) dims[3];
		// get transformation matrix cooefficients
		double m00 = matrix[0][0]; double m10 = matrix[1][0]; double m20 = matrix[2][0]; 
		double m01 = matrix[0][1]; double m11 = matrix[1][1]; double m21 = matrix[2][1];
		double m02 = matrix[0][2]; double m12 = matrix[1][2]; double m22 = matrix[2][2];
		double m03 = matrix[0][3]; double m13 = matrix[1][3]; double m23 = matrix[2][3];
		/* compute coordinate of the 8 corners after transformation
		 * 
		 * P1(0,0,0) — P2(w,0,0)	/ 	P5(0,0,d) — P6(w,0,d)
		 *		|       	 | 		/		 |			  | 	
		 * P3(0,h,0) — P4(w,h,0)	/ 	P7(0,h,d) — P8(w,h,d)
		 */
		List<Double> x_cor = new ArrayList<Double>();
		List<Double> y_cor = new ArrayList<Double>();
		List<Double> z_cor = new ArrayList<Double>();
		// add 8 points X coordinates
		x_cor.add( m03 );			x_cor.add( w*m00 + h*m01 + d*m02 + m03 );	// P1, P8
		x_cor.add( w*m00 + m03 );	x_cor.add( h*m01 + d*m02 + m03 );			// P2, P7
		x_cor.add( h*m01 + m03 );	x_cor.add( w*m00 + d*m02 + m03 );			// P3, P6
		x_cor.add( d*m02 + m03 );	x_cor.add( w*m00 + h*m01 + m03 );			// P5, P4
		// add 8 points Y coordinates
		y_cor.add( m13 );			y_cor.add( w*m10 + h*m11 + d*m12 + m13 );	// P1, P8
		y_cor.add( w*m10 + m13 );	y_cor.add( h*m11 + d*m12 + m13 );			// P2, P7
		y_cor.add( h*m11 + m13 );	y_cor.add( w*m10 + d*m12 + m13 );			// P3, P6
		y_cor.add( d*m12 + m13 );	y_cor.add( w*m10 + h*m11 + m13 );			// P5, P4
		// add 8 points Z coordinates
		z_cor.add( m23 );			z_cor.add( w*m20 + h*m21 + d*m22 + m23 );	// P1, P8
		z_cor.add( w*m20 + m23 );	z_cor.add( h*m21 + d*m22 + m23 );			// P2, P7
		z_cor.add( h*m21 + m23 );	z_cor.add( w*m20 + d*m22 + m23 );			// P3, P6
		z_cor.add( d*m22 + m23 );	z_cor.add( w*m20 + h*m21 + m23 );			// P5, P4
		
		double x_min = Collections.min( x_cor ); double x_max = Collections.max( x_cor );
		double y_min = Collections.min( y_cor ); double y_max = Collections.max( y_cor );
		double z_min = Collections.min( z_cor ); double z_max = Collections.max( z_cor );
	
		return new double[][] { {x_min, y_min, z_min}, {x_max, y_max, z_max} };
	}

	/**		Identity transform in the ImgLib2 representation
	 * <p>
	 * @return	: an ImgLib2 AffineTransform3D that changes nothing
	 */
	public static AffineTransform3D identity_imglib2 () {
		AffineTransform3D matrix = new AffineTransform3D();
		matrix.identity();
		return matrix;
	}
	
	/**		Inverse of an ImgLib2 transform
	 *
	 * @param matrix	: ImgLib2 transform
	 * <p>
	 * @return			: its inverse, as a new object
	 */
	public static AffineTransform3D inverse (AffineTransform3D matrix) {
		return matrix.inverse();
	}
	
	/**		Convert this class's 4 x 4 double array into an ImgLib2 transform
	 * <br>		ImgLib2 and CLIJ2 both take the 3 x 4 upper part; the last row is implied.
	 *
	 * @param matrix_raw	: 4 x 4 transformation matrix
	 * <p>
	 * @return				: the same transformation as an ImgLib2 AffineTransform3D
	 */
	public static AffineTransform3D raw_to_imglib2 (double[][] matrix_raw) {
		AffineTransform3D matrix = new AffineTransform3D();
		matrix.set(matrix_raw);
		return matrix;
	}
	
	/**		The deskew transformation, already converted for ImgLib2
	 *
	 * @param dzstep		: Z step size, in the same unit as dxy
	 * @param dxy			: XY pixel size, in the same unit as dzstep
	 * @param opmAngle		: OPM angle in degree
	 * @param imageHeight	: OPM raw data image height: size of Y axis
	 * <p>
	 * @return				: the deskew transform as an ImgLib2 AffineTransform3D
	 */
	public static AffineTransform3D deskew_imglib2 (
			double dzstep,
			double dxy,
			double opmAngle,
			double imageHeight
			) {
		double[][] deskew_raw = deskew( dzstep, dxy, opmAngle, imageHeight );
		return raw_to_imglib2( deskew_raw );
	}
	
	

	
	/*
	public static void align2ndChannelwithMatrix2 (
			ImagePlus imp,
			double[][] matrix
			) {
		if (null == imp || null == matrix || 2 != imp.getNChannels()) return;
		ImagePlus[] imp_channels = ChannelSplitter.split ( imp );
		imp_channels[0].setTitle("debug_ch1");
		imp_channels[0].show();
		imp_channels[1].setTitle("debug_ch2");
		imp_channels[1].show();
		ImagePlus imp_ch2 = GPU.transform ( imp_channels[1], matrix, "Z", false );
		imp_ch2.setTitle("debug_ch2_align");
		imp_ch2.show();
	}
	*/
	
	public static double[][] convertTo3Dtransform ( double[][] matrix_2d ) {
		if (null == matrix_2d) return null;
		double[][] matrix_3d = identity();
		matrix_3d[0][0] = matrix_2d[0][0];
		matrix_3d[0][1] = matrix_2d[0][1];
		matrix_3d[0][3] = matrix_2d[0][2];
		matrix_3d[1][0] = matrix_2d[1][0];
		matrix_3d[1][1] = matrix_2d[1][1];
		matrix_3d[1][3] = matrix_2d[1][2];
		return matrix_3d;
	}
	
	
	
	/**			add X axis flip to the affine transform matrix
	 * <br>		Lets the mirrored right camera half be flipped and deskewed in one pass, instead
	 * <br>		of flipping the volume first and transforming it afterwards.
	 *
	 * @param imp		: image the matrix will be applied to; only its width is used
	 * @param matrix	: 4 x 4 deskew matrix
	 * <p>
	 * @return			: a new matrix that mirrors X and then applies the deskew
	 */
	public static double[][] matrix_flipX (
			ImagePlus imp,
			double[][] matrix
			) {
		if (null == imp || null == matrix) return null;
		double[][] matrix_flip = copy (matrix);	
		matrix_flip[0][0] *= -1;
		matrix_flip[0][1] *= -1;
		matrix_flip[0][2] *= -1;
		matrix_flip[0][3] = - matrix_flip[0][3] - imp.getWidth();
		return matrix_flip;
	}
	
	
	public static int[][] point_inverseDeskew (int[][] points, double[][] matrix, int depth) {
		if (null == points || null == matrix) return null;
		AffineTransform3D transform = raw_to_imglib2(matrix);
		
		double[] corner_source = {0, 0, 0};
		double[] corner_target = {0, 0, depth};
		transform.applyInverse( corner_source, corner_target );
		
		int nPts = points.length;
		int[][] points_inverse = new int[nPts][3];
		for (int i=0; i<nPts; i++) {
			double[] xyz_source = new double[3];
			double[] xyz_target = { (double)points[i][0], (double)points[i][1], (double)points[i][2] };
			transform.applyInverse( xyz_source, xyz_target );
			if ( xyz_source[1]<0 ) xyz_source[1] = 0;
			if ( xyz_source[2]<0 ) xyz_source[2] = 0;
			points_inverse[i][0] = (int) Math.round( xyz_source[0] );
			points_inverse[i][1] = (int) Math.round( xyz_source[1] );//- corner_source[1] );
			points_inverse[i][2] = (int) Math.round( xyz_source[2] );//- corner_source[2] );
		}
		return points_inverse;
	}
	
	
	
	
}
