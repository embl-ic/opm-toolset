package de.embl.iclm;

import java.awt.image.BufferedImage;

import bdv.cache.CacheControl;
import bdv.viewer.BasicViewerState;
import bdv.viewer.render.AccumulateProjectorARGB;
import bdv.viewer.render.MultiResolutionRenderer;
import bdv.viewer.render.RenderTarget;
import bdv.viewer.render.awt.BufferedImageRenderResult;

/**
 * BigDataViewer's own renderer, built directly - which only test code may do: its constructor lost
 * the projector factory between the managed release (10.6.4) and Fiji's (10.6.11), so the one
 * present is chosen here, which lets the tests run against either (contract C5.3).
 */
final class BdvTestRenderer {

	private BdvTestRenderer() {}

	/** Two rendering threads, one screen scale: a frame is drawn at full resolution or not at all. */
	static MultiResolutionRenderer renderer(RenderTarget<?> target, boolean useVolatile, CacheControl cache) {
		bdv.viewer.RequestRepaint none = () -> {};
		for (java.lang.reflect.Constructor<?> c : MultiResolutionRenderer.class.getConstructors())
			try {
				if (c.getParameterCount() == 9)
					return (MultiResolutionRenderer) c.newInstance(target, none, new double[] { 1 }, 0L, 2, null, useVolatile,
							AccumulateProjectorARGB.factory, cache);
				if (c.getParameterCount() == 8)
					return (MultiResolutionRenderer) c.newInstance(target, none, new double[] { 1 }, 0L, 2, null, useVolatile, cache);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		throw new IllegalStateException("No known MultiResolutionRenderer constructor");
	}

	/** One complete frame of {@code state}, from the blocking sources, as BigDataViewer draws it. */
	static BufferedImage paint(BasicViewerState state, final int width, final int height) {
		final BufferedImageRenderResult result = new BufferedImageRenderResult();
		RenderTarget<BufferedImageRenderResult> target = new RenderTarget<BufferedImageRenderResult>() {
			@Override public BufferedImageRenderResult getReusableRenderResult() { return result; }
			@Override public BufferedImageRenderResult createRenderResult() { return new BufferedImageRenderResult(); }
			@Override public void setRenderResult(BufferedImageRenderResult r) {}
			@Override public int getWidth() { return width; }
			@Override public int getHeight() { return height; }
		};
		MultiResolutionRenderer renderer = renderer(target, false, new CacheControl.Dummy());
		try {
			renderer.requestRepaint();
			if (!renderer.paint(state)) throw new IllegalStateException("BigDataViewer's renderer was cancelled");
			return result.getBufferedImage();
		} finally {
			renderer.kill();
		}
	}
}
