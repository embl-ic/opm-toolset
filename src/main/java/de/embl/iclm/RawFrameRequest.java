package de.embl.iclm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import bdv.viewer.Interpolation;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * One frame asked of completed-frame playback, frozen: everything that decides its pixels (joint
 * INTERFACES, {@code RenderRequest}). Two requests with different revisions are different frames
 * even when nothing else differs - the same view at consecutive time points that happen to look
 * alike - which is what lets a presentation be acknowledged for the request it answers and no
 * other.
 */
final class RawFrameRequest {

	/** One displayed part: where it lands and how its values become colour. */
	static final class Layer {
		final RawSourceSpec spec;
		final AffineTransform3D sourceToWorld;
		final double min, max;
		final int argb;

		Layer(RawSourceSpec spec, AffineTransform3D sourceToWorld, double min, double max, int argb) {
			this.spec = spec;
			this.sourceToWorld = sourceToWorld.copy();
			this.min = min;
			this.max = max;
			this.argb = argb;
		}
	}

	final long revision;
	/** BigDataViewer's time index, and the file version the frame is drawn from. */
	final int timeIndex;
	final RawAcquisition.Entry entry;
	final RawAcquisition.FileKey key;
	final RawTiffLayout layout;
	final AffineTransform3D viewer;
	final int width, height;
	final Interpolation interpolation;
	final List<Layer> layers;

	RawFrameRequest(long revision, int timeIndex, RawAcquisition.Entry entry, AffineTransform3D viewer, int width, int height,
			Interpolation interpolation, List<Layer> layers) {
		if (entry.key() == null || entry.layout() == null)
			throw new IllegalArgumentException(entry.file.getName() + " has not been checked as viewable");
		if (width <= 0 || height <= 0) throw new IllegalArgumentException("A frame of " + width + " x " + height + " pixels");
		this.revision = revision;
		this.timeIndex = timeIndex;
		this.entry = entry;
		this.key = entry.key();
		this.layout = entry.layout();
		this.viewer = viewer.copy();
		this.width = width;
		this.height = height;
		this.interpolation = interpolation;
		this.layers = Collections.unmodifiableList(new ArrayList<Layer>(layers));
	}

	/** Raw voxels to screen pixels, for one layer. */
	AffineTransform3D sourceToScreen(Layer layer) {
		AffineTransform3D t = layer.sourceToWorld.copy();
		t.preConcatenate(viewer);
		return t;
	}

	/** The parts as the coverage sees them. */
	List<RawCoverage.Part> coverageParts() {
		List<RawCoverage.Part> parts = new ArrayList<RawCoverage.Part>();
		for (Layer l : layers) parts.add(new RawCoverage.Part(l.spec, sourceToScreen(l)));
		return parts;
	}

	@Override
	public String toString() {
		return String.format(Locale.US, "request %d: time index %d (%s), %d x %d, %s", revision, timeIndex, entry.time, width, height,
				interpolation);
	}
}
