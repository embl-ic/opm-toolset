package de.embl.iclm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

	/** A selected acquisition file, with its generation and layout from one publication. */
	static final class Input {
		final RawAcquisition.Entry entry;
		final RawAcquisition.Entry.Shown publication;
		final RawAcquisition.FileKey key;
		final RawTiffLayout layout;

		Input(RawAcquisition.Entry entry, RawAcquisition.Entry.Shown publication) {
			if (entry == null || publication == null) throw new IllegalArgumentException("A selected file is not shown now");
			this.entry = entry;
			this.publication = publication;
			this.key = publication.key;
			this.layout = publication.layout;
		}
	}

	final List<Input> inputs;
	final RawAcquisition.TimeKey time;
	final Set<RawAcquisition.FileKey> keys;
	final List<RawAcquisition.Entry.Shown> publications;
	final long revision;
	/** BigDataViewer's time index, and the file version the frame is drawn from. */
	final int timeIndex;
	final RawAcquisition.Entry entry;
	/**
	 * The publication the frame is drawn from: its version and layout, taken together (M3-A-F1). Its identity is what the
	 * presentation gate refuses once the catalogue takes it away (M3-A-F2).
	 */
	final RawAcquisition.Entry.Shown publication;
	final RawAcquisition.FileKey key;
	final RawTiffLayout layout;
	final AffineTransform3D viewer;
	final int width, height;
	final Interpolation interpolation;
	final List<Layer> layers;

	/** A frame of the publication {@code entry} shows now, taken in one read; refused when it shows none. */
	RawFrameRequest(long revision, int timeIndex, RawAcquisition.Entry entry, AffineTransform3D viewer, int width, int height,
			Interpolation interpolation, List<Layer> layers) {
		this(revision, timeIndex, entry, entry.shown(), viewer, width, height, interpolation, layers);
	}

	/**
	 * A frame of {@code publication}, one the caller took from {@code entry} once and placed its layers by: version, layout
	 * and layers then all come from one check (M3-A-F1). A request either has one or is refused, never half of one.
	 */
	RawFrameRequest(long revision, int timeIndex, RawAcquisition.Entry entry, RawAcquisition.Entry.Shown publication,
			AffineTransform3D viewer, int width, int height, Interpolation interpolation, List<Layer> layers) {
		this(revision, timeIndex, Collections.singletonList(new Input(entry, publication)), viewer, width, height, interpolation, layers);
	}

	RawFrameRequest(long revision, int timeIndex, List<Input> inputs, AffineTransform3D viewer, int width, int height,
			Interpolation interpolation, List<Layer> layers) {
		if (inputs.isEmpty()) throw new IllegalArgumentException("A frame needs at least one acquisition file");
		this.inputs = Collections.unmodifiableList(new ArrayList<Input>(inputs));
		this.time = inputs.get(0).entry.time;
		Set<Integer> channels = new HashSet<Integer>();
		Set<RawAcquisition.FileKey> keys = new HashSet<RawAcquisition.FileKey>();
		List<RawAcquisition.Entry.Shown> publications = new ArrayList<RawAcquisition.Entry.Shown>();
		for (Input input : inputs) {
			if (!time.equals(input.entry.time)) throw new IllegalArgumentException("A frame cannot mix acquisition time points");
			if (!channels.add(input.entry.channel)) throw new IllegalArgumentException("Duplicate acquisition channel in a frame");
			keys.add(input.key);
			publications.add(input.publication);
		}
		this.keys = Collections.unmodifiableSet(keys);
		this.publications = Collections.unmodifiableList(publications);
		for (Layer layer : layers) input(layer); // no layer may silently sample another file
		// The first-file aliases keep the original single-file callers source compatible.
		RawAcquisition.Entry entry = inputs.get(0).entry;
		RawAcquisition.Entry.Shown publication = inputs.get(0).publication;
		if (width <= 0 || height <= 0) throw new IllegalArgumentException("A frame of " + width + " x " + height + " pixels");
		this.revision = revision;
		this.timeIndex = timeIndex;
		this.entry = entry;
		this.publication = publication;
		this.key = publication.key;
		this.layout = publication.layout;
		this.viewer = viewer.copy();
		this.width = width;
		this.height = height;
		this.interpolation = interpolation;
		this.layers = Collections.unmodifiableList(new ArrayList<Layer>(layers));
	}

	Input input(Layer layer) {
		for (Input input : inputs) if (input.entry.channel == layer.spec.acquisitionChannel) return input;
		throw new IllegalArgumentException("No selected file for " + layer.spec.key);
	}

	boolean revoked(Set<RawAcquisition.Entry.Shown> revoked) {
		for (RawAcquisition.Entry.Shown p : publications) if (revoked.contains(p)) return true;
		return false;
	}

	boolean withdrawn(Set<RawAcquisition.FileKey> withdrawn) {
		for (RawAcquisition.FileKey k : keys) if (withdrawn.contains(k)) return true;
		return false;
	}

	List<RawCoverage.Part> coverageParts(Input input) {
		List<RawCoverage.Part> parts = new ArrayList<RawCoverage.Part>();
		for (Layer l : layers)
			if (l.spec.acquisitionChannel == input.entry.channel) parts.add(new RawCoverage.Part(l.spec, sourceToScreen(l)));
		return parts;
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
