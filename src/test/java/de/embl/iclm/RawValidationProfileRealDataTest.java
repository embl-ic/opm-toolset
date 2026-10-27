package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.Assume;
import org.junit.Test;

/**
 * Where checking a raw acquisition file's structure spends its time (M2-Q-F1): the file's version
 * ({@code FileKey}), the completion check that walks every IFD and strip range against EOF
 * ({@link TiffCompletionCheck#isReady}), the layout parse ({@link FastTiffReader#parse}), and the
 * whole {@link RawAcquisition.Entry#validate} the view runs - one file after another, as the view's
 * checker does. Two passes in one runtime: the second finds the headers in the operating system's
 * cache, which the first may or may not have. Run outside Maven, gate {@code opm.test.validation}:
 * <pre>
 * run-harness.ps1 -Source &lt;checkout&gt; -Out &lt;log&gt; -Test de.embl.iclm.RawValidationProfileRealDataTest -Gate opm.test.validation
 * </pre>
 * It asserts only that every file checks as complete.
 */
public class RawValidationProfileRealDataTest {

	@Test
	public void checkingRealAcquisitionFiles() throws Exception {
		String data = System.getProperty("opm.test.validation", "");
		Assume.assumeTrue("Set -Dopm.test.validation", !data.isEmpty());
		File folder = new File(data);
		int wanted = Integer.getInteger("opm.validation.times", 50);
		System.out.printf(Locale.US, "java %s; %s%n", System.getProperty("java.version"), folder);
		System.out.println("pass\tfiles\tfile_key_ms\tcompletion_check_ms\tlayout_parse_ms\tvalidate_ms\tvalidate_p50_ms\tvalidate_max_ms\tfiles_per_s");
		for (int pass = 1; pass <= 2; pass++) {
			RawAcquisition acquisition = RawAcquisition.scan(folder);		// fresh entries: nothing checked yet
			List<RawAcquisition.TimeKey> times = acquisition.times(1, 1);
			int count = Math.min(wanted, times.size());
			double key = 0, completion = 0, parse = 0;
			double[] validate = new double[count];
			for (int t = 0; t < count; t++) {
				RawAcquisition.Entry e = acquisition.entry(times.get(t), 1);
				long t0 = System.nanoTime();
				RawAcquisition.FileKey k = RawAcquisition.FileKey.of(e.file, 1);
				long t1 = System.nanoTime();
				boolean ready = TiffCompletionCheck.isReady(e.file);
				long t2 = System.nanoTime();
				RawTiffLayout layout = RawTiffLayout.read(e.file);
				long t3 = System.nanoTime();
				assertTrue(k != null && ready && layout.supported());
				key += (t1 - t0) / 1e6;
				completion += (t2 - t1) / 1e6;
				parse += (t3 - t2) / 1e6;
			}
			RawAcquisition again = RawAcquisition.scan(folder);		// the whole check the view runs, on entries not checked yet
			for (int t = 0; t < count; t++) {
				RawAcquisition.Entry e = again.entry(times.get(t), 1);
				long t0 = System.nanoTime();
				assertEquals(e.detail(), RawAcquisition.Readiness.COMPLETE, e.validate());
				validate[t] = (System.nanoTime() - t0) / 1e6;
			}
			double total = 0, max = 0;
			for (double v : validate) {
				total += v;
				max = Math.max(max, v);
			}
			double[] sorted = validate.clone();
			Arrays.sort(sorted);
			System.out.printf(Locale.US, "%d\t%d\t%.1f\t%.1f\t%.1f\t%.1f\t%.1f\t%.1f\t%.1f%n", pass, count, key / count, completion / count,
					parse / count, total / count, sorted[sorted.length / 2], max, count / (total / 1e3));
		}
	}
}
