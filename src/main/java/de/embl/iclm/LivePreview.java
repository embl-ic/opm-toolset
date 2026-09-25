package de.embl.iclm;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The acquisition, on screen, while it is still being acquired.
 *
 * <p>This no longer opens or grows anything itself. It raises the OPM Data Viewer, points it
 * at the output, and asks it for the views the run's dialog selected; the viewer's own live
 * update then follows the run. The preview used to be a second implementation of exactly that
 * - it opened the views the viewer opens and called the same growth routine - and keeping the
 * two in step meant making every fix twice. One window now owns the views, which also gives
 * the user the region, ROI-materialise and channel controls over a run in progress, which a
 * bare preview window never had.
 *
 * <p>Always virtual for the volume, deliberately: a preview that materialised its pixels would
 * compete for RAM with the deskew producing them and would stop following the run.
 *
 * <p>Which format is previewed follows the output format rather than what happens to be on
 * disk. A run writing OME-Zarr is previewed from the store, whether or not it also writes
 * TIFF, because the store commits one time point at a time and is the thing designed to be
 * read while it is written. A TIFF-only run is previewed from its result folders, where
 * {@link TiffCompletionCheck} is what keeps a half-written file out of the view.
 *
 * <p>An OME-Zarr preview is composed the way the run composes its result - a whole-image run
 * side by side, a fold flipped, a combined run in its slot order - by handing the viewer a
 * {@link DeskewChannelView}. The store itself only ever holds unflipped halves.
 *
 * <p>Windows are left open when the run stops. A preview is a window the user was watching,
 * not a resource the listener owns. They belong to the viewer, and close with it.
 */
public final class LivePreview {

	/** Preferred projection to preview, in order; the first the dataset actually has wins. */
	private static final String[] PREFERRED_PROJECTIONS = { "maxZ", "maxY", "maxX" };
	/** What an older caller that names no projections gets: the one that used to be all there was. */
	private static final List<String> MAXZ_ONLY = java.util.Collections.singletonList("maxZ");

	private final boolean showProjection;
	private final boolean showVolume;
	/**
	 * Open the projection movie as a virtual stack, which is the only kind that can grow.
	 * <p>
	 * A materialised movie loads every C/T plane before the window opens and then stays as it
	 * was, so it simply stops following the run. Offered because it is occasionally what is
	 * wanted - a fixed snapshot to measure on - but the volume has no such option, since a 5-D
	 * view of an acquisition in progress has to read planes as it needs them.
	 */
	private final boolean projectionVirtual;
	/** A TIFF-only run has no store to read, so its preview reads the result folders instead. */
	private final boolean preferTiff;
	/**
	 * How the run composes its channels, so a preview from the store shows the run's result
	 * rather than the store's unflipped halves. Null leaves the viewer to the dataset's own
	 * recorded layout; a TIFF preview ignores it, having its composition in its pixels.
	 */
	private DeskewChannelView channelView;
	/**
	 * The projections the run writes, one preview window each, in the order they open.
	 * <p>
	 * It used to be one window, maxZ, whatever the run was set to project: maxZ is the view the
	 * XY alignment carries exactly, and was taken to be the one worth watching. But a run set to
	 * project along X and Y as well writes those too, and a preview showing one of three looks
	 * like a run that wrote one of three.
	 */
	private final List<String> projections;
	/** One entry per output root this run is writing; recursive watching can produce several. */
	private final Set<String> arranged = new LinkedHashSet<String>();

	/**			Prepare a preview for one live run
	 *
	 * @param showProjection	: open the projection movie
	 * @param showVolume		: open the 5-D volume
	 * @param projectionVirtual	: open the projection movie virtually, so it can grow
	 * @param preferTiff		: preview the TIFF results, for a run that writes no OME-Zarr
	 */
	public LivePreview(boolean showProjection, boolean showVolume, boolean projectionVirtual,
			boolean preferTiff) {
		this(showProjection, showVolume, projectionVirtual, preferTiff, null, MAXZ_ONLY);
	}

	/**			Prepare a preview for one live run, shown the way the run composes its channels
	 *
	 * @param channelView		: the run's channel layout in the viewer's terms, or null
	 */
	public LivePreview(boolean showProjection, boolean showVolume, boolean projectionVirtual,
			boolean preferTiff, DeskewChannelView channelView, List<String> projections) {
		this.showProjection = showProjection;
		this.showVolume = showVolume;
		this.projectionVirtual = projectionVirtual;
		this.preferTiff = preferTiff;
		this.channelView = channelView;
		this.projections = projections == null || projections.isEmpty()
				? MAXZ_ONLY : new java.util.ArrayList<String>(projections);
	}

	/**			The preview a Live or Batch run with these settings asks for
	 *
	 * @param preferTiff		: preview the TIFF results, for a run that writes no OME-Zarr
	 */
	static LivePreview of(Parameter parameter, ChannelOperationSettings channels, boolean preferTiff) {
		return new LivePreview(parameter.livePreviewProjection, parameter.livePreviewVolume,
				parameter.previewVirtual, preferTiff, DeskewChannelView.of(parameter, channels),
				wantedProjections(parameter.projX, parameter.projY, parameter.projZ,
						parameter.maxProj, parameter.avgProj));
	}

	/**			The projection keys a run set to these axes and types writes
	 * <p>		Z first, then Y, then X, maximum before mean. Nothing ticked still previews
	 * 			something: no axis means Z and no type means maximum.
	 */
	static List<String> wantedProjections(boolean x, boolean y, boolean z, boolean max, boolean mean) {
		if (!x && !y && !z) z = true;
		if (!max && !mean) max = true;
		List<String> wanted = new java.util.ArrayList<String>();
		boolean[] axes = { z, y, x };
		String[] names = { "Z", "Y", "X" };
		for (int i = 0; i < axes.length; i++) {
			if (!axes[i]) continue;
			if (max) wanted.add("max" + names[i]);
			if (mean) wanted.add("mean" + names[i]);
		}
		return wanted;
	}

	public boolean wanted() {
		return showProjection || showVolume;
	}

	/**			Make sure the viewer is showing this output, once per root
	 * <p>		Called after every committed time point, but only the first call for a given
	 * 			root does anything: from then on the viewer's own live update is what follows
	 * 			the run. Re-arranging on every commit would re-scan the folder and re-open the
	 * 			windows underneath the user.
	 *
	 * @param root				: the OME-Zarr root, or the folder holding the TIFF results
	 */
	public void update(File root) {
		if (!wanted() || root == null) return;
		if (!arranged.add(key(root))) return;
		OpmDataViewer.showLive(root, preferTiff, showProjection, showVolume, projectionVirtual,
				channelView, projections);
	}

	/**			Re-state how the run composes its channels, once the run has decided
	 * <p>		Live reads a multi-file acquisition's layout off its file names, which it cannot
	 * 			have done when Start built this preview. The preview is only ever raised once a
	 * 			result is on disk - always after that decision - so replacing the view here is
	 * 			what makes the first window open composed the way the run writes, rather than
	 * 			the way the previous run was configured.
	 *
	 * @param view				: the run's channel layout in the viewer's terms, or null
	 */
	public void composeAs(DeskewChannelView view) {
		channelView = view;
	}

	/** Stop tracking, without closing the windows the user is watching. */
	public void close() {
		arranged.clear();
	}

	/** The first preferred projection this dataset actually carries, else whatever it has. */
	static String choose(List<String> available) {
		if (available == null || available.isEmpty()) return null;
		for (String preferred : PREFERRED_PROJECTIONS)
			for (String candidate : available)
				if (preferred.equalsIgnoreCase(candidate)) return candidate;
		return available.get(0);
	}

	/**			The wanted projections this dataset carries, in wanted order, as it names them
	 * <p>		An OME-Zarr names a mean projection {@code meanZ}; a TIFF result keeps the
	 * 			{@code avgZ} of its file names. Either answers for a wanted mean. When none of the
	 * 			wanted ones is there - a TIFF run that wrote other axes than the ticks now say -
	 * 			the preview still shows the one projection it would always have shown.
	 */
	static List<String> choose(List<String> available, List<String> wanted) {
		List<String> chosen = new java.util.ArrayList<String>();
		if (available == null || available.isEmpty()) return chosen;
		for (String key : wanted == null ? MAXZ_ONLY : wanted) {
			for (String candidate : available) {
				if (chosen.contains(candidate)) continue;
				boolean mean = key.startsWith("mean")
						&& candidate.equalsIgnoreCase("avg" + key.substring("mean".length()));
				if (candidate.equalsIgnoreCase(key) || mean) {
					chosen.add(candidate);
					break;
				}
			}
		}
		if (chosen.isEmpty()) chosen.add(choose(available));
		return chosen;
	}

	private static String key(File root) {
		try {
			return root.getCanonicalPath().toLowerCase(Locale.ROOT);
		} catch (IOException unresolvable) {
			return root.getAbsolutePath().toLowerCase(Locale.ROOT);
		}
	}
}
