package net.osmand.plus.roadcrew.tacho;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Which tachographs the card screen shows, and when a dropped start is tried again (ROADMAP 411).
 * Galin, 03.11.2026: pairing is its own place - the "Тахограф" section with a wizard - and
 * "Свали картата" only downloads. Opening the Bluetooth side looks for the paired tachographs
 * near the phone and picks the nearest by itself, its name on the screen, so the driver sees
 * which one it is and can change it; "Нов тахограф" lists only tachographs not paired yet, so a
 * truck parked nearby is never paired without the driver choosing it.
 */
public final class RoadCrewTachoBleChoice {

	/** The unit dropped Tacho Manager's own start twice before its third try went through. */
	public static final int ATTEMPTS = 3;
	public static final long PAUSE_MILLIS = 3000;

	public static final class Candidate {
		public final String address;
		public final String name;
		public final boolean bonded;
		/** Signal heard in the scan; Integer.MIN_VALUE when it was not heard. */
		public final int rssi;

		public Candidate(String address, String name, boolean bonded, int rssi) {
			this.address = address;
			this.name = name;
			this.bonded = bonded;
			this.rssi = rssi;
		}
	}

	private static final Comparator<Candidate> NEAREST_FIRST = (a, b) -> Integer.compare(b.rssi, a.rssi);

	private RoadCrewTachoBleChoice() {
	}

	/** Documented device names: VDO DTCO-<serial> and Stoneridge SE5000-<serial> (ROADMAP 422). */
	public static boolean isTachograph(String name) {
		return name != null && (name.startsWith("DTCO-") || name.startsWith("SE5000-"));
	}

	/** Whether the scan heard it. */
	public static boolean heard(Candidate candidate) {
		return candidate.rssi != Integer.MIN_VALUE;
	}

	/** @param bonded the phone's paired devices, any kind */
	public static List<Candidate> pairedTachographs(List<Candidate> bonded) {
		List<Candidate> paired = new ArrayList<>();
		for (Candidate candidate : bonded) {
			if (isTachograph(candidate.name)) {
				paired.add(candidate);
			}
		}
		return paired;
	}

	/**
	 * The paired tachographs the scan heard, nearest first: the first is the one picked by itself.
	 *
	 * @param bonded the phone's paired devices, any kind
	 * @param seen   what the scan heard, any kind, with its signal
	 */
	public static List<Candidate> nearbyPaired(List<Candidate> bonded, List<Candidate> seen) {
		List<Candidate> paired = pairedTachographs(bonded);
		List<Candidate> near = new ArrayList<>();
		for (Candidate candidate : seen) {
			if (isTachograph(candidate.name) && find(paired, candidate.address) != null) {
				near.add(candidate);
			}
		}
		near.sort(NEAREST_FIRST);
		return near;
	}

	/** Every paired tachograph for the "Тахограф" section: the heard ones first, nearest first, with their signal. */
	public static List<Candidate> pairedWithSignal(List<Candidate> bonded, List<Candidate> seen) {
		List<Candidate> listed = new ArrayList<>();
		for (Candidate candidate : pairedTachographs(bonded)) {
			Candidate heard = find(seen, candidate.address);
			listed.add(heard == null ? candidate : new Candidate(candidate.address, candidate.name, true, heard.rssi));
		}
		// Stable: the ones not heard keep the phone's order after the heard ones.
		listed.sort(NEAREST_FIRST);
		return listed;
	}

	/** For "Нов тахограф": the tachographs the scan heard that the phone has not paired, nearest first. */
	public static List<Candidate> unpaired(List<Candidate> bonded, List<Candidate> seen) {
		List<Candidate> paired = pairedTachographs(bonded);
		List<Candidate> fresh = new ArrayList<>();
		for (Candidate candidate : seen) {
			if (isTachograph(candidate.name) && find(paired, candidate.address) == null) {
				fresh.add(candidate);
			}
		}
		fresh.sort(NEAREST_FIRST);
		return fresh;
	}

	/**
	 * Try again after attempt {@code attempt} failed? Only while no card data has come - a part
	 * of the card already in means the unit is busy with this download - and not for a cancel or
	 * for a device that does not speak VDO's pipe.
	 */
	public static boolean again(int attempt, boolean cardDataSeen, String error) {
		if (attempt >= ATTEMPTS || cardDataSeen) {
			return false;
		}
		String message = error == null ? "" : error;
		return !message.contains("cancelled") && !message.contains("Unsupported")
				&& !message.contains("Pair the tachograph first");
	}

	private static Candidate find(List<Candidate> list, String address) {
		for (Candidate candidate : list) {
			if (candidate.address.equalsIgnoreCase(address)) {
				return candidate;
			}
		}
		return null;
	}
}
