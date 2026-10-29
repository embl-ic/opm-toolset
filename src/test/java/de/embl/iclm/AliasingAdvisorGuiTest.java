package de.embl.iclm;

import static org.junit.Assert.*;
import ij.gui.GenericDialog;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AliasingAdvisorGuiTest {
	@Rule public TemporaryFolder folder = new TemporaryFolder();
	@Before public void gui() { Assume.assumeTrue(Boolean.getBoolean("opm.test.gui")); Assume.assumeFalse(GraphicsEnvironment.isHeadless()); }
	private AliasingStepAdvisor open(AliasingAdvisorSettings settings) throws Exception {
		AliasingStepAdvisor advisor = new AliasingStepAdvisor(settings, false);
		Thread plugin = Shutdown.daemon(() -> advisor.buildDialog(null).showDialog(), "advisor-gui-test"); plugin.start();
		long end = System.currentTimeMillis() + 10000;
		while (System.currentTimeMillis() < end) {
			AtomicReference<Boolean> showing = new AtomicReference<Boolean>(false);
			EventQueue.invokeAndWait(() -> showing.set(advisor.dialog != null && advisor.dialog.isShowing()));
			if (showing.get()) return advisor; Thread.sleep(20);
		}
		throw new AssertionError("Advisor did not show");
	}
	private static void close(GenericDialog dialog) throws Exception { EventQueue.invokeAndWait(() -> dialog.windowClosing(null)); }
	private static Button button(Container root, String label) {
		for (Component c : root.getComponents()) {
			if (c instanceof Button && ((Button)c).getLabel().equals(label)) return (Button)c;
			if (c instanceof Container) { Button result = button((Container)c, label); if (result != null) return result; }
		}
		return null;
	}
	private static Choice metadata(Container root) {
		for (Component c : root.getComponents()) {
			if (c instanceof Choice && ((Choice)c).getItemCount() > 0 && ((Choice)c).getItem(0).equals("Manual")) return (Choice)c;
			if (c instanceof Container) { Choice result = metadata((Container)c); if (result != null) return result; }
		}
		return null;
	}
	@Test public void foldsKeepActionsVisibleAndDialogFitsScreen() throws Exception {
		AliasingStepAdvisor advisor = open(new AliasingAdvisorSettings());
		try {
			EventQueue.invokeAndWait(() -> {
				advisor.updateEstimate(false);
				assertFalse(advisor.dialog.isModal()); assertNotNull(advisor.dialog.folds());
				assertEquals(6, advisor.dialog.folds().sections().size());
				for (SectionFolds.Section section : advisor.dialog.folds().sections()) section.setExpanded(false);
				advisor.dialog.pack(); assertTrue(button(advisor.dialog, "Stop").isShowing()); assertTrue(button(advisor.dialog, "Test reference ROI").isShowing());
				assertTrue(advisor.dialog.getHeight() <= ij.gui.GUI.getMaxWindowBounds().height);
				assertNotNull(metadata(advisor.dialog)); assertEquals(0.8, advisor.read().na, 0);
				try {
					File dir = new File(".tmp/advisor-verification"); dir.mkdirs();
					BufferedImage image = new BufferedImage(advisor.dialog.getWidth(), advisor.dialog.getHeight(), BufferedImage.TYPE_INT_RGB);
					Graphics2D graphics = image.createGraphics(); advisor.dialog.printAll(graphics); graphics.dispose();
					ImageIO.write(image, "png", new File(dir, "advisor-folded.png"));
				} catch (Exception failure) { throw new RuntimeException(failure); }
			});
		} finally { close(advisor.dialog); }
	}
	@Test public void metadataDropdownOverwritesPresentFieldsAndRetainsManualNa() throws Exception {
		File metadata = folder.newFile("ExperimentalParameter.txt");
		Files.write(metadata.toPath(), Arrays.asList("Pixel size at object /um: .12", "Tilt angle: 33.5", "Step size /um: .2", "Exposure time /ms: 80", "Planes per volume: 91", "NA slit width /mm: 2.2"), StandardCharsets.UTF_8);
		AliasingAdvisorSettings settings = new AliasingAdvisorSettings(); settings.na = .9; settings.recentMetadata = new String[] {metadata.getAbsolutePath()};
		AliasingStepAdvisor advisor = open(settings);
		try {
			EventQueue.invokeAndWait(() -> {
				Choice choice = metadata(advisor.dialog); choice.select(2);
				choice.dispatchEvent(new ItemEvent(choice, ItemEvent.ITEM_STATE_CHANGED, choice.getSelectedItem(), ItemEvent.SELECTED));
			});
			long deadline = System.currentTimeMillis() + 10000; AtomicReference<AliasingAdvisorSettings> read = new AtomicReference<AliasingAdvisorSettings>();
			do { EventQueue.invokeAndWait(() -> read.set(advisor.read())); if (read.get().step == .2) break; Thread.sleep(20); } while (System.currentTimeMillis() < deadline);
			assertEquals(.2, read.get().step, 0); assertEquals(33.5, read.get().angle, 0); assertEquals(.9, read.get().na, 0);
			assertFalse(read.get().geometryConfirmed); assertEquals(80, read.get().exposureMs, 0);
			assertEquals(.8, read.get().maximumStep, 1e-12);
		} finally { close(advisor.dialog); }
	}
	@Test public void aNewMetadataSelectionSupersedesAnOutstandingLoad() throws Exception {
		File a = folder.newFile("a.txt"), b = folder.newFile("b.txt");
		Files.write(a.toPath(), Arrays.asList("Step size /um: .3"), StandardCharsets.UTF_8);
		Files.write(b.toPath(), Arrays.asList("Step size /um: .4"), StandardCharsets.UTF_8);
		AliasingAdvisorSettings settings = new AliasingAdvisorSettings(); settings.recentMetadata = new String[] {a.getAbsolutePath(), b.getAbsolutePath()};
		AliasingStepAdvisor advisor = open(settings);
		try {
			EventQueue.invokeAndWait(() -> {
				Choice choice = metadata(advisor.dialog);
				for (int index : new int[] {2, 3}) {
					choice.select(index); choice.dispatchEvent(new ItemEvent(choice, ItemEvent.ITEM_STATE_CHANGED, choice.getSelectedItem(), ItemEvent.SELECTED));
				}
			});
			long deadline = System.currentTimeMillis() + 10000; AtomicReference<AliasingAdvisorSettings> read = new AtomicReference<AliasingAdvisorSettings>();
			do { EventQueue.invokeAndWait(() -> read.set(advisor.read())); if (read.get().step == .4) break; Thread.sleep(20); } while (System.currentTimeMillis() < deadline);
			assertEquals(.4, read.get().step, 0); assertEquals(b.getCanonicalPath(), read.get().metadataPath);
		} finally { close(advisor.dialog); }
	}
	@Test public void stopRemainsCancellingUntilWorkerCleanupActuallyFinishes() throws Exception {
		AliasingStepAdvisor advisor = open(new AliasingAdvisorSettings());
		java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1), cleanup = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1), done = new java.util.concurrent.CountDownLatch(1);
		try {
			EventQueue.invokeAndWait(() -> advisor.start("GUI cancellation", () -> {
				ready.countDown();
				try { while (!Shutdown.stopping()) Thread.yield(); }
				finally { cleanup.countDown(); ShutdownAsyncTest.uninterruptible(release); }
			}, op -> done.countDown()));
			ShutdownAsyncTest.await(ready);
			EventQueue.invokeAndWait(() -> {
				Button stop = button(advisor.dialog,"Stop");
				for (java.awt.event.ActionListener listener : stop.getActionListeners()) listener.actionPerformed(new java.awt.event.ActionEvent(stop,java.awt.event.ActionEvent.ACTION_PERFORMED,"Stop"));
			});
			ShutdownAsyncTest.await(cleanup);
			EventQueue.invokeAndWait(() -> assertTrue(summary(advisor.dialog).getText().contains("Cancelling")));
			release.countDown(); ShutdownAsyncTest.await(done);
			EventQueue.invokeAndWait(() -> assertTrue(summary(advisor.dialog).getText().contains("cleanup finished")));
		} finally { release.countDown(); close(advisor.dialog); }
	}
	private static TextArea summary(Container root) {
		for (Component c : root.getComponents()) {
			if (c instanceof TextArea) return (TextArea)c;
			if (c instanceof Container) { TextArea area=summary((Container)c); if(area!=null) return area; }
		}
		return null;
	}
}
