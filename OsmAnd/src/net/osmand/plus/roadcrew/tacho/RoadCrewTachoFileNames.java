package net.osmand.plus.roadcrew.tacho;

import java.io.File;

/**
 * Where a downloaded driver card is stored when the phone stores the file
 * itself (Android 7 to 9). Plain Java: tools/tests/roadcrew-tacho-file-name.test.mjs.
 *
 * The name is C_yyyyMMdd_HHmm_card.ddd by the phone's local time, the usual
 * form for a driver card file, kept as it is. Two downloads can share it - the
 * same card in the same minute, or the hour that repeats when a driver crosses
 * into a time zone an hour behind - and the second used to replace the first.
 * Android 10 and later keep such files apart by themselves, adding " (1)";
 * this does the same, so no download is ever written over another (Galin,
 * 02.10.2026).
 */
public final class RoadCrewTachoFileNames {

	private RoadCrewTachoFileNames() {
	}

	/** {@code name} in {@code dir}, or the first of "name (1)", "name (2)"... not yet taken. */
	public static File unused(File dir, String name) {
		File file = new File(dir, name);
		int dot = name.lastIndexOf('.');
		String base = dot < 0 ? name : name.substring(0, dot);
		String extension = dot < 0 ? "" : name.substring(dot);
		for (int copy = 1; file.exists(); copy++) {
			file = new File(dir, base + " (" + copy + ")" + extension);
		}
		return file;
	}
}
