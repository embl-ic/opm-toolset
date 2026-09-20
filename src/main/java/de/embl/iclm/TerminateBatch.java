package de.embl.iclm;

import ij.IJ;
import ij.plugin.PlugIn;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;

/**
 * Stop a batch run that is already going, without killing Fiji.
 *
 * <p>Until this existed there was no way out of a long run except waiting for it, quitting
 * Fiji, or ending the process. The last two are the dangerous ones: they cut the writers off
 * wherever they happen to be, which is how a half-written chunk, a truncated TIFF or a time
 * point that is committed in the metadata but missing on disk gets made.
 *
 * <p>What this does instead is ask. {@link Shutdown#cancel} raises the run's own flag and
 * interrupts its worker; the run unwinds at its next {@link Shutdown#stopping()} checkpoint,
 * which sits <em>between</em> units of work - after the current plane, chunk or time point has
 * been finished and closed. So a terminated run leaves the same on-disk state a completed one
 * would have left had it been asked for fewer time points, which is exactly what makes the
 * resume path able to pick it up again.
 *
 * <p>Per run, not global. {@link Shutdown#request()} stops everything in the JVM, which is the
 * wrong answer when a Deskew Batch and a format conversion are both in flight and only one of
 * them is the mistake.
 */
public class TerminateBatch implements PlugIn {

	private static final String TITLE = "Running Batch Processing(s)";

	@Override
	public void run (String arg) {
		Party.commandStarted ( "Batch Processing > Terminate..." );
		List<Shutdown.Operation> running = Shutdown.running();
		if (running.isEmpty()) {
			IJ.showMessage ( TITLE, "No batch processing is running." );
			return;
		}
		new Dialog ( running ).setVisible ( true );
	}

	/** Modal, because the choice it asks for is about state that is changing underneath it. */
	private static final class Dialog extends JDialog {

		private static final long serialVersionUID = 1L;

		private final JList<Shutdown.Operation> list;

		Dialog (List<Shutdown.Operation> running) {
			super ( IJ.getInstance(), TITLE, true );

			DefaultListModel<Shutdown.Operation> model = new DefaultListModel<Shutdown.Operation>();
			for (Shutdown.Operation operation : running) model.addElement ( operation );
			list = new JList<Shutdown.Operation> ( model );
			/* MULTIPLE_INTERVAL_SELECTION is what gives shift-click for a range and
			 * ctrl-click for individual rows; both are Swing's own behaviour for it. */
			list.setSelectionMode ( ListSelectionModel.MULTIPLE_INTERVAL_SELECTION );
			list.setVisibleRowCount ( Math.min ( 10, Math.max ( 4, running.size() ) ) );

			JPanel content = new JPanel ( new BorderLayout ( 8, 8 ) );
			content.setBorder ( BorderFactory.createEmptyBorder ( 10, 10, 10, 10 ) );
			content.setBackground ( Parameter.frameColor );

			JLabel explain = new JLabel ( "<html>Select the run(s) to stop, then press Terminate."
					+ "<br>Each stops at its next safe point, after the plane, chunk or time point"
					+ " it is writing is finished, so nothing is left half written."
					+ "<br>Shift-click selects a range; ctrl-click adds or removes one row.</html>" );
			explain.setBackground ( Parameter.frameColor );
			content.add ( explain, BorderLayout.NORTH );

			JScrollPane scroll = new JScrollPane ( list );
			scroll.setPreferredSize ( new Dimension ( 560, 150 ) );
			content.add ( scroll, BorderLayout.CENTER );

			JPanel buttons = new JPanel ( new FlowLayout ( FlowLayout.RIGHT, 6, 0 ) );
			buttons.setBackground ( Parameter.frameColor );
			JButton help = new JButton ( "Help" );
			JButton selectAll = new JButton ( "Select All" );
			JButton selectNone = new JButton ( "Select None" );
			JButton terminate = new JButton ( "Terminate" );
			JButton cancel = new JButton ( "Cancel" );
			buttons.add ( help );
			buttons.add ( selectAll );
			buttons.add ( selectNone );
			buttons.add ( terminate );
			buttons.add ( cancel );
			content.add ( buttons, BorderLayout.SOUTH );

			help.addActionListener ( new ActionListener() {
				@Override public void actionPerformed (ActionEvent event) {
					new ij.gui.HTMLDialog ( Dialog.this, TITLE, Help.terminate );
				}
			});
			selectAll.addActionListener ( new ActionListener() {
				@Override public void actionPerformed (ActionEvent event) {
					list.setSelectionInterval ( 0, list.getModel().getSize() - 1 );
				}
			});
			selectNone.addActionListener ( new ActionListener() {
				@Override public void actionPerformed (ActionEvent event) { list.clearSelection(); }
			});
			terminate.addActionListener ( new ActionListener() {
				@Override public void actionPerformed (ActionEvent event) { terminateSelected(); }
			});
			cancel.addActionListener ( new ActionListener() {
				@Override public void actionPerformed (ActionEvent event) { dispose(); }
			});

			/* A Swing timer, so the rows keep showing how long each run has been going and
			 * turn to "stopping" once asked. It only repaints - the model is left alone, so it
			 * cannot pull a row out from under a selection the user is making. Swing timers
			 * run on the shared daemon TimerQueue and so cannot hold the JVM open. */
			final Timer refresh = new Timer ( 1000, new ActionListener() {
				@Override public void actionPerformed (ActionEvent event) { list.repaint(); }
			});
			refresh.start();
			addWindowListener ( new java.awt.event.WindowAdapter() {
				@Override public void windowClosed (java.awt.event.WindowEvent event) { refresh.stop(); }
				@Override public void windowClosing (java.awt.event.WindowEvent event) { refresh.stop(); }
			});

			setContentPane ( content );
			Party.decorate ( this, content );	// before pack(); see Party.reserveRim
			getRootPane().setDefaultButton ( cancel );	// Enter cannot terminate by accident
			pack();
			setResizable ( true );
			setLocationRelativeTo ( IJ.getInstance() );
		}

		private void terminateSelected () {
			List<Shutdown.Operation> chosen = list.getSelectedValuesList();
			if (chosen.isEmpty()) {
				IJ.showMessage ( TITLE, "Select at least one run to terminate." );
				return;
			}
			for (Shutdown.Operation operation : chosen) {
				Shutdown.cancel ( operation );
				IJ.log ( "OPM: termination requested for " + operation.getName()
						+ "; it will stop at its next safe point." );
			}
			dispose();
		}
	}
}
