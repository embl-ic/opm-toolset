package de.embl.iclm;

import java.awt.Graphics;
import java.awt.Insets;

import fiji.util.gui.GenericDialogPlus;

/**		A {@link GenericDialogPlus} that can wear the party theme
 * <p>	The modal half of the pair: every OPM dialog that uses {@code addDirectoryField},
 * <br>	{@code addFileField} or {@code addImageChoice} is one of these rather than a bare
 * <br>	{@code GenericDialogPlus}, and {@code PartyCoverageTest} fails if one is not.
 *
 * <p>	Identical to {@link PartyDialog} but for the class it extends, and for the same reason:
 * <br>	an AWT dialog paints its own background in {@code paint} and offers nothing else to hang
 * <br>	a decoration off. The behaviour is all in {@link Party.DialogRim}; read the notes on
 * <br>	{@link PartyDialog}.
 *
 * @author ziqiang.huang@embl.de
 */
public class PartyDialogPlus extends GenericDialogPlus {

	private static final long serialVersionUID = 1L;

	private final Party.DialogRim rim = new Party.DialogRim ( this );

	public PartyDialogPlus (String title) {
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

	/** Do not clear before painting while the rim is animating; see {@link PartyDialog}. */
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
