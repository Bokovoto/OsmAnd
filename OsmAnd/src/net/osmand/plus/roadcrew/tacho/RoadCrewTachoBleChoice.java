package net.osmand.plus.roadcrew.tacho;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Which tachograph the one "Свали картата" button reaches, and when a dropped start is tried
 * again (ROADMAP 411). Galin, 11.10.2026: no "paired tachograph" / "first pairing" buttons -
 * found and paired by itself, as Tacho Manager does, and only tachographs listed.
 */
public final class RoadCrewTachoBleChoice {

	/** The unit dropped Tacho Manager's own start twice before its third try went through. */
	public static final int ATTEMPTS = 3;
	public static final long PAUSE_MILLIS = 3000;

	public enum Kind { USE, PAIR, ASK, NONE }

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

	public static final class Decision {
		public final Kind kind;
		/** USE and PAIR: the one; ASK: nearest first; NONE: empty. */
		public final List<Candidate> candidates;

		Decision(Kind kind, List<Candidate> candidates) {
			this.kind = kind;
			this.candidates = Collections.unmodifiableList(candidates);
		}
	}

	private RoadCrewTachoBleChoice() {
	}

	/** VDO's DTCO 4.1 names itself DTCO-<serial> (ROADMAP 407); that pipe is the one we speak. */
	public static boolean isTachograph(String name) {
		return name != null && name.startsWith("DTCO-");
	}

	/**
	 * @param bonded the phone's paired devices, any kind
	 * @param seen   what a short scan heard, any kind, with its signal
	 */
	public static Decision decide(List<Candidate> bonded, List<Candidate> seen) {
		List<Candidate> pairedTachographs = new ArrayList<>();
		for (Candidate candidate : bonded) {
			if (isTachograph(candidate.name)) {
				pairedTachographs.add(candidate);
			}
		}
		List<Candidate> heardPaired = new ArrayList<>();
		List<Candidate> heardNew = new ArrayList<>();
		for (Candidate candidate : seen) {
			if (!isTachograph(candidate.name)) {
				continue;
			}
			if (contains(pairedTachographs, candidate.address)) {
				heardPaired.add(candidate);
			} else {
				heardNew.add(candidate);
			}
		}
		Comparator<Candidate> nearestFirst = (a, b) -> Integer.compare(b.rssi, a.rssi);
		if (!heardPaired.isEmpty()) {
			heardPaired.sort(nearestFirst);
			return new Decision(Kind.USE, Collections.singletonList(heardPaired.get(0)));
		}
		if (heardNew.size() == 1) {
			return new Decision(Kind.PAIR, heardNew);
		}
		if (heardNew.size() > 1) {
			heardNew.sort(nearestFirst);
			return new Decision(Kind.ASK, heardNew);
		}
		if (pairedTachographs.size() == 1) {
			return new Decision(Kind.USE, pairedTachographs);
		}
		return new Decision(Kind.NONE, new ArrayList<>());
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

	private static boolean contains(List<Candidate> list, String address) {
		for (Candidate candidate : list) {
			if (candidate.address.equalsIgnoreCase(address)) {
				return true;
			}
		}
		return false;
	}
}
