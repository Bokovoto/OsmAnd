package net.osmand.plus.roadcrew.tacho;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The driver card file is sent as a ZIP holding the original DDD, same name,
 * same bytes (Galin, 25.09.2026). Tested on his phone: Viber refuses a .ddd
 * file ("not supported or damaged") even from the phone's own file manager,
 * and accepts the same file in a .zip. The DDD in Downloads/RoadCrew stays as
 * downloaded. Plain Java, tested without a phone (ROADMAP 327).
 */
public final class RoadCrewTachoShareZip {

	private RoadCrewTachoShareZip() {
	}

	/** C_..._card.ddd -> C_..._card.zip */
	public static String zipName(String dddName) {
		int dot = dddName.lastIndexOf('.');
		return (dot > 0 ? dddName.substring(0, dot) : dddName) + ".zip";
	}

	/** One entry, the DDD under its own name, dated when it was downloaded. */
	public static byte[] zip(String dddName, byte[] ddd, long takenAtMillis) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(ddd.length / 2 + 256);
		try (ZipOutputStream out = new ZipOutputStream(bytes)) {
			ZipEntry entry = new ZipEntry(dddName);
			if (takenAtMillis > 0) {
				entry.setTime(takenAtMillis);
			}
			out.putNextEntry(entry);
			out.write(ddd);
			out.closeEntry();
		}
		return bytes.toByteArray();
	}

	/** Reads the archive back: exactly one entry, that name, those bytes - or an IOException. */
	public static void verify(byte[] zip, String dddName, byte[] ddd) throws IOException {
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
			ZipEntry entry = in.getNextEntry();
			if (entry == null || !dddName.equals(entry.getName())) {
				throw new IOException("ZIP does not hold " + dddName);
			}
			ByteArrayOutputStream content = new ByteArrayOutputStream(ddd.length);
			byte[] buffer = new byte[8192];
			for (int n; (n = in.read(buffer)) > 0; ) {
				content.write(buffer, 0, n);
			}
			if (!Arrays.equals(content.toByteArray(), ddd)) {
				throw new IOException("ZIP content differs from " + dddName);
			}
			if (in.getNextEntry() != null) {
				throw new IOException("ZIP holds more than " + dddName);
			}
		} catch (IllegalArgumentException | java.util.zip.ZipException e) {
			throw new IOException("ZIP cannot be read back: " + e.getMessage(), e);
		}
	}
}
