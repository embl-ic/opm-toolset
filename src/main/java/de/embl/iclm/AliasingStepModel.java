package de.embl.iclm;

import java.util.ArrayList;
import java.util.List;

/** Pure geometry and conditional acquisition estimates; no image or preference state. */
final class AliasingStepModel {
	static final double FWHM = 2 * Math.sqrt(2 * Math.log(2));
	static final class Support {
		double kz, ky, aliasCap, sensitivityCap, cameraRowFraction, cameraXFraction;
		String description;
	}
	static Support support(AliasingAdvisorSettings s) {
		s.validate();
		Support result = new Support();
		if (s.optics == 2) {
			result.kz = s.kz; result.ky = s.ky;
			result.cameraRowFraction = result.cameraXFraction = Double.NaN;
			result.sensitivityCap = Double.NaN;
			result.description = s.supportMeasured ? "Entered measured support (sampling not independently verified)" : "Entered assumed support";
		} else {
			double sx, sy, sz;
			if (s.optics == 0) {
				sx = sy = .51 * s.wavelengthNm / 1000 / s.na / FWHM;
				sz = 1.77 * s.refractiveIndex * s.wavelengthNm / 1000 / (s.na * s.na) / FWHM;
			} else { sx = s.fwhmX / FWHM; sy = s.fwhmY / FWHM; sz = s.fwhmZ / FWHM; }
			double sn = Math.sin(Math.toRadians(s.angle)), cs = Math.cos(Math.toRadians(s.angle));
			double sheetPrecision = s.optics == 0 ? Math.pow(FWHM / s.sheetFwhm, 2) : 0;
			double qxx = 1 / (sx * sx), qyy = 1 / (sy * sy) + sn * sn * sheetPrecision;
			double qzz = 1 / (sz * sz) + cs * cs * sheetPrecision, qyz = sn * cs * sheetPrecision;
			double c = Math.log(1 / s.threshold) / (2 * Math.PI * Math.PI);
			result.kz = Math.sqrt(c * qzz); result.ky = Math.sqrt(c * qyy);
			result.cameraXFraction = 2 * s.pixel * Math.sqrt(c * qxx);
			result.cameraRowFraction = 2 * s.pixel * Math.sqrt(c * (cs * cs * qyy + sn * sn * qzz - 2 * sn * cs * qyz));
			result.sensitivityCap = aliasCap(s.angle, Math.sqrt(Math.log(10) / (2 * Math.PI * Math.PI) * qzz)) * s.safety;
			result.description = s.optics == 0 ? "Illustrative detection x sheet Gaussian; intrinsic support" : "Axis-aligned effective Gaussian approximation; not a measured full OTF";
		}
		result.aliasCap = s.safety * aliasCap(s.angle, result.kz);
		return result;
	}
	static double aliasCap(double angle, double kz) { return 1 / (2 * kz * Math.tan(Math.toRadians(angle))); }
	static double sinc(double cycles) {
		double x = Math.PI * cycles;
		return Math.abs(x) < 1e-9 ? 1 : Math.sin(x) / x;
	}
	static final class Prediction {
		double step, normalSpacing, finePitch, framePeriodMs, exposureMs, blurUm;
		double absoluteMotion, relativeMotion, seconds, speedup, span, overshoot;
		long planes;
		boolean aliasPass, motionPass;
		String status;
	}
	static Prediction predict(AliasingAdvisorSettings s, Support support, double step) {
		AliasingAdvisorSettings.positive(step, "Candidate step");
		Prediction p = new Prediction(); p.step = step;
		p.normalSpacing = step * Math.sin(Math.toRadians(s.angle)); p.finePitch = s.step / s.refinement;
		double t0 = s.measuredPeriod ? s.periodMs : s.exposureMs + s.overheadMs;
		p.framePeriodMs = s.fixedVelocity ? t0 * step / s.step : t0;
		double l0 = s.step * s.exposureMs / t0;
		p.exposureMs = s.preserveBlur ? l0 * p.framePeriodMs / step : s.exposureMs;
		p.blurUm = s.scanMode == 1 ? 0 : step * p.exposureMs / p.framePeriodMs;
		p.absoluteMotion = s.scanMode == 2 ? Double.NaN : Math.abs(sinc(support.ky * p.blurUm));
		double base = s.scanMode == 1 ? 1 : sinc(support.ky * l0);
		p.relativeMotion = Double.NaN;
		boolean lobe = s.scanMode == 1 || (support.ky * l0 < 1 && support.ky * p.blurUm < 1);
		if (s.scanMode != 2 && lobe && base >= s.baselineMotionFloor) {
			p.relativeMotion = 1;
			for (int i = 1; i <= 256; i++) {
				double k = support.ky * i / 256.;
				double baseline = s.scanMode == 1 ? 1 : sinc(k * l0);
				p.relativeMotion = Math.min(p.relativeMotion, sinc(k * p.blurUm) / baseline);
			}
		}
		p.planes = planeCount(s.extent, step); p.span = (p.planes - 1) * step; p.overshoot = Math.max(0, p.span - s.extent);
		p.seconds = ((p.planes - 1) * p.framePeriodMs + p.exposureMs + s.flybackMs) / 1000;
		double baselineSeconds = ((planeCount(s.extent, s.step) - 1) * t0 + s.exposureMs + s.flybackMs) / 1000;
		p.speedup = baselineSeconds / p.seconds;
		p.aliasPass = step <= support.aliasCap && !(support.cameraRowFraction >= 1 || support.cameraXFraction >= 1);
		p.motionPass = lobe && p.relativeMotion >= s.relativeMotionFloor
				&& (!s.gateAbsoluteMotion || p.absoluteMotion >= s.absoluteMotionFloor)
				&& p.exposureMs <= p.framePeriodMs && p.exposureMs > 0;
		p.status = !p.aliasPass ? "Outside conditional alias/camera bound" : !p.motionPass ? "Motion/timing criterion fails or unknown"
				: "Conditional model candidate; validate optics and timing";
		// Entered support carries no lateral/camera-row extent, so camera sampling was not checked at all.
		if (Double.isNaN(support.cameraRowFraction) || Double.isNaN(support.cameraXFraction)) p.status += "; camera sampling not checked";
		return p;
	}
	static long planeCount(double extent, double step) {
		double ratio = extent / step;
		if (!Double.isFinite(ratio) || ratio > Integer.MAX_VALUE - 1) throw new IllegalArgumentException("Too many acquisition planes");
		double nearest = Math.rint(ratio);
		if (Math.abs(ratio - nearest) <= Math.max(1e-12, 8 * Math.ulp(ratio))) ratio = nearest;
		return (long) Math.ceil(ratio) + 1;
	}
	static int[] factors(AliasingAdvisorSettings s) {
		List<Integer> list = new ArrayList<Integer>();
		for (int m = 2; m * s.refinement <= 64; m++)
			if (m * s.step >= s.minimumStep - 1e-10 && m * s.step <= s.maximumStep + 1e-10) list.add(m);
		if (list.isEmpty()) throw new IllegalArgumentException("Range contains no integer subset factors >=2 with m*r <=64");
		int[] result = new int[list.size()]; for (int i = 0; i < result.length; i++) result[i] = list.get(i);
		return result;
	}
	static int[] domain(int depth, int[] factors, int guard) {
		int first = 0, last = depth - 1;
		for (int m : factors) for (int a = 0; a < m; a++) {
			if (a + m >= depth) throw new IllegalArgumentException("ROI has too few planes for every phase");
			first = Math.max(first, a); last = Math.min(last, a + ((depth - 1 - a) / m) * m);
		}
		first += guard; last -= guard;
		if (last - first < 2) throw new IllegalArgumentException("Common guarded domain has fewer than three planes; enlarge ROI or reduce factors/guard");
		return new int[] {first, last};
	}
	static int padding(AliasingAdvisorSettings s) {
		double pad = Math.max(Math.ceil(s.scanPadding / (s.step / s.refinement)), Math.ceil(s.rowPadding / s.pixel));
		if (pad > 4096) throw new IllegalArgumentException("Physical padding exceeds core limit; reduce padding or refinement");
		return (int) pad;
	}
	static String range(AliasingAdvisorSettings s, Support support) {
		double lo = Double.NaN, hi = Double.NaN;
		for (int i = 0; i <= 1000; i++) {
			double d = s.minimumStep + (s.maximumStep - s.minimumStep) * i / 1000.;
			Prediction p = predict(s, support, d);
			if (p.aliasPass && p.motionPass) { if (Double.isNaN(lo)) lo = d; hi = d; }
			else if (!Double.isNaN(lo)) break;
		}
		return Double.isNaN(lo) ? "No explored model step meets the current alias/motion criteria"
				: String.format(java.util.Locale.ROOT, "Conditional model interval ~%.4f..%.4f um (1001-point estimate)", lo, hi);
	}
}
