import java.util.*;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoBleChoice;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoBleChoice.Candidate;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoBleChoice.Decision;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoBleChoice.Kind;

/** Which tachograph the one "Свали картата" button reaches (Galin, 11.10.2026: no paired/pairing buttons). */
public class ChoiceTest {
  static int checks;
  static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
  static Candidate c(String address, String name, boolean bonded, int rssi) { return new Candidate(address, name, bonded, rssi); }

  public static void main(String[] args) {
    Candidate truck = c("00:60:37:4B:39:0B", "DTCO-20908380", true, -60);
    Candidate oldTruck = c("00:60:37:11:22:33", "DTCO-11111111", true, Integer.MIN_VALUE);
    Candidate next = c("00:60:37:44:55:66", "DTCO-22222222", false, -85);
    Candidate bmw = c("AA:BB:CC:DD:EE:FF", "BMW i25927", true, -50);

    check(RoadCrewTachoBleChoice.isTachograph("DTCO-20908380"));
    check(!RoadCrewTachoBleChoice.isTachograph("BMW i25927") && !RoadCrewTachoBleChoice.isTachograph(null));

    // The paired tachograph alone in range: straight to it, a car's Bluetooth is never offered.
    Decision d = RoadCrewTachoBleChoice.decide(List.of(truck, oldTruck, bmw), List.of(truck, bmw));
    check(d.kind == Kind.USE && d.candidates.get(0) == truck);
    // Galin, 03.11.2026: more than one tachograph in range is the driver's choice, paired or not.
    // The paired one beside a new one: both on the list, nearest first.
    d = RoadCrewTachoBleChoice.decide(List.of(truck, oldTruck, bmw), List.of(truck, next, bmw));
    check(d.kind == Kind.ASK && d.candidates.size() == 2 && d.candidates.get(0) == truck && d.candidates.get(1) == next);
    // A driver in another truck, his old one parked beside it: the new cab is not passed over
    // just because the old one is paired.
    Candidate oldParked = c("00:60:37:11:22:33", "DTCO-11111111", true, -70);
    Candidate newCab = c("00:60:37:44:55:66", "DTCO-22222222", false, -55);
    d = RoadCrewTachoBleChoice.decide(List.of(oldParked), List.of(oldParked, newCab));
    check(d.kind == Kind.ASK && d.candidates.get(0) == newCab && d.candidates.get(1) == oldParked);
    // Another truck: the old one is paired but away, one unpaired DTCO in range - the driver
    // picks it from the list, even when it is the only one (Galin, 11.10.2026).
    d = RoadCrewTachoBleChoice.decide(List.of(oldTruck, bmw), List.of(next, bmw));
    check(d.kind == Kind.ASK && d.candidates.size() == 1 && d.candidates.get(0) == next);
    // The very first time, one tachograph in range: still the list, never a pairing by itself.
    d = RoadCrewTachoBleChoice.decide(List.of(bmw), List.of(next));
    check(d.kind == Kind.ASK && d.candidates.size() == 1 && d.candidates.get(0) == next);
    // Several unpaired in a parking lot: ask, nearest first.
    Candidate far = c("00:60:37:77:88:99", "DTCO-33333333", false, -95);
    Candidate near = c("00:60:37:aa:bb:cc", "DTCO-44444444", false, -55);
    d = RoadCrewTachoBleChoice.decide(List.of(), List.of(far, near, next));
    check(d.kind == Kind.ASK && d.candidates.get(0) == near && d.candidates.get(2) == far);
    // Two paired in range: the driver says which one he drives now, nearest first.
    Candidate pairedFar = c("00:60:37:dd:ee:ff", "DTCO-55555555", true, -90);
    d = RoadCrewTachoBleChoice.decide(List.of(pairedFar, truck), List.of(pairedFar, truck));
    check(d.kind == Kind.ASK && d.candidates.size() == 2 && d.candidates.get(0) == truck && d.candidates.get(0).bonded);
    // Nothing heard in the short scan but one paired: try it, the scan may simply have missed it.
    d = RoadCrewTachoBleChoice.decide(List.of(truck, bmw), List.of());
    check(d.kind == Kind.USE && d.candidates.get(0) == truck);
    // Nothing heard and nothing or several paired: say so, do not guess between trucks.
    check(RoadCrewTachoBleChoice.decide(List.of(bmw), List.of()).kind == Kind.NONE);
    check(RoadCrewTachoBleChoice.decide(List.of(truck, oldTruck), List.of()).kind == Kind.NONE);

    // A connection the unit drops at the start is tried again, at most three times in all;
    // never once card data has come, never for a cancel or a device that is not a tachograph.
    check(RoadCrewTachoBleChoice.again(1, false, "Bluetooth disconnected (19)"));
    check(RoadCrewTachoBleChoice.again(2, false, "Bluetooth response timed out"));
    check(!RoadCrewTachoBleChoice.again(3, false, "Bluetooth disconnected (19)"));
    check(!RoadCrewTachoBleChoice.again(1, true, "Bluetooth disconnected (19)"));
    check(!RoadCrewTachoBleChoice.again(1, false, "Download cancelled"));
    check(!RoadCrewTachoBleChoice.again(1, false, "Unsupported tachograph Bluetooth profile"));
    check(RoadCrewTachoBleChoice.ATTEMPTS == 3);

    // Both documented name prefixes use the same selection rules (ROADMAP 422).
    Candidate stoneridge = c("12:34:56:00:00:01", "SE5000-1234567890", true, -60);
    Candidate newStoneridge = c("12:34:56:00:00:02", "SE5000-0987654321", false, -70);
    check(RoadCrewTachoBleChoice.isTachograph(stoneridge.name));
    check(!RoadCrewTachoBleChoice.isTachograph("HEADSET-SE5000-123"));
    check(!RoadCrewTachoBleChoice.isTachograph("SE500-123"));
    check(!RoadCrewTachoBleChoice.isTachograph("SE5000"));
    d = RoadCrewTachoBleChoice.decide(List.of(stoneridge, bmw), List.of());
    check(d.kind == Kind.USE && d.candidates.get(0) == stoneridge);
    d = RoadCrewTachoBleChoice.decide(List.of(oldTruck, stoneridge), List.of(stoneridge));
    check(d.kind == Kind.USE && d.candidates.get(0) == stoneridge);
    d = RoadCrewTachoBleChoice.decide(List.of(bmw), List.of(newStoneridge, bmw));
    check(d.kind == Kind.ASK && d.candidates.size() == 1 && d.candidates.get(0) == newStoneridge);
    d = RoadCrewTachoBleChoice.decide(List.of(), List.of(next, newStoneridge));
    check(d.kind == Kind.ASK && d.candidates.size() == 2 && d.candidates.get(0) == newStoneridge);
    check(RoadCrewTachoBleChoice.decide(List.of(truck, stoneridge), List.of()).kind == Kind.NONE);
    System.out.println(checks + " Bluetooth choice checks passed");
  }
}
