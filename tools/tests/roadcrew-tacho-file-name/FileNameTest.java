import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import net.osmand.plus.roadcrew.tacho.RoadCrewTachoFileNames;

/**
 * Galin, 02.10.2026: when the date changes and the card goes in again, is
 * anything written over anything? A downloaded card is C_yyyyMMdd_HHmm_card.ddd
 * by the phone's local time. Android 10 and later keep two files of one name
 * apart by themselves; on Android 7 to 9 the second download replaced the
 * first - the same card in the same minute, or the hour that repeats when a
 * driver crosses into a time zone an hour behind.
 */
public class FileNameTest {

	static int passed = 0;

	static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError(what);
		}
		passed++;
	}

	public static void main(String[] args) throws IOException {
		File dir = Files.createTempDirectory("roadcrew-tacho-names").toFile();
		String name = "C_20261002_1015_DE123456.ddd";

		File first = RoadCrewTachoFileNames.unused(dir, name);
		check(first.getName().equals(name), "a free name is used as it is");
		check(first.createNewFile(), "the first download is stored");

		File second = RoadCrewTachoFileNames.unused(dir, name);
		check(second.getName().equals("C_20261002_1015_DE123456 (1).ddd"),
				"the same name again does not replace it: " + second.getName());
		check(second.createNewFile(), "the second download is stored beside it");

		File third = RoadCrewTachoFileNames.unused(dir, name);
		check(third.getName().equals("C_20261002_1015_DE123456 (2).ddd"), "and a third beside both");

		check(RoadCrewTachoFileNames.unused(dir, "C_20261002_1016_DE123456.ddd").getName()
				.equals("C_20261002_1016_DE123456.ddd"), "the next minute has its own name, as it is");

		System.out.println(passed + " file name checks passed");
	}
}
