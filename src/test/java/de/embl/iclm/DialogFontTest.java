package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Font;

import javax.swing.JLabel;
import javax.swing.JPanel;

import org.junit.Test;

import ij.Prefs;

/**
 * The Deskew Batch dialog and the Live Deskew setup are one design, and that includes what
 * they are drawn with.
 *
 * <p>The two are built from different toolkits - a {@code GenericDialog} of AWT widgets and a
 * Swing dialog - and an AWT widget with no font of its own falls back to the toolkit default
 * rather than to anything the desktop configured. On Linux that is Dialog plain 12, drawn by
 * Java2D, which beside the look and feel's font is both smaller and a different face. Taking
 * both fonts from {@link Parameter#dialogFont()} is what keeps them the same.
 */
public class DialogFontTest {

	/** The shared font is the one Swing hands the Live setup, at ImageJ's GUI scale. */
	@Test
	public void theDialogFontIsTheOneSwingGivesTheLiveSetup () {
		Font swing = new JLabel().getFont();
		double scale = Prefs.getGuiScale();
		Font expected = scale == 1.0d
				? swing : swing.deriveFont ( (float) ( swing.getSize() * scale ) );

		Font shared = Parameter.dialogFont();
		assertEquals ( "family", expected.getFamily(), shared.getFamily() );
		assertEquals ( "size", expected.getSize(), shared.getSize() );
		/* Including the weight the look and feel asked for: Metal's Label.font is bold, and a
		 * batch dialog forced plain under it would not match the Live setup beside it. */
		assertEquals ( "style", expected.getStyle(), shared.getStyle() );
	}

	/** Section headings differ from the body in weight only, in both dialogs. */
	@Test
	public void theSectionFontIsTheDialogFontInBold () {
		Font body = Parameter.dialogFont();
		Font heading = Parameter.sectionFont();
		assertEquals ( body.getFamily(), heading.getFamily() );
		assertEquals ( body.getSize(), heading.getSize() );
		assertTrue ( "the heading is bold", heading.isBold() );
		assertEquals ( "italic is not dropped on the way", body.isItalic(), heading.isItalic() );
	}

	/**
	 * Applying the font to a built Swing form must not flatten it.
	 *
	 * <p>The look and feel has already given every component a font, so the Live setup can only
	 * follow the GUI scale by having the font set on each of them - after its headings have been
	 * made bold. Carrying each component's own style across is what keeps them bold.
	 */
	@Test
	public void applyingTheFontKeepsEachComponentsOwnStyle () {
		JLabel body = new JLabel ( "save to" );
		body.setFont ( new Font ( "Dialog", Font.PLAIN, 12 ) );
		JLabel heading = new JLabel ( "Output setup:" );
		heading.setFont ( new Font ( "Dialog", Font.BOLD, 12 ) );
		JPanel inner = new JPanel();
		inner.add ( body );
		JPanel form = new JPanel();
		form.add ( heading );
		form.add ( inner );

		Font applied = new Font ( "SansSerif", Font.PLAIN, 20 );
		Parameter.applyFont ( form, applied );

		assertEquals ( 20, heading.getFont().getSize() );
		assertEquals ( 20, body.getFont().getSize() );
		assertTrue ( "the heading stays bold", heading.getFont().isBold() );
		assertTrue ( "the body stays plain", !body.getFont().isBold() );
	}
}
