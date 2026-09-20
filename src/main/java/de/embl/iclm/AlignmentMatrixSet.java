package de.embl.iclm;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A rigid 2-D transform for every optical source, all expressed in one reference frame.
 *
 * <p>The historic alignment file is a bare 2 x 3 CSV.  It remains the preferred format for
 * the common one-file/two-half acquisition and means exactly what it always meant: mirror the
 * right half, then transform it onto the untouched left half.  A file with more than two
 * selected sources uses the tagged v1 form documented in {@link #save(File)}.  Keeping the
 * old representation byte-simple is important: old plugin versions, macros and hand-edited
 * bead matrices continue to work.</p>
 */
public final class AlignmentMatrixSet {

	public static final String HEADER = "# OPM alignment matrix set v1";
	public static final String CONVENTION = "right-halves-mirrored;each-source-to-reference";
	private static final double[][] IDENTITY = { { 1, 0, 0 }, { 0, 1, 0 } };

	private String reference;
	private final LinkedHashMap<String, double[][]> matrices = new LinkedHashMap<String, double[][]>();
	private boolean legacy;

	public AlignmentMatrixSet(String reference) {
		this.reference = clean(reference);
		if (this.reference != null) matrices.put(this.reference, copy(IDENTITY));
	}

	/** Build the historic right-to-left interpretation of a plain 2 x 3 matrix. */
	public static AlignmentMatrixSet legacy(double[][] matrix) {
		if (!is2d(matrix)) return null;
		AlignmentMatrixSet set = new AlignmentMatrixSet(ChannelOperationSettings.sourceKey(1, true));
		set.matrices.put(ChannelOperationSettings.sourceKey(1, false), copy(matrix));
		set.legacy = true;
		return set;
	}

	/** A tagged set rebuilt from what a dataset recorded, or null when it recorded none. */
	static AlignmentMatrixSet tagged(String reference, Map<String, double[][]> matrices) {
		if (matrices == null || matrices.isEmpty()) return null;
		AlignmentMatrixSet set = new AlignmentMatrixSet(reference);
		for (Map.Entry<String, double[][]> entry : matrices.entrySet())
			if (is2d(entry.getValue())) set.put(entry.getKey(), entry.getValue());
		return set;
	}

	public String reference() { return reference; }
	public boolean isLegacy() { return legacy; }
	public int size() { return matrices.size(); }
	public List<String> sources() { return Collections.unmodifiableList(new ArrayList<String>(matrices.keySet())); }

	public Map<String, double[][]> matrices() {
		LinkedHashMap<String, double[][]> result = new LinkedHashMap<String, double[][]>();
		for (Map.Entry<String, double[][]> entry : matrices.entrySet())
			result.put(entry.getKey(), copy(entry.getValue()));
		return result;
	}

	public void put(String source, double[][] matrix) {
		String key = clean(source);
		if (key == null) throw new IllegalArgumentException("An alignment source name is required.");
		if (!is2d(matrix)) throw new IllegalArgumentException("A 2 x 3 alignment matrix is required for " + key + ".");
		matrices.put(key, copy(matrix));
		if (reference == null) reference = key;
	}

	/**
	 * Matrix for a canonical source label.  A tagged file is explicit.  A legacy file applies
	 * its one matrix to every right half, preserving Batch/Live's established behaviour for
	 * acquisitions that happen to contain more than one acquisition-channel file.
	 */
	public double[][] matrixFor(String source) {
		double[][] exact = matrices.get(source);
		if (exact != null) return copy(exact);
		if (legacy && source != null && source.endsWith("-right")) {
			double[][] old = matrices.get(ChannelOperationSettings.sourceKey(1, false));
			return copy(old);
		}
		return null;
	}

	public boolean isReference(String source) { return reference != null && reference.equals(source); }

	/** Whether a matrix is the identity, within rounding. */
	static boolean isIdentity(double[][] matrix) {
		return matrix != null && Math.abs(matrix[0][0] - 1) < 1e-12 && Math.abs(matrix[0][1]) < 1e-12
				&& Math.abs(matrix[0][2]) < 1e-12 && Math.abs(matrix[1][0]) < 1e-12
				&& Math.abs(matrix[1][1] - 1) < 1e-12 && Math.abs(matrix[1][2]) < 1e-12;
	}

	/** How one camera half is brought into the output: mirrored or not, then this matrix. */
	public static final class Placement {
		/** Mirror the half horizontally before the matrix is applied. */
		public final boolean mirror;
		/** The 2 x 3 alignment applied after any mirror; null when there is nothing to apply. */
		public final double[][] matrix;

		Placement(boolean mirror, double[][] matrix) {
			this.mirror = mirror;
			this.matrix = matrix == null || isIdentity(matrix) ? null : copy(matrix);
		}
	}

	/**			Where one source goes for the half the user chose to flip
	 * <p>		<b>The matrices are always measured one way, and the flip is always the user's.</b>
	 * 			A stored matrix maps its source - right halves mirrored, left halves not - onto
	 * 			the reference. That is the convention {@code Channel Alignment} computes in, for a
	 * 			bare two-half CSV and a tagged set alike, and it never changes. Which half is
	 * 			mirrored in the result is decided when the result is made, and a different choice
	 * 			needs no new measurement: it is the same aligned result seen from the other side.
	 * <p>		Flipping the left halves instead mirrors every left half and no right half, and
	 * 			makes the <em>unflipped right half of the reference's own acquisition channel</em>
	 * 			the fixed frame - exactly as a bare CSV's "flip left half onto right" always did,
	 * 			which this reproduces. Every half-source matrix becomes
	 * <pre>    F * inverse(A) * M * F</pre>
	 * 			with F the mirror about [0, width - 1], M the stored matrix of the source and A the
	 * 			stored matrix of that right half (identity where there is none). Derivation: a warp
	 * 			by M places {@code out(p) = src(M^-1 p)}, so warps compose as matrix products; the
	 * 			flip-right placement of a half is {@code M F} (right) or {@code M} (left), and the
	 * 			flip-left one is that placement taken into the frame of {@code A F}, i.e. multiplied
	 * 			on the left by {@code (A F)^-1 = F A^-1}. For a right half that is
	 * 			{@code (F A^-1 M F)}, unmirrored; for a left half {@code (F A^-1 M F) F}, mirrored.
	 * 			With a bare CSV's single M this gives identity on the right halves and
	 * 			{@code F M^-1 F} = {@link Transform#mirrorAlignmentMatrix2D} on the left ones.
	 * <p>		A full-width source is never mirrored and keeps its own matrix whichever half is
	 * 			flipped: it carries both halves in their acquired places.
	 *
	 * @param set				: the loaded matrices, or null for none
	 * @param source			: a source key such as {@code _Channel0002-right}
	 * @param flipLeft			: mirror the left halves rather than the right ones
	 * @param halfWidth			: width in pixels of one camera half, the mirror's extent
	 * <p>
	 * @return					: the mirror and the matrix to apply after it
	 */
	public static Placement placement(AlignmentMatrixSet set, String source, boolean flipLeft,
			int halfWidth) {
		if (ChannelOperationSettings.isWholeSource(source))
			return new Placement(false, set == null || set.legacy ? null : set.matrices.get(source));
		boolean left = source != null && source.endsWith("-left");
		boolean mirror = flipLeft ? left : !left;
		if (set == null) return new Placement(mirror, null);
		double[][] stored = set.matrixFor(source);
		if (!flipLeft) return new Placement(mirror, stored);

		int channel = ChannelOperationSettings.acquisitionChannelOf(set.reference);
		double[][] anchor = channel > 0
				? set.matrixFor(ChannelOperationSettings.sourceKey(channel, false)) : null;
		return new Placement(mirror, Transform.reframeAlignmentMatrix2D(stored, anchor, halfWidth));
	}

	/** Matrix old callers should see when they can only consume one transform. */
	public double[][] legacyMatrix() {
		double[][] right = matrices.get(ChannelOperationSettings.sourceKey(1, false));
		if (right != null) return copy(right);
		for (Map.Entry<String, double[][]> entry : matrices.entrySet())
			if (!entry.getKey().equals(reference)) return copy(entry.getValue());
		return null;
	}

	/**
	 * Save two-source Channel0001 left/right data as the historic bare CSV.  Every other case
	 * is a tagged CSV:
	 * <pre>
	 * # OPM alignment matrix set v1
	 * convention,right-halves-mirrored;each-source-to-reference
	 * reference,_Channel0001-left
	 * source,_Channel0001-left,1,0,0,0,1,0
	 * source,_Channel0002-left,...
	 * </pre>
	 */
	public boolean save(File file) {
		if (file == null) return false;
		if (isClassicPair()) return IO.saveMatrixToFile(
				matrices.get(ChannelOperationSettings.sourceKey(1, false)), file.getAbsolutePath());
		File target = csv(file);
		try {
			BufferedWriter writer = new BufferedWriter(new FileWriter(target, false));
			try {
				writer.write(HEADER); writer.newLine();
				writer.write("convention," + CONVENTION); writer.newLine();
				writer.write("reference," + reference); writer.newLine();
				for (Map.Entry<String, double[][]> entry : matrices.entrySet()) {
					double[][] m = entry.getValue();
					writer.write(String.format(Locale.US,
							"source,%s,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g",
							entry.getKey(), m[0][0], m[0][1], m[0][2],
							m[1][0], m[1][1], m[1][2]));
					writer.newLine();
				}
			} finally { writer.close(); }
			return true;
		} catch (IOException error) {
			System.out.println(error.getMessage());
			return false;
		}
	}

	public static AlignmentMatrixSet load(String path) {
		if (path == null || path.trim().isEmpty()) return null;
		File file = new File(path);
		if (!file.isFile()) return null;
		try {
			BufferedReader reader = new BufferedReader(new FileReader(file));
			try {
				String first = reader.readLine();
				if (first == null) return null;
				if (!first.trim().equals(HEADER)) return loadLegacy(first, reader);
				String reference = null;
				List<String[]> records = new ArrayList<String[]>();
				String line;
				while ((line = reader.readLine()) != null) {
					String[] fields = split(line);
					if (fields.length < 2) continue;
					if ("reference".equalsIgnoreCase(fields[0])) reference = fields[1];
					else if ("source".equalsIgnoreCase(fields[0])) records.add(fields);
				}
				AlignmentMatrixSet set = new AlignmentMatrixSet(reference);
				for (String[] fields : records) {
					if (fields.length != 8) throw new IOException("Invalid alignment source row.");
					double[][] matrix = {
						{ number(fields[2]), number(fields[3]), number(fields[4]) },
						{ number(fields[5]), number(fields[6]), number(fields[7]) }
					};
					set.put(fields[1], matrix);
				}
				return set.reference == null || set.matrices.isEmpty() ? null : set;
			} finally { reader.close(); }
		} catch (Exception error) {
			System.out.println(error.getMessage());
			return null;
		}
	}

	private static AlignmentMatrixSet loadLegacy(String first, BufferedReader reader) throws IOException {
		String second = reader.readLine();
		if (second == null) return null;
		String[] a = split(first), b = split(second);
		if (a.length < 3 || b.length < 3) return null;
		return legacy(new double[][] {
			{ number(a[0]), number(a[1]), number(a[2]) },
			{ number(b[0]), number(b[1]), number(b[2]) }
		});
	}

	private boolean isClassicPair() {
		return matrices.size() == 2
				&& ChannelOperationSettings.sourceKey(1, true).equals(reference)
				&& matrices.containsKey(ChannelOperationSettings.sourceKey(1, false));
	}

	private static File csv(File file) {
		return file.getName().toLowerCase(Locale.ROOT).endsWith(".csv")
				? file : new File(file.getParentFile(), file.getName() + ".csv");
	}

	private static String[] split(String line) {
		String[] fields = line.trim().split("\\s*,\\s*", -1);
		for (int i = 0; i < fields.length; i++) fields[i] = fields[i].trim();
		return fields;
	}

	private static double number(String value) { return Double.parseDouble(value.trim()); }
	private static String clean(String value) {
		if (value == null) return null;
		String clean = value.trim();
		return clean.isEmpty() ? null : clean;
	}

	public static boolean is2d(double[][] matrix) {
		return matrix != null && matrix.length >= 2 && matrix[0] != null && matrix[1] != null
				&& matrix[0].length >= 3 && matrix[1].length >= 3;
	}

	static double[][] identity2d() { return copy(IDENTITY); }

	static double[][] copy(double[][] matrix) {
		if (matrix == null) return null;
		double[][] result = new double[matrix.length][];
		for (int row = 0; row < matrix.length; row++)
			result[row] = matrix[row] == null ? null : matrix[row].clone();
		return result;
	}
}
