package de.embl.iclm;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import bdv.tools.brightness.ConverterSetup;
import bdv.ui.convertersetupeditor.ConverterSetupEditPanel;
import bdv.ui.sourcegrouptree.SourceGroupTree;
import bdv.ui.sourcetable.SourceTable;
import bdv.util.BdvHandle;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;

/** Per-source Fiji-style Auto, sampled from the current screen plane without counting outside pixels. */
final class OnlineDeskewAutoContrast {
	static final int MAX_SAMPLES = 250000;
	private final BdvHandle handle;
	private final BooleanSupplier closing;
	private final Consumer<String> note;
	/** Converter setups survive position switches; all access to their cycles is on Swing. */
	private final Map<ConverterSetup, Cycle> cycles = new IdentityHashMap<ConverterSetup, Cycle>();

	OnlineDeskewAutoContrast(BdvHandle handle, BooleanSupplier closing, Consumer<String> note) {
		this.handle = handle; this.closing = closing; this.note = note;
		int installed = install(handle.getCardPanel().getComponent());
		if (installed != 2) System.out.println("Online Deskew View: Auto B/C installed on " + installed
				+ " of 2 source/group rows; the other row structure was not found.");
	}

	private int install(Component component) {
		if (component instanceof ConverterSetupEditPanel) {
			ConverterSetupEditPanel row = (ConverterSetupEditPanel) component;
			SourceTable table = find(row.getParent(), SourceTable.class);
			SourceGroupTree tree = find(row.getParent(), SourceGroupTree.class);
			if (table == null && tree == null) return 0;
			Supplier<List<ConverterSetup>> selected = table != null ? table::getSelectedConverterSetups
					: () -> handle.getConverterSetups().getConverterSetups(tree.getSelectedSources());
			JButton auto = new JButton("Auto");
			auto.setName("opm.online.auto");
			auto.setFocusable(false);
			auto.setToolTipText("Auto brightness/contrast from this source's pixels in the current screen plane. Click again to tighten, then reset.");
			boolean[] busy = { false };
			Runnable enable = () -> auto.setEnabled(!closing.getAsBoolean() && !busy[0] && !selected.get().isEmpty());
			if (table != null) table.getSelectionModel().addListSelectionListener(e -> enable.run());
			else tree.getSelectionModel().addTreeSelectionListener(e -> enable.run());
			auto.addActionListener(e -> {
				busy[0] = true; enable.run();
				adjust(selected.get(), () -> { busy[0] = false; enable.run(); });
			});
			row.add(auto, "growy");
			enable.run();
			return 1;
		}
		int count = 0;
		if (component instanceof Container) for (Component child : ((Container) component).getComponents()) count += install(child);
		return count;
	}

	static <T> T find(Component component, Class<T> type) {
		if (type.isInstance(component)) return type.cast(component);
		if (component instanceof Container) for (Component child : ((Container) component).getComponents()) {
			T found = find(child, type); if (found != null) return found;
		}
		return null;
	}

	private void adjust(List<ConverterSetup> selected, Runnable finished) {
		AffineTransform3D view = new AffineTransform3D();
		handle.getViewerPanel().state().getViewerTransform(view);
		int width = handle.getViewerPanel().getDisplay().getWidth();
		int height = handle.getViewerPanel().getDisplay().getHeight();
		int time = handle.getViewerPanel().state().getCurrentTimepoint();
		List<Sample> requests = new ArrayList<Sample>();
		for (SourceAndConverter<?> soc : handle.getViewerPanel().state().getSources()) {
			ConverterSetup setup = handle.getConverterSetups().getConverterSetup(soc);
			if (selected.contains(setup)) {
				Source<?> source = soc.getSpimSource();
				if (source instanceof RawPartSource) source = ((RawPartSource<?>) source).samplingSource();
				requests.add(new Sample(setup, source));
			}
		}
		Shutdown.daemon(() -> {
			String message = "";
			try {
				for (Sample request : requests) {
					if (closing.getAsBoolean()) break;
					request.pixels = sample(request.source, time, view, width, height, closing);
				}
			} catch (RuntimeException failed) { message = "Auto B/C could not read the view: " + failed.getMessage(); }
			final String error = message;
			SwingUtilities.invokeLater(() -> {
				try {
					if (closing.getAsBoolean()) return;
					String status = error;
					for (Sample request : requests) {
						if (request.pixels == null) continue;
						if (request.pixels.length == 0) { status = "Auto B/C: no pixels of " + request.source.getName() + " in the current view."; continue; }
						Cycle cycle = cycles.get(request.setup);
						if (cycle == null) { cycle = new Cycle(); cycles.put(request.setup, cycle); }
						Range range = cycle.adjust(request.pixels, request.setup.getDisplayRangeMin(), request.setup.getDisplayRangeMax());
						request.setup.setDisplayRange(range.min, range.max);
					}
					note.accept(status);
				} finally { finished.run(); }
			});
		}, "OPM-online-auto-contrast").start();
	}

	private static final class Sample {
		final ConverterSetup setup;
		final Source<?> source;
		int[] pixels;
		Sample(ConverterSetup setup, Source<?> source) { this.setup = setup; this.source = source; }
	}

	/** Round screen points into the blocking source, discarding points outside its interval. */
	static int[] sample(Source<?> source, int time, AffineTransform3D view, int width, int height, BooleanSupplier closing) {
		if (width <= 0 || height <= 0 || !source.isPresent(time)) return new int[0];
		AffineTransform3D sourceToWorld = new AffineTransform3D();
		source.getSourceTransform(time, 0, sourceToWorld);
		AffineTransform3D screenToSource = view.copy().concatenate(sourceToWorld).inverse();
		RandomAccessibleInterval<?> image = source.getSource(time, 0);
		RandomAccess<?> access = image.randomAccess();
		int stride = Math.max(1, (int) Math.ceil(Math.sqrt((double) width * height / MAX_SAMPLES)));
		int[] values = new int[((width + stride - 1) / stride) * ((height + stride - 1) / stride)];
		double[] screen = new double[3], voxel = new double[3];
		long[] point = new long[3];
		int count = 0;
		for (int y = 0; y < height; y += stride) {
			if (closing.getAsBoolean() || Shutdown.stopping()) return new int[0];
			screen[1] = y;
			for (int x = 0; x < width; x += stride) {
				screen[0] = x; screenToSource.apply(screen, voxel);
				boolean inside = true;
				for (int d = 0; d < 3; d++) {
					point[d] = Math.round(voxel[d]);
					inside &= point[d] >= image.min(d) && point[d] <= image.max(d);
				}
				if (!inside) continue;
				access.setPosition(point);
				values[count++] = (int) ((RealType<?>) access.get()).getRealDouble();
			}
		}
		return Arrays.copyOf(values, count);
	}

	static final class Range {
		final double min, max;
		final int stage;
		Range(double min, double max, int stage) { this.min = min; this.max = max; this.stage = stage; }
	}

	/** The 16-bit algorithm from ImageJ ContrastAdjuster.autoAdjust, independent of windows. */
	static Range autoRange(int[] pixels, int previousStage) {
		if (pixels.length == 0) throw new IllegalArgumentException("Auto needs pixels");
		int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
		for (int value : pixels) { min = Math.min(min, value); max = Math.max(max, value); }
		int[] histogram = new int[256];
		double scale = max == min ? 0 : 256.0 / (max - min);
		for (int value : pixels) histogram[Math.min(255, (int) ((value - min) * scale))]++;
		int stage = nextStage(previousStage);
		int threshold = pixels.length / stage, limit = pixels.length / 10;
		int low = 0, high = 255;
		while (low < 255 && !(histogram[low] <= limit && histogram[low] > threshold)) low++;
		while (high > 0 && !(histogram[high] <= limit && histogram[high] > threshold)) high--;
		if (high < low) return new Range(min, max == min ? min + 1 : max, 0);
		double binSize = (max - min) / 256.0;
		double bottom = min + low * binSize, top = min + high * binSize;
		if (bottom == top) { bottom = min; top = max == min ? min + 1 : max; }
		return new Range(bottom, top, stage);
	}

	static int nextStage(int stage) { return stage < 10 ? 5000 : stage / 2; }

	/** A hand edit since the last Auto resets this source's sequence. */
	static final class Cycle {
		private Range last;
		Range adjust(int[] pixels, double currentMin, double currentMax) {
			int previous = last == null || last.min != currentMin || last.max != currentMax ? 0 : last.stage;
			last = autoRange(pixels, previous);
			return last;
		}
	}
}
