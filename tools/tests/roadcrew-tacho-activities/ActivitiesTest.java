import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Card;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Day;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Kind;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Place;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Segment;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * ROADMAP 379: the driver's activities and places read from a card file. Synthetic files only -
 * a real card file holds a name, a card number and places, and never enters this repository.
 * The 13.08 day repeats the pattern of Galin's real card, which Tacho Manager showed the same.
 */
public class ActivitiesTest {
	static final ZoneId SOFIA = ZoneId.of("Europe/Sofia");
	static final int REST = 0, AVAILABLE = 1, WORK = 2, DRIVING = 3;
	static int cases;

	static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError(what);
		}
		cases++;
	}

	static long day(int y, int m, int d) {
		return LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
	}

	/** slot, crew/known, card not inserted, activity, minute of the UTC day. */
	static int change(int slot, int crew, int out, int activity, int minute) {
		return slot << 15 | crew << 14 | out << 13 | activity << 11 | minute;
	}

	static int change(int activity, int minute) {
		return change(0, 0, 0, activity, minute);
	}

	record Rec(long date, int distance, int[] changes) {
	}

	/** CardDriverActivity: the two pointers and a cyclic buffer of daily records starting at {@code start}. */
	static byte[] activity(int size, int start, List<Rec> recs) {
		byte[] buf = new byte[size];
		int pos = start;
		int prev = 0;
		int oldest = start;
		int newest = start;
		for (Rec r : recs) {
			int len = 12 + 2 * r.changes.length;
			byte[] rec = new byte[len];
			put(rec, 0, prev, 2);
			put(rec, 2, len, 2);
			put(rec, 4, r.date, 4);
			put(rec, 8, 0x0001, 2);
			put(rec, 10, r.distance, 2);
			for (int i = 0; i < r.changes.length; i++) {
				put(rec, 12 + 2 * i, r.changes[i], 2);
			}
			for (int i = 0; i < len; i++) {
				buf[(pos + i) % size] = rec[i];
			}
			newest = pos;
			prev = len;
			pos = (pos + len) % size;
		}
		byte[] ef = new byte[4 + size];
		put(ef, 0, oldest, 2);
		put(ef, 2, newest, 2);
		System.arraycopy(buf, 0, ef, 4, size);
		return ef;
	}

	static void put(byte[] b, int at, long value, int n) {
		for (int i = 0; i < n; i++) {
			b[at + i] = (byte) (value >> (8 * (n - 1 - i)));
		}
	}

	record Pl(long time, int type, int country, int odometer) {
	}

	static byte[] places(boolean secondGeneration, List<Pl> list, int capacity) {
		int rec = secondGeneration ? 21 : 10;
		int head = secondGeneration ? 2 : 1;
		byte[] ef = new byte[head + rec * capacity];
		put(ef, 0, list.size() - 1, head);
		for (int i = 0; i < list.size(); i++) {
			Pl p = list.get(i);
			int at = head + i * rec;
			put(ef, at, p.time, 4);
			ef[at + 4] = (byte) p.type;
			ef[at + 5] = (byte) p.country;
			put(ef, at + 7, p.odometer, 3);
		}
		return ef;
	}

	/** A DDD: blocks of FID, type (0/1 Gen1 data/signature, 2/3 Gen2), length, data. */
	static byte[] ddd(Object... blocks) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		block(out, 0x0002, 0, new byte[25]);
		for (int i = 0; i < blocks.length; i += 3) {
			int fid = (Integer) blocks[i];
			int type = (Integer) blocks[i + 1];
			byte[] data = (byte[]) blocks[i + 2];
			block(out, fid, type, data);
			block(out, fid, type + 1, new byte[type < 2 ? 128 : 64]);
		}
		return out.toByteArray();
	}

	static void block(ByteArrayOutputStream out, int fid, int type, byte[] data) {
		out.write(fid >> 8);
		out.write(fid);
		out.write(type);
		out.write(data.length >> 8);
		out.write(data.length);
		out.write(data, 0, data.length);
	}

	static List<Rec> augustDays() {
		List<Rec> recs = new ArrayList<>();
		recs.add(new Rec(day(2026, 8, 12), 300, new int[]{change(REST, 0), change(DRIVING, 360), change(WORK, 700),
				change(DRIVING, 720), change(REST, 1089)}));
		// 13.08: as on Galin's card - 05:34 to 17:09 UTC, driving 6:43, other work 0:12.
		recs.add(new Rec(day(2026, 8, 13), 455, new int[]{change(REST, 0), change(DRIVING, 334), change(WORK, 337),
				change(DRIVING, 342), change(WORK, 356), change(DRIVING, 358), change(REST, 461), change(DRIVING, 475),
				change(WORK, 516), change(DRIVING, 518), change(WORK, 525), change(DRIVING, 528), change(REST, 530),
				change(DRIVING, 796), change(REST, 1029)}));
		recs.add(new Rec(day(2026, 8, 14), 120, new int[]{change(REST, 0), change(DRIVING, 346), change(REST, 600)}));
		return recs;
	}

	static Day find(List<Day> days, LocalDate date) {
		for (Day d : days) {
			if (d.date.equals(date)) {
				return d;
			}
		}
		throw new AssertionError("no day " + date);
	}

	static String hm(long epochMinute) {
		return java.time.Instant.ofEpochSecond(epochMinute * 60).atZone(SOFIA).toLocalTime().toString();
	}

	static int sum(Day d) {
		int total = 0;
		for (Segment s : d.segments) {
			total += (int) (s.to - s.from);
		}
		return total;
	}

	public static void main(String[] args) throws Exception {
		// 1. The 13.08 day, the 13.08 record lying across the end of the cyclic buffer (size 100, start 70).
		byte[] g2 = activity(100, 70, augustDays());
		Card card = RoadCrewTachoActivities.read(ddd(0x0504, 2, g2), Long.MAX_VALUE);
		check(card.secondGeneration, "the Gen2 copy is read");
		check(card.complete, "the whole buffer walked");
		List<Day> days = RoadCrewTachoActivities.days(card, SOFIA);
		Day d13 = find(days, LocalDate.of(2026, 8, 13));
		check(hm(d13.start).equals("08:34"), "start 08:34 by the phone's clock, was " + hm(d13.start));
		check(hm(d13.end).equals("20:09"), "end 20:09, was " + hm(d13.end));
		check(d13.driving == 403, "driving 6:43, was " + d13.driving);
		check(d13.work == 12, "other work 0:12, was " + d13.work);
		check(d13.available == 0, "no availability");
		check(sum(d13) == 1440, "a summer day is 24 hours");
		check(RoadCrewTachoActivities.restBefore(card, d13.start) == 685, "rest before 11:25, as Tacho Manager");
		check(RoadCrewTachoActivities.restAfter(card, d13.end) == 757, "rest after 12:37, as Tacho Manager");
		check(card.distanceKm.get(LocalDate.of(2026, 8, 13)) == 455, "the day's distance");

		// 2. A Gen2 card keeps two copies. Every tachograph writes the Gen1 one, only a smart one the
		//    Gen2 one: on Galin's card (26.09) 18-19.09, in a first-generation tachograph, are in Gen1
		//    alone, and on 20.09 Gen1 has the card inserted where Gen2 has it out. So the days of both
		//    are read, a day in both from Gen1 (ROADMAP 393 - this replaces "Gen2 wins over Gen1").
		List<Rec> gen1Days = List.of(augustDays().get(0), augustDays().get(1));
		List<Rec> gen2Days = List.of(new Rec(day(2026, 8, 13), 0, new int[]{change(0, 0, 1, REST, 0)}), augustDays().get(2));
		Card both = RoadCrewTachoActivities.read(ddd(0x0504, 0, activity(200, 0, gen1Days), 0x0504, 2,
				activity(100, 0, gen2Days)), Long.MAX_VALUE);
		List<Day> bothDays = RoadCrewTachoActivities.days(both, SOFIA);
		check(both.complete, "both copies walked");
		check(find(bothDays, LocalDate.of(2026, 8, 13)).driving == 403, "a day in both copies is read from Gen1");
		check(both.distanceKm.get(LocalDate.of(2026, 8, 13)) == 455, "and its distance");
		check(find(bothDays, LocalDate.of(2026, 8, 12)).driving == 709, "a day only in Gen1");
		check(find(bothDays, LocalDate.of(2026, 8, 14)).driving == 254, "a day only in Gen2");
		Card g1 = RoadCrewTachoActivities.read(ddd(0x0504, 0, activity(200, 0, augustDays())), Long.MAX_VALUE);
		check(!g1.secondGeneration, "Gen1 only");
		check(find(RoadCrewTachoActivities.days(g1, SOFIA), LocalDate.of(2026, 8, 13)).driving == 403, "Gen1 read the same");

		// 3. The co-driver seat is still the holder's activity.
		List<Rec> crew = List.of(new Rec(day(2026, 8, 20), 0, new int[]{change(REST, 0), change(1, 1, 0, DRIVING, 300),
				change(1, 1, 0, AVAILABLE, 400), change(0, 1, 0, DRIVING, 500), change(REST, 600)}));
		Day d20 = find(RoadCrewTachoActivities.days(RoadCrewTachoActivities.read(ddd(0x0504, 2, activity(100, 0, crew)),
				Long.MAX_VALUE), SOFIA), LocalDate.of(2026, 8, 20));
		check(d20.driving == 200, "driving in both seats, was " + d20.driving);
		check(d20.available == 100, "availability as co-driver");

		// 4. Card not inserted: unknown unless entered by hand.
		List<Rec> out = List.of(new Rec(day(2026, 8, 21), 0, new int[]{change(0, 0, 1, REST, 0),
				change(0, 1, 1, WORK, 300), change(0, 0, 0, DRIVING, 400), change(0, 0, 0, REST, 500)}));
		Card outCard = RoadCrewTachoActivities.read(ddd(0x0504, 2, activity(100, 0, out)), Long.MAX_VALUE);
		List<Segment> segs = outCard.segments;
		check(!segs.get(0).cardInserted && !segs.get(0).known, "card out, nothing entered: unknown");
		check(!segs.get(1).cardInserted && segs.get(1).known && segs.get(1).kind == Kind.WORK, "entered by hand: known");
		check(segs.get(2).cardInserted && segs.get(2).known, "card in: known");

		// 5. The newest activity ends at the download.
		long until = day(2026, 8, 14) / 60 + 420;
		Card cut = RoadCrewTachoActivities.read(ddd(0x0504, 2, g2), until);
		Segment last = cut.segments.get(cut.segments.size() - 1);
		check(last.to == until && last.kind == Kind.DRIVING, "cut at the download, mid-drive");
		check(find(RoadCrewTachoActivities.days(cut, SOFIA), LocalDate.of(2026, 8, 14)).driving == 420 - 346,
				"only the driving before the download");

		// 6. Summer time ends on 25.10.2026: that day is 25 hours by the phone's clock.
		List<Rec> autumn = List.of(new Rec(day(2026, 10, 24), 0, new int[]{change(REST, 0)}),
				new Rec(day(2026, 10, 25), 0, new int[]{change(REST, 0), change(DRIVING, 600), change(REST, 700)}),
				new Rec(day(2026, 10, 26), 0, new int[]{change(REST, 0)}),
				new Rec(day(2026, 10, 27), 0, new int[]{change(REST, 0)}));
		List<Day> ad = RoadCrewTachoActivities.days(RoadCrewTachoActivities.read(ddd(0x0504, 2, activity(120, 0, autumn)),
				Long.MAX_VALUE), SOFIA);
		check(sum(find(ad, LocalDate.of(2026, 10, 25))) == 1500, "25.10.2026 has 25 hours");
		check(sum(find(ad, LocalDate.of(2026, 10, 26))) == 1440, "26.10.2026 has 24");
		check(find(ad, LocalDate.of(2026, 10, 25)).driving == 100, "the drive on the long day");

		// 7. Places, Gen2 21-byte and Gen1 10-byte records; empty records skipped.
		long t1 = day(2026, 8, 13) + 334 * 60;
		long t2 = day(2026, 8, 13) + 1029 * 60;
		List<Pl> pl = List.of(new Pl(t1, 0, 7, 833348), new Pl(t2, 1, 7, 833803));
		for (boolean gen2 : new boolean[]{true, false}) {
			Card withPlaces = RoadCrewTachoActivities.read(ddd(0x0504, gen2 ? 2 : 0, g2, 0x0506, gen2 ? 2 : 0,
					places(gen2, pl, 6)), Long.MAX_VALUE);
			List<Place> ps = withPlaces.places;
			check(ps.size() == 2, (gen2 ? "Gen2" : "Gen1") + " places, empty ones skipped: " + ps.size());
			check(ps.get(0).begin && ps.get(0).country == 7 && ps.get(0).odometerKm == 833348
					&& ps.get(0).minute == t1 / 60, "begin, Bulgaria, 833348 km");
			check(!ps.get(1).begin && ps.get(1).odometerKm == 833803, "end, 833803 km");
		}

		// 8. A damaged record stops the walk: what was read is kept and the file is marked incomplete.
		byte[] broken = g2.clone();
		int at13 = 4 + 92;  // the 13.08 record starts at 92 in the buffer
		put(broken, at13 + 2, 5, 2);  // recordLength 5 cannot be
		Card damaged = RoadCrewTachoActivities.read(ddd(0x0504, 2, broken), Long.MAX_VALUE);
		check(!damaged.complete, "marked incomplete");
		check(damaged.distanceKm.containsKey(LocalDate.of(2026, 8, 14)), "the newest day still read");
		check(!damaged.distanceKm.containsKey(LocalDate.of(2026, 8, 12)), "nothing invented behind the damage");

		// 9. No activity file: an honest error, not an empty card.
		boolean threw = false;
		try {
			RoadCrewTachoActivities.read(ddd(0x0506, 2, places(true, pl, 6)), Long.MAX_VALUE);
		} catch (java.io.IOException e) {
			threw = true;
		}
		check(threw, "a file without EF 0504 is an error");

		// 10. A Gen2 card used only in first-generation tachographs (a driver's, 10.10: Gen1 400 days,
		//     every Gen2 copy all zeros). An unused copy is not damage: the days and places are Gen1's.
		byte[] unusedActivity = new byte[4 + 100];
		byte[] unusedPlaces = new byte[2 + 21 * 6];
		Card firstGen = RoadCrewTachoActivities.read(ddd(0x0504, 0, activity(200, 0, augustDays()),
				0x0506, 0, places(false, pl, 6), 0x0504, 2, unusedActivity, 0x0506, 2, unusedPlaces), Long.MAX_VALUE);
		check(firstGen.secondGeneration, "still a Gen2 card");
		check(firstGen.complete, "an all-zero Gen2 copy is not damage");
		check(find(RoadCrewTachoActivities.days(firstGen, SOFIA), LocalDate.of(2026, 8, 13)).driving == 403,
				"the days are read from Gen1");
		check(firstGen.places.size() == 2 && firstGen.places.get(0).odometerKm == 833348, "the places from Gen1");

		// 11. The same places in both copies are one place each; a card never used has no days and
		//     is not damaged either.
		Card twice = RoadCrewTachoActivities.read(ddd(0x0504, 2, g2, 0x0506, 0, places(false, pl, 6),
				0x0506, 2, places(true, pl, 6)), Long.MAX_VALUE);
		check(twice.places.size() == 2, "a place written to both copies counted once: " + twice.places.size());
		Card unused = RoadCrewTachoActivities.read(ddd(0x0504, 0, new byte[4 + 60], 0x0504, 2, unusedActivity),
				Long.MAX_VALUE);
		check(unused.segments.isEmpty() && unused.complete, "a card never used: no days, not damaged");

		// 12. Codex's review (10.10, tools/probes/tacho-copy-review): a copy too short to
		//     hold its pointers and one record is malformed, zeros or not; the other copy's
		//     days stay. Two separate events in one minute stay two, within a copy and across.
		for (int length : new int[]{0, 1, 4, 15}) {
			check(!RoadCrewTachoActivities.read(ddd(0x0504, 0, new byte[length]), Long.MAX_VALUE).complete,
					"a " + length + "-byte copy is malformed, not unused");
		}
		Card shortGen2 = RoadCrewTachoActivities.read(ddd(0x0504, 0, activity(200, 0, augustDays()),
				0x0504, 2, new byte[4]), Long.MAX_VALUE);
		check(!shortGen2.complete, "a 4-byte Gen2 copy is flagged");
		check(find(RoadCrewTachoActivities.days(shortGen2, SOFIA), LocalDate.of(2026, 8, 13)).driving == 403,
				"and Gen1's days are kept");
		long ten = day(2026, 8, 13) + 600 * 60;
		List<Pl> separate = List.of(new Pl(ten + 1, 0, 7, 1000), new Pl(ten + 50, 0, 7, 1001));
		check(RoadCrewTachoActivities.read(ddd(0x0504, 2, g2, 0x0506, 2, places(true, separate, 6)),
				Long.MAX_VALUE).places.size() == 2, "two events in one minute in Gen2 stay two");
		check(RoadCrewTachoActivities.read(ddd(0x0504, 0, g2, 0x0506, 0, places(false, separate.subList(0, 1), 6),
				0x0506, 2, places(true, separate.subList(1, 2), 6)), Long.MAX_VALUE).places.size() == 2,
				"different seconds and odometers across copies are two places");

		System.out.println(cases + " card activity checks passed");
	}
}
