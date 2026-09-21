package de.embl.iclm;

import java.awt.Graphics;
import java.awt.Insets;

import ij.gui.NonBlockingGenericDialog;

/**		The {@link NonBlockingGenericDialog} every non-blocking OPM dialog is built from
 * <p>	Every non-blocking OPM dialog is one of these rather than a bare
 * <br>	{@code NonBlockingGenericDialog}, and {@code CommandCoverageTest} fails if one is not.
 * <br>	It is still a {@code NonBlockingGenericDialog} in every way that matters - the batch
 * <br>	dialog has to be one, so that a long run can be started without a modal dialog holding
 * <br>	the event thread.
 *
 * <p>	<b>The subclass exists because an AWT dialog fills its own background and nothing else
 * <br>	does.</b> There is no border, no glass pane and no root pane to hang a decoration off;
 * <br>	the only way in is {@code paint}. {@code Debug.Theme.DialogRim} holds all of it, so this
 * <br>	class and {@link OpmDialogPlus} cannot drift apart. It also keeps a margin round the
 * <br>	content, a little wider than {@code GenericDialog}'s own.
 *
 * <p>	{@code update} is overridden not to clear before {@code paint}: a heavyweight window
 * <br>	clears itself first, and the margin can be repainted many times a second, so clearing
 * <br>	would flicker the whole dialog.
 *
 * <p>	Its section headings fold ({@link SectionFolds}): {@code setup()}, which
 * <br>	{@code showDialog()} calls once the dialog is packed and before it is shown, installs
 * <br>	them, and every later {@code pack()} refits the dialog to its screen.
 *
 * @author ziqiang.huang@embl.de
 */
public class OpmDialog extends NonBlockingGenericDialog {

	private static final long serialVersionUID = 1L;

	private final Debug.Theme.DialogRim rim = new Debug.Theme.DialogRim ( this );
	private SectionFolds folds;

	public OpmDialog (String title) {
		super ( title );
		Debug.Theme.register ( rim );
	}

	/** Turn the section headings into folds, now that the dialog is built and packed. */
	@Override
	protected void setup () {
		super.setup();
		folds = SectionFolds.install ( this );
	}

	@Override
	public void pack () {
		super.pack();
		if (folds != null) folds.packed();
	}

	/** The folds of this dialog, or null before it is shown or when it has no headings. */
	SectionFolds folds () {
		return folds;
	}

	/** The margin, on top of the 10 px {@code GenericDialog} already leaves. */
	@Override
	public Insets getInsets () {
		return rim.pad ( super.getInsets() );
	}

	@Override
	public void paint (Graphics g) {
		super.paint ( g );
		rim.paint ( g, super.getInsets() );
	}

	/** Do not clear before painting while the margin is animating; see the class comment. */
	@Override
	public void update (Graphics g) {
		if (rim.on()) paint ( g );
		else super.update ( g );
	}

	@Override
	public void dispose () {
		Debug.Theme.unregister ( rim );
		super.dispose();
	}
}
