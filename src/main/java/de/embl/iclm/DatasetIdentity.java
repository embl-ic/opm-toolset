package de.embl.iclm;

import java.io.File;
import java.nio.file.Path;

/** A store, or one TIFF series within a result folder, independent of the scan selection. */
final class DatasetIdentity {

	final File root;
	private final Path path;
	final String series;

	private DatasetIdentity(File root, String series) {
		this.path = root.getAbsoluteFile().toPath().normalize();
		this.root = path.toFile();
		this.series = series;
	}

	static DatasetIdentity zarr(File root) { return new DatasetIdentity(root, null); }
	static DatasetIdentity tiff(File root, String series) { return new DatasetIdentity(root, series); }

	@Override public boolean equals(Object other) {
		if (!(other instanceof DatasetIdentity)) return false;
		DatasetIdentity that = (DatasetIdentity) other;
		return path.equals(that.path) && (series == null ? that.series == null : series.equals(that.series));
	}

	@Override public int hashCode() { return 31 * path.hashCode() + (series == null ? 0 : series.hashCode()); }

	/** Also carried on TIFF images, including crops, for ROI source validation. */
	@Override public String toString() { return path + (series == null ? "" : "|TIFF|" + series); }
}
