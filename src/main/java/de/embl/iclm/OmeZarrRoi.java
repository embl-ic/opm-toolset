package de.embl.iclm;

import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.GenericDialog;

import java.awt.Button;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.TextField;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.Vector;

/**
 * Fill a region dialog's x/y/width/height from whatever ROI is drawn on the front image.
 *
 * <p>Typing six numbers is the wrong way to say "this bit of the volume". The way it is
 * actually reached is: open a view, look at it, drag a box round something. This turns that
 * box into the region, and is shared by the viewer's own region dialog and the OME-Zarr region
 * export, so both mean the same thing by it.
 *
 * <p>Only the ROI's <em>bounding box</em> is used, so any ROI shape will do. Z is deliberately
 * left alone: the ROI says nothing about depth, and silently resetting the z fields to the
 * whole stack would undo a range the user had just typed.
 *
 * <p>The coordinate question this has to answer is which view the box was drawn on. A view
 * that is already cropped is smaller than the volume and starts at the region's own origin, so
 * a box drawn on it is offset. {@link OmeZarrView} stamps every view it opens with the dataset
 * it came from and the origin it starts at, which is what makes the offset exact rather than
 * guessed from matching dimensions. An image carrying no stamp at all is taken at face value -
 * its numbers are shown in the dialog and clamped before use, so a wrong guess is visible and
 * harmless - but an image stamped for a <em>different</em> dataset is refused, because that is
 * unambiguous evidence the box means something else.
 *
 * <p>The dialogs this serves are modal, so no ROI can be drawn while one is open. The button's
 * enabled state is therefore settled once, when the dialog is built.
 */
final class OmeZarrRoi {

	/** The label the button carries in every dialog that offers it. */
	static final String BUTTON_LABEL = "update region with active ROI";

	/** Property naming the dataset a view was opened from. */
	static final String ROOT_PROPERTY = "opm.zarrRoot";
	/** Property giving "x,y,zStart" of the view's own origin within that dataset. */
	static final String ORIGIN_PROPERTY = "opm.viewOrigin";

	private OmeZarrRoi() {}

	/**			The active image's ROI as a region of the named dataset's full view
	 *
	 * @param root				: the dataset the region is meant for, or null to accept any
	 * <p>
	 * @return					: the XY box in full-view coordinates, or null when the front
	 * 							  image cannot supply a usable one
	 */
	static Rectangle activeRegion(File root) {
		ImagePlus active = WindowManager.getCurrentImage();
		if (active == null || active.getRoi() == null) return null;
		Rectangle box = active.getRoi().getBounds();
		if (box == null || box.width <= 0 || box.height <= 0) return null;

		String stamped = stringProperty(active, ROOT_PROPERTY);
		if (stamped != null && root != null && !stamped.equals(root.getAbsolutePath())) return null;

		int[] origin = viewOrigin(active);
		return new Rectangle(box.x + origin[0], box.y + origin[1], box.width, box.height);
	}

	/**			Add the button to a dialog whose next four numeric fields are x, y, width, height
	 * <p>		Greyed out rather than hidden when there is nothing to take, so the dialog does
	 * 			not change shape depending on what happens to be in front, and the reason is
	 * 			written next to it.
	 *
	 * @param dialog			: the dialog being built, before {@code showDialog()}
	 * @param root				: the dataset the region is meant for
	 * @param firstField		: index into {@code getNumericFields()} of the x field
	 */
	static void addUpdateButton(final GenericDialog dialog, final File root, int firstField) {
		add(dialog, new RootSource() {
			@Override public File root() { return root; }
		}, firstField);
	}

	/**			The same button, where the dataset is typed into one of the dialog's own fields
	 * <p>		The export dialog names its dataset in a text field the user can still edit, so
	 * 			the root has to be read when the button is pressed, not when it was added. The
	 * 			enabled state is still settled at build time, from the field's initial value:
	 * 			that is the dataset the front view almost always came from, and a modal dialog
	 * 			gives no opportunity to draw a different ROI anyway.
	 *
	 * @param rootField			: index into {@code getStringFields()} of the dataset path
	 */
	static void addUpdateButton(final GenericDialog dialog, final int rootField, int firstField) {
		add(dialog, new RootSource() {
			@Override public File root() {
				String typed = text(dialog.getStringFields(), rootField);
				return typed == null || typed.trim().isEmpty() ? null : new File(typed.trim());
			}
		}, firstField);
	}

	/** Where a dialog's dataset comes from: fixed, or a field the user can still edit. */
	private interface RootSource { File root(); }

	private static void add(final GenericDialog dialog, final RootSource source, final int firstField) {
		final Rectangle available = activeRegion(source.root());
		dialog.addButton(BUTTON_LABEL, new ActionListener() {
			@Override public void actionPerformed(ActionEvent event) {
				Rectangle box = activeRegion(source.root());
				if (box == null) return;
				setField(dialog, firstField, box.x);
				setField(dialog, firstField + 1, box.y);
				setField(dialog, firstField + 2, box.width);
				setField(dialog, firstField + 3, box.height);
			}
		});
		if (available == null) {
			disableButton(dialog, BUTTON_LABEL);
			dialog.addMessage("No usable ROI: draw a rectangle on a view of this dataset first.");
		}
	}

	/** The origin a view starts at, or {0,0,0} for an unstamped image. */
	private static int[] viewOrigin(ImagePlus image) {
		int[] origin = { 0, 0, 0 };
		String stamped = stringProperty(image, ORIGIN_PROPERTY);
		if (stamped == null) return origin;
		String[] parts = stamped.split(",");
		for (int i = 0; i < origin.length && i < parts.length; i++) {
			try { origin[i] = Integer.parseInt(parts[i].trim()); }
			catch (NumberFormatException malformed) { return new int[] { 0, 0, 0 }; }
		}
		return origin;
	}

	private static String stringProperty(ImagePlus image, String key) {
		Object value = image.getProperty(key);
		return value instanceof String ? (String) value : null;
	}

	private static void setField(GenericDialog dialog, int index, int value) {
		Vector<?> fields = dialog.getNumericFields();
		if (fields == null || index < 0 || index >= fields.size()) return;
		Object field = fields.get(index);
		if (field instanceof TextField) ((TextField) field).setText(String.valueOf(value));
	}

	private static String text(Vector<?> fields, int index) {
		if (fields == null || index < 0 || index >= fields.size()) return null;
		Object field = fields.get(index);
		return field instanceof TextField ? ((TextField) field).getText() : null;
	}

	/**
	 * Grey out the button just added.
	 * <p>
	 * {@link GenericDialog#addButton} returns nothing, so the component is found by its label.
	 * It is the only {@link Button} on the dialog until {@code showDialog()} adds OK and
	 * Cancel. Should a future ImageJ hold it somewhere else the button simply stays enabled,
	 * and pressing it does nothing, which is a great deal better than failing to open at all.
	 */
	private static void disableButton(GenericDialog dialog, String label) {
		for (Component component : dialog.getComponents())
			if (component instanceof Button && label.equals(((Button) component).getLabel()))
				component.setEnabled(false);
	}
}
