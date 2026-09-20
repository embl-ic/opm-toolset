package de.embl.iclm;

import java.awt.Graphics;
import java.awt.Insets;

import ij.gui.NonBlockingGenericDialog;

/**		A {@link NonBlockingGenericDialog} that can wear the party theme
 * <p>	Every non-blocking OPM dialog is one of these rather than a bare
 * <br>	{@code NonBlockingGenericDialog}, and {@code PartyCoverageTest} fails if one is not.
 * <br>	It is still a {@code NonBlockingGenericDialog} in every way that matters - the batch
 * <br>	dialog has to be one, so that a long run can be started without the modal dialog
 * <br>	holding the event thread - and when the theme is off it is indistinguishable from one.
 *
 * <p>	<b>The subclass exists because an AWT dialog fills its own background and nothing else
 * <br>	does.</b> There is no border, no glass pane and no root pane to hang a decoration off;
 * <br>	the only way in is {@code paint}. {@link Party.DialogRim} holds all of it, so this class
 * <br>	and {@link PartyDialogPlus} cannot drift apart.
 *
 * <p>	{@code update} is overridden for the same reason the demo script overrides it: a
 * <br>	heavyweight window clears itself before {@code paint}, and the rim repaints its margin
 * <br>	twenty times a second, so clearing first would flicker the whole dialog.
 *
 * @author ziqiang.huang@embl.de
 */
public class PartyDialog extends NonBlockingGenericDialog {

	private static final long serialVersionUID = 1L;

	private final Party.DialogRim rim = new Party.DialogRim ( this );

	public PartyDialog (String title) {
		super ( title );
		Party.register ( rim );
	}

	/** Room for the tube, on top of the 10 px {@code GenericDialog} already leaves. */
	@Override
	public Insets getInsets () {
		return rim.pad ( super.getInsets() );
	}

	@Override
	public void paint (Graphics g) {
		super.paint ( g );
		rim.paint ( g, super.getInsets() );
	}

	/** Do not clear before painting while the rim is animating; see the class comment. */
	@Override
	public void update (Graphics g) {
		if (rim.on()) paint ( g );
		else super.update ( g );
	}

	@Override
	public void dispose () {
		Party.unregister ( rim );
		super.dispose();
	}
}
