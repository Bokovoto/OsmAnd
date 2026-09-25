import net.osmand.plus.roadcrew.tacho.RoadCrewTachoShareZip;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The DDD goes out as a ZIP with the original file inside (Galin, 25.09.2026:
 * Viber refused .ddd, accepted .zip). The archive must hold exactly that one
 * file, same name, same bytes. ROADMAP 327.
 */
public class ShareZipTest {

	static int passed = 0;

	static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError(what);
		}
		passed++;
	}

	static byte[] ddd(int length) {
		byte[] out = new byte[length];
		for (int i = 0; i < length; i++) {
			out[i] = (byte) (i * 31 + (i >> 7));
		}
		return out;
	}

	public static void main(String[] args) throws Exception {
		String name = "C_20260925_1808_000000000AKEV001.ddd";
		check(RoadCrewTachoShareZip.zipName(name).equals("C_20260925_1808_000000000AKEV001.zip"), "zip name");
		check(RoadCrewTachoShareZip.zipName("card").equals("card.zip"), "zip name without extension");

		for (int length : new int[]{0, 1, 26493, 123214}) {
			byte[] original = ddd(length);
			byte[] zip = RoadCrewTachoShareZip.zip(name, original, 1790348914000L);
			try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
				ZipEntry entry = in.getNextEntry();
				check(entry != null && entry.getName().equals(name), "one entry with the original name, " + length);
				ByteArrayOutputStream content = new ByteArrayOutputStream();
				byte[] buffer = new byte[4096];
				for (int n; (n = in.read(buffer)) > 0; ) {
					content.write(buffer, 0, n);
				}
				check(Arrays.equals(content.toByteArray(), original), "the same bytes, " + length);
				check(in.getNextEntry() == null, "nothing else in the archive, " + length);
			}
			RoadCrewTachoShareZip.verify(zip, name, original);
			passed++;
		}

		byte[] original = ddd(26493);
		byte[] zip = RoadCrewTachoShareZip.zip(name, original, 1790348914000L);
		byte[] tampered = zip.clone();
		tampered[tampered.length / 2] ^= 0x55;
		try {
			RoadCrewTachoShareZip.verify(tampered, name, original);
			check(false, "a damaged archive must not pass");
		} catch (IOException expected) {
			passed++;
		}
		try {
			RoadCrewTachoShareZip.verify(zip, "other.ddd", original);
			check(false, "a wrong name must not pass");
		} catch (IOException expected) {
			passed++;
		}

		System.out.println(passed + " share zip checks passed");
	}
}
