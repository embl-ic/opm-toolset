package de.embl.iclm;

import java.awt.Graphics;
import java.awt.Insets;

import fiji.util.gui.GenericDialogPlus;

/**		The {@link GenericDialogPlus} every modal OPM dialog is built from
 * <p>	The modal half of the pair: every OPM dialog that has to stay modal is one of these
 * <br>	rather than a bare {@code GenericDialogPlus}, and {@code CommandCoverageTest} fails if
 * <br>	one is not.
 *
 * <p>	Identical to {@link OpmDialog} but for the class it extends, and for the same reason:
 * <br>	an AWT dialog paints its own background in {@code paint} and offers nothing else to hang
 * <br>	a decoration off. The behaviour is all in {@code Debug.Theme.DialogRim}; read the notes
 * <br>	on {@link OpmDialog}.
 *
 * @author ziqiang.huang@embl.de
 */
public class OpmDialogPlus extends GenericDialogPlus {

	private static final long serialVersionUID = 1L;

	private final Debug.Theme.DialogRim rim = new Debug.Theme.DialogRim ( this );

	public OpmDialogPlus (String title) {
		super ( title );
		Debug.Theme.register ( rim );
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

	/** Do not clear before painting while the margin is animating; see {@link OpmDialog}. */
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
