package net.osmand.plus.roadcrew.tacho;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The driver's activities (EF 0504, CardDriverActivity) and places (EF 0506) read back from a
 * downloaded card file, and cut into days by the phone's clock. Plain Java, no Android -
 * tools/tests/roadcrew-tacho-activities.test.mjs (ROADMAP 379).
 * <p>
 * Galin, 09.10.2026: "Анализ" beside "Изпрати" on every downloaded file. Checked on his real
 * card against Tacho Manager: 13.08.2026 driving 6:43, other work 0:12, the same segments.
 * <p>
 * Annex 1C: the activity file is two pointers and a cyclic buffer of daily records -
 * previousRecordLength, recordLength, the day's date (UTC), presence counter, distance, then
 * ActivityChangeInfo of two bytes each: slot, driving status, card status, activity, and the
 * minute of the UTC day. Every change on a driver card is the holder's; the slot only says
 * which seat. Nothing is invented: a damaged record stops the walk and the card is marked
 * incomplete, and time with the card out that nobody entered stays unknown.
 */
public final class RoadCrewTachoActivities {

	public enum Kind { REST, AVAILABLE, WORK, DRIVING }

	public static final class Segment {
		/** Epoch minutes, UTC; {@code to} is exclusive. */
		public final long from;
		public final long to;
		public final Kind kind;
		public final boolean cardInserted;
		/** False for card-out time that was not entered by hand: the activity is not known. */
		public final boolean known;

		Segment(long from, long to, Kind kind, boolean cardInserted, boolean known) {
			this.from = from;
			this.to = to;
			this.kind = kind;
			this.cardInserted = cardInserted;
			this.known = known;
		}

		Segment cut(long a, long b) {
			return new Segment(Math.max(from, a), Math.min(to, b), kind, cardInserted, known);
		}

		boolean sameAs(Segment other) {
			return kind == other.kind && cardInserted == other.cardInserted && known == other.known;
		}
	}

	public static final class Place {
		/** Epoch minute, UTC. */
		public final long minute;
		public final boolean begin;
		/** NationNumeric of Annex 1C: 7 is Bulgaria. */
		public final int country;
		public final int odometerKm;

		Place(long minute, boolean begin, int country, int odometerKm) {
			this.minute = minute;
			this.begin = begin;
			this.country = country;
			this.odometerKm = odometerKm;
		}
	}

	public static final class Card {
		public final List<Segment> segments;
		public final List<Place> places;
		public final boolean secondGeneration;
		/** False when a damaged record stopped the walk: older days are missing, not invented. */
		public final boolean complete;
		/** The card's own distance per UTC day, in km. */
		public final Map<LocalDate, Integer> distanceKm;

		Card(List<Segment> segments, List<Place> places, boolean secondGeneration, boolean complete,
				Map<LocalDate, Integer> distanceKm) {
			this.segments = segments;
			this.places = places;
			this.secondGeneration = secondGeneration;
			this.complete = complete;
			this.distanceKm = distanceKm;
		}
	}

	public static final class Day {
		public final LocalDate date;
		/** Cut to this day by the phone's clock. */
		public final List<Segment> segments;
		/** Epoch minutes of the first and last known activity other than rest; -1 when none. */
		public final long start;
		public final long end;
		public final int driving;
		public final int work;
		public final int available;
		public final int rest;
		/** Card out and nothing entered. */
		public final int unknown;

		Day(LocalDate date, List<Segment> segments) {
			this.date = date;
			this.segments = segments;
			long first = -1;
			long last = -1;
			int[] minutes = new int[Kind.values().length];
			int notKnown = 0;
			for (Segment s : segments) {
				int length = (int) (s.to - s.from);
				if (!s.known) {
					notKnown += length;
					continue;
				}
				minutes[s.kind.ordinal()] += length;
				if (s.kind != Kind.REST) {
					if (first < 0) {
						first = s.from;
					}
					last = s.to;
				}
			}
			this.start = first;
			this.end = last;
			this.driving = minutes[Kind.DRIVING.ordinal()];
			this.work = minutes[Kind.WORK.ordinal()];
			this.available = minutes[Kind.AVAILABLE.ordinal()];
			this.rest = minutes[Kind.REST.ordinal()];
			this.unknown = notKnown;
		}
	}

	private static final int FID_ACTIVITY = 0x0504;
	private static final int FID_PLACES = 0x0506;
	private static final int DAY_MINUTES = 1440;

	private RoadCrewTachoActivities() {
	}

	/**
	 * Reads a DDD file. {@code untilMinute} is the download, in epoch minutes: the newest activity
	 * lasts until then, not to the end of its day ({@link Long#MAX_VALUE} when unknown).
	 */
	public static Card read(byte[] ddd, long untilMinute) throws IOException {
		byte[][] found = new byte[4][];
		int i = 0;
		while (i + 5 <= ddd.length) {
			int fid = u16(ddd, i);
			int type = ddd[i + 2] & 255;
			int length = u16(ddd, i + 3);
			if (i + 5 + length > ddd.length) {
				throw new IOException("card file cut short");
			}
			int slot = fid == FID_ACTIVITY ? 0 : fid == FID_PLACES ? 2 : -1;
			if (slot >= 0 && (type == 0 || type == 2)) {
				found[slot + type / 2] = Arrays.copyOfRange(ddd, i + 5, i + 5 + length);
			}
			i += 5 + length;
		}
		boolean secondGeneration = found[1] != null;
		if (found[0] == null && found[1] == null) {
			throw new IOException("no driver activity data (EF 0504) in the card file");
		}
		// A Gen2 card keeps two copies of its activities and places. Every tachograph writes the Gen1
		// copy, only a smart tachograph the Gen2 one: a card used in first-generation tachographs alone
		// has its Gen2 copies all zeros (a driver's card, 10.10), and a day in one is in the Gen1 copy only
		// (Galin's card, 18-20.09). So the days of both copies are read, a day in both from Gen1, and an
		// all-zero copy is an unused one, not damage (ROADMAP 393).
		Walk gen1 = walk(found[0]);
		Walk gen2 = walk(found[1]);
		List<Record> records = new ArrayList<>(gen1.records);
		Set<Long> gen1Days = new HashSet<>();
		for (Record record : gen1.records) {
			gen1Days.add(record.dayMinute);
		}
		for (Record record : gen2.records) {
			if (!gen1Days.contains(record.dayMinute)) {
				records.add(record);
			}
		}
		Map<LocalDate, Integer> distance = new TreeMap<>();
		List<Segment> segments = new ArrayList<>();
		readActivity(records, untilMinute, segments, distance);
		List<Place> places = new ArrayList<>();
		if (found[2] != null) {
			readPlaces(found[2], 1, 10, places);
		}
		if (found[3] != null) {
			List<Place> second = new ArrayList<>();
			readPlaces(found[3], 2, 21, second);
			for (Place place : second) {
				if (!hasPlace(places, place)) {
					places.add(place);
				}
			}
		}
		places.sort((a, b) -> Long.compare(a.minute, b.minute));
		return new Card(Collections.unmodifiableList(segments), Collections.unmodifiableList(places),
				secondGeneration, gen1.complete && gen2.complete, Collections.unmodifiableMap(distance));
	}

	private static boolean hasPlace(List<Place> places, Place place) {
		for (Place p : places) {
			if (p.minute == place.minute && p.begin == place.begin && p.country == place.country) {
				return true;
			}
		}
		return false;
	}

	private static final class Record {
		final long dayMinute;
		final int[] changes;
		final int distance;

		Record(long dayMinute, int[] changes, int distance) {
			this.dayMinute = dayMinute;
			this.changes = changes;
			this.distance = distance;
		}
	}

	/** One copy's daily records, newest first; an absent or all-zero copy has none and is complete. */
	private static final class Walk {
		final List<Record> records;
		final boolean complete;

		Walk(List<Record> records, boolean complete) {
			this.records = records;
			this.complete = complete;
		}
	}

	private static Walk walk(byte[] ef) {
		List<Record> records = new ArrayList<>();
		if (ef == null || allZero(ef)) {
			return new Walk(records, true);
		}
		if (ef.length < 4 + 12) {
			return new Walk(records, false);
		}
		int oldest = u16(ef, 0);
		int newest = u16(ef, 2);
		int size = ef.length - 4;
		if (oldest >= size || newest >= size) {
			return new Walk(records, false);
		}
		boolean complete = true;
		int pos = newest;
		for (int guard = size / 12 + 1; ; guard--) {
			if (guard <= 0) {
				complete = false;
				break;
			}
			int previous = cyclic16(ef, size, pos);
			int length = cyclic16(ef, size, pos + 2);
			if (length < 12 || length > size || (length - 12) % 2 != 0) {
				complete = false;
				break;
			}
			long date = cyclic32(ef, size, pos + 4);
			int[] changes = new int[(length - 12) / 2];
			boolean valid = changes.length > 0;
			for (int k = 0; k < changes.length && valid; k++) {
				changes[k] = cyclic16(ef, size, pos + 12 + 2 * k);
				valid = (changes[k] & 0x7FF) < DAY_MINUTES;
			}
			if (!valid) {
				complete = false;
				break;
			}
			long dayStart = date - Math.floorMod(date, 86400L);
			records.add(new Record(dayStart / 60, changes, cyclic16(ef, size, pos + 10)));
			if (pos == oldest) {
				break;
			}
			if (previous == 0) {
				complete = false;
				break;
			}
			pos = Math.floorMod(pos - previous, size);
		}
		return new Walk(records, complete);
	}

	private static boolean allZero(byte[] bytes) {
		for (byte b : bytes) {
			if (b != 0) {
				return false;
			}
		}
		return true;
	}

	private static void readActivity(List<Record> records, long untilMinute, List<Segment> out, Map<LocalDate, Integer> distance) {
		records.sort((a, b) -> Long.compare(a.dayMinute, b.dayMinute));
		long previousEnd = -1;
		for (Record record : records) {
			distance.put(LocalDate.ofEpochDay(record.dayMinute / DAY_MINUTES), record.distance);
			long dayEnd = record.dayMinute + DAY_MINUTES;
			if (previousEnd >= 0 && record.dayMinute > previousEnd) {
				// Whole days without a record: the card was out and nothing was entered.
				append(out, new Segment(previousEnd, record.dayMinute, Kind.REST, false, false), untilMinute);
			}
			for (int k = 0; k < record.changes.length; k++) {
				int value = record.changes[k];
				long from = record.dayMinute + (value & 0x7FF);
				long to = k + 1 < record.changes.length ? record.dayMinute + (record.changes[k + 1] & 0x7FF) : dayEnd;
				boolean inserted = (value & 0x2000) == 0;
				boolean known = inserted || (value & 0x4000) != 0;
				Kind kind = Kind.values()[(value >> 11) & 3];
				append(out, new Segment(from, to, kind, inserted, known), untilMinute);
			}
			previousEnd = Math.max(previousEnd, dayEnd);
		}
	}

	private static void append(List<Segment> out, Segment s, long untilMinute) {
		long to = Math.min(s.to, untilMinute);
		long from = out.isEmpty() ? s.from : Math.max(s.from, out.get(out.size() - 1).to);
		if (to <= from) {
			return;
		}
		Segment piece = new Segment(from, to, s.kind, s.cardInserted, s.known);
		if (!out.isEmpty()) {
			Segment last = out.get(out.size() - 1);
			if (last.to == piece.from && last.sameAs(piece)) {
				out.set(out.size() - 1, new Segment(last.from, piece.to, last.kind, last.cardInserted, last.known));
				return;
			}
		}
		out.add(piece);
	}

	private static void readPlaces(byte[] ef, int head, int recordLength, List<Place> out) {
		for (int at = head; at + recordLength <= ef.length; at += recordLength) {
			long time = u32(ef, at);
			if (time == 0) {
				continue;
			}
			int type = ef[at + 4] & 255;
			int odometer = ((ef[at + 7] & 255) << 16) | ((ef[at + 8] & 255) << 8) | (ef[at + 9] & 255);
			// '00' begin, '01' end; '02'/'03' entered by hand; '04'/'05' assumed by the tachograph.
			out.add(new Place(time / 60, type % 2 == 0, ef[at + 5] & 255, odometer));
		}
		out.sort((a, b) -> Long.compare(a.minute, b.minute));
	}

	/** The card's days by the phone's clock, from the first to the last day with data. */
	public static List<Day> days(Card card, ZoneId zone) {
		List<Day> days = new ArrayList<>();
		if (card.segments.isEmpty()) {
			return days;
		}
		LocalDate first = localDate(card.segments.get(0).from, zone);
		LocalDate last = localDate(card.segments.get(card.segments.size() - 1).to - 1, zone);
		int index = 0;
		for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
			long a = date.atStartOfDay(zone).toEpochSecond() / 60;
			long b = date.plusDays(1).atStartOfDay(zone).toEpochSecond() / 60;
			while (index < card.segments.size() && card.segments.get(index).to <= a) {
				index++;
			}
			List<Segment> cut = new ArrayList<>();
			for (int k = index; k < card.segments.size() && card.segments.get(k).from < b; k++) {
				cut.add(card.segments.get(k).cut(a, b));
			}
			days.add(new Day(date, Collections.unmodifiableList(cut)));
		}
		return days;
	}

	/**
	 * Minutes of uninterrupted rest ending at {@code minute}. Card-out time counts as rest, as
	 * Tacho Manager and DCR count it: a driver at home does not insert the card.
	 */
	public static int restBefore(Card card, long minute) {
		int total = 0;
		long at = minute;
		for (int k = card.segments.size() - 1; k >= 0; k--) {
			Segment s = card.segments.get(k);
			if (s.from >= at) {
				continue;
			}
			if (s.to != at || !isRest(s)) {
				break;
			}
			total += (int) (s.to - s.from);
			at = s.from;
		}
		return total;
	}

	/** Minutes of uninterrupted rest starting at {@code minute}; card-out time counts as rest. */
	public static int restAfter(Card card, long minute) {
		int total = 0;
		long at = minute;
		for (Segment s : card.segments) {
			if (s.to <= at) {
				continue;
			}
			if (s.from != at || !isRest(s)) {
				break;
			}
			total += (int) (s.to - s.from);
			at = s.to;
		}
		return total;
	}

	private static boolean isRest(Segment s) {
		return s.kind == Kind.REST || !s.known;
	}

	private static LocalDate localDate(long epochMinute, ZoneId zone) {
		return Instant.ofEpochSecond(epochMinute * 60).atZone(zone).toLocalDate();
	}

	private static int u16(byte[] b, int at) {
		return ((b[at] & 255) << 8) | (b[at + 1] & 255);
	}

	private static long u32(byte[] b, int at) {
		return ((long) (b[at] & 255) << 24) | ((b[at + 1] & 255) << 16) | ((b[at + 2] & 255) << 8) | (b[at + 3] & 255);
	}

	/** A byte of the cyclic buffer, which starts after the two pointers. */
	private static int cyclic(byte[] ef, int size, int at) {
		return ef[4 + Math.floorMod(at, size)] & 255;
	}

	private static int cyclic16(byte[] ef, int size, int at) {
		return (cyclic(ef, size, at) << 8) | cyclic(ef, size, at + 1);
	}

	private static long cyclic32(byte[] ef, int size, int at) {
		return ((long) cyclic16(ef, size, at) << 16) | cyclic16(ef, size, at + 2);
	}
}
