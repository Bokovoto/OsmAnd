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

    // The paired tachograph in range: straight to it, a car's Bluetooth is never offered.
    Decision d = RoadCrewTachoBleChoice.decide(List.of(truck, oldTruck, bmw), List.of(truck, next, bmw));
    check(d.kind == Kind.USE && d.candidates.get(0) == truck);
    // Another truck: the old one is paired but away, one unpaired DTCO in range - pair that one.
    d = RoadCrewTachoBleChoice.decide(List.of(oldTruck, bmw), List.of(next, bmw));
    check(d.kind == Kind.PAIR && d.candidates.get(0) == next);
    // Several unpaired in a parking lot: ask, nearest first.
    Candidate far = c("00:60:37:77:88:99", "DTCO-33333333", false, -95);
    Candidate near = c("00:60:37:aa:bb:cc", "DTCO-44444444", false, -55);
    d = RoadCrewTachoBleChoice.decide(List.of(), List.of(far, near, next));
    check(d.kind == Kind.ASK && d.candidates.get(0) == near && d.candidates.get(2) == far);
    // Two paired in range: the stronger signal is the cab the phone is in.
    Candidate pairedFar = c("00:60:37:dd:ee:ff", "DTCO-55555555", true, -90);
    d = RoadCrewTachoBleChoice.decide(List.of(pairedFar, truck), List.of(pairedFar, truck));
    check(d.kind == Kind.USE && d.candidates.get(0) == truck);
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
    System.out.println(checks + " Bluetooth choice checks passed");
  }
}
