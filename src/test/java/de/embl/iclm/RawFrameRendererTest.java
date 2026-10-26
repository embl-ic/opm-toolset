package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import bdv.viewer.Interpolation;
import net.imglib2.FinalInterval;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.view.Views;

/**
 * Agent-B's S3, at the renderer: {@link RawFrameRenderer#render} returns only once every band it
 * started has finished - when one fails while another is still inside pixel access, when the frame
 * is cancelled, and when the renderer is closed while bands are queued - so that the caller may
 * release the frame's cells and image the moment it returns.
 */
public class RawFrameRendererTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final int SW = 96, SH = 96;

	@After
	public void noHook() {
		RawFrameRenderer.rowHookForTest = null;
	}

	private RawFrameRequest request(RawFrameFixture f) {
		return f.request(1, 0, RawFrameFixture.view(f.dims(), 0.5, 0, SW, SH), Interpolation.NEARESTNEIGHBOR, 0, 3000, SW, SH);
	}

	/** Both halves placed with no transform at all, as in agent-B's probe: a source row is a screen row. */
	private RawFrameRequest identityRequest(RawFrameFixture f) {
		AffineTransform3D identity = new AffineTransform3D();
		return new RawFrameRequest(1, 0, f.entry(0), identity, SW, SH, Interpolation.NEARESTNEIGHBOR, Arrays.asList(
				new RawFrameRequest.Layer(RawFrameFixture.SPECS[0], identity, 0, 3000, 0xff00ff00),
				new RawFrameRequest.Layer(RawFrameFixture.SPECS[1], identity, 0, 3000, 0xffff00ff)));
	}

	/** Bands that are running now. */
	private static final AtomicInteger running = new AtomicInteger();

	/**
	 * B's probe as a regression: the first band fails once the second is inside a pixel read, and
	 * the second is held there. {@code render} must not return until the held band has finished.
	 */
	@Test
	public void aFailedBandDoesNotLetRenderReturnWhileAnotherBandStillReadsPixels() throws Exception {
		try (RawFrameFixture f = new RawFrameFixture(folder.getRoot())) {
			final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
			final AtomicBoolean firstBand = new AtomicBoolean(), secondBand = new AtomicBoolean(), inside = new AtomicBoolean();
			FunctionRandomAccessible<UnsignedShortType> samples = new FunctionRandomAccessible<UnsignedShortType>(3, (p, v) -> {
				if (Thread.currentThread().getName().startsWith("OPM-raw-frame")) {
					long y = p.getLongPosition(1);
					if (y < 16 && firstBand.compareAndSet(false, true)) {
						await(entered);
						throw new IllegalStateException("the first band fails");
					}
					if (y >= 16 && secondBand.compareAndSet(false, true)) {
						inside.set(true);
						entered.countDown();
						await(release);
						inside.set(false);
					}
				}
				v.set(1000);
			}, UnsignedShortType::new);
			final RawFrameRenderer renderer = new RawFrameRenderer(2);
			final AtomicReference<Throwable> thrown = new AtomicReference<Throwable>();
			final AtomicBoolean returned = new AtomicBoolean();
			Thread caller = new Thread(() -> {
				try {
					renderer.render(identityRequest(f), (k, l) -> Views.interval(samples, new FinalInterval(RawFrameFixture.W, RawFrameFixture.H,
							RawFrameFixture.D)), () -> false);
				} catch (Throwable t) {
					thrown.set(t);
				}
				returned.set(true);
			}, "test-caller");
			try {
				caller.start();
				assertTrue(entered.await(5, TimeUnit.SECONDS));
				Thread.sleep(300);		// the first band has failed by now
				assertTrue("the second band is still inside a pixel read", inside.get());
				assertFalse("render has not returned while a band still reads", returned.get());
				release.countDown();
				caller.join(5000);
				assertTrue(returned.get());
				assertFalse("no band is inside a pixel read once it returned", inside.get());
				assertTrue(String.valueOf(thrown.get()), thrown.get() instanceof ExecutionException);
				assertEquals("the first band fails", thrown.get().getCause().getMessage());
			} finally {
				release.countDown();
				renderer.close();
				assertTrue(renderer.awaitTermination(5000));
			}
		}
	}

	/** A cancelled frame stops at the next row of every band, and returns once all have. */
	@Test
	public void aCancelledFrameReturnsOnceEveryBandHasStopped() throws Exception {
		try (RawFrameFixture f = new RawFrameFixture(folder.getRoot())) {
			final RawFrameRenderer renderer = new RawFrameRenderer(3);
			final AtomicBoolean cancelled = new AtomicBoolean();
			final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
			final AtomicBoolean once = new AtomicBoolean();
			running.set(0);
			RawFrameRenderer.rowHookForTest = row -> {
				if (once.compareAndSet(false, true)) {
					running.incrementAndGet();
					entered.countDown();
					await(release);
					running.decrementAndGet();
				}
			};
			final AtomicReference<Throwable> thrown = new AtomicReference<Throwable>();
			Thread caller = new Thread(() -> {
				try {
					renderer.render(request(f), f.reference::blocking, cancelled::get);
				} catch (Throwable t) {
					thrown.set(t);
				}
			}, "test-caller");
			try {
				caller.start();
				assertTrue(entered.await(5, TimeUnit.SECONDS));
				cancelled.set(true);
				caller.join(300);
				assertTrue("still waiting for the held band", caller.isAlive());
				release.countDown();
				caller.join(5000);
				assertFalse(caller.isAlive());
				assertEquals("no band runs once it returned", 0, running.get());
				assertTrue(String.valueOf(thrown.get()), thrown.get() instanceof java.util.concurrent.CancellationException);
			} finally {
				release.countDown();
				renderer.close();
			}
		}
	}

	/**
	 * Closing the renderer while bands are queued behind a held one: the queued bands still run,
	 * and the frame completes once all have; a frame asked for afterwards is refused at once.
	 */
	@Test
	public void closingWhileBandsAreQueuedLetsThemFinishAndRefusesTheNextFrame() throws Exception {
		try (RawFrameFixture f = new RawFrameFixture(folder.getRoot())) {
			final RawFrameRenderer renderer = new RawFrameRenderer(1);
			final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
			final AtomicBoolean once = new AtomicBoolean();
			RawFrameRenderer.rowHookForTest = row -> {
				if (once.compareAndSet(false, true)) {
					entered.countDown();
					await(release);
				}
			};
			final RawFrameRequest r = request(f);
			final AtomicReference<Object> outcome = new AtomicReference<Object>();
			Thread caller = new Thread(() -> {
				try {
					outcome.set(renderer.render(r, f.reference::blocking, () -> false));
				} catch (Throwable t) {
					outcome.set(t);
				}
			}, "test-caller");
			try {
				caller.start();
				assertTrue(entered.await(5, TimeUnit.SECONDS));
				renderer.close();		// five bands wait behind the held one
				release.countDown();
				caller.join(5000);
				assertTrue(String.valueOf(outcome.get()), outcome.get() instanceof java.awt.image.BufferedImage);
				int[] drawn = ((java.awt.image.BufferedImage) outcome.get()).getRGB(0, 0, SW, SH, null, 0, SW);
				int[] expected = f.referenceImage(r).getRGB(0, 0, SW, SH, null, 0, SW);
				assertTrue("the queued bands ran: the frame is complete", Arrays.equals(expected, drawn));
				assertTrue(renderer.awaitTermination(5000));
				try {
					renderer.render(r, f.reference::blocking, () -> false);
					fail("a closed renderer drew a frame");
				} catch (IllegalStateException expectedRefusal) {
					assertNotNull(expectedRefusal.getMessage());
				}
			} finally {
				release.countDown();
			}
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("latch deadline");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
