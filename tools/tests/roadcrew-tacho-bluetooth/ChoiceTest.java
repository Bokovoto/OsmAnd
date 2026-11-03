import java.util.*;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoBleChoice;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoBleChoice.Candidate;

/**
 * Which tachographs the card screen shows (Galin, 03.11.2026): the paired ones near the phone,
 * the nearest picked by itself, and for "Нов тахограф" only the ones the phone has not paired.
 */
public class ChoiceTest {
  static int checks;
  static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
  static Candidate c(String address, String name, boolean bonded, int rssi) { return new Candidate(address, name, bonded, rssi); }

  public static void main(String[] args) {
    Candidate truck = c("00:60:37:4B:39:0B", "DTCO-20908380", true, Integer.MIN_VALUE);
    Candidate oldTruck = c("00:60:37:11:22:33", "DTCO-11111111", true, Integer.MIN_VALUE);
    Candidate bmw = c("AA:BB:CC:DD:EE:FF", "BMW i25927", true, Integer.MIN_VALUE);
    // What the scan heard comes as its own objects, with a signal.
    Candidate truckHeard = c("00:60:37:4B:39:0B", "DTCO-20908380", true, -60);
    Candidate next = c("00:60:37:44:55:66", "DTCO-22222222", false, -85);
    Candidate bmwHeard = c("AA:BB:CC:DD:EE:FF", "BMW i25927", true, -50);

    check(RoadCrewTachoBleChoice.isTachograph("DTCO-20908380"));
    check(!RoadCrewTachoBleChoice.isTachograph("BMW i25927") && !RoadCrewTachoBleChoice.isTachograph(null));

    // The phone's paired tachographs: a car's Bluetooth is never one of them.
    List<Candidate> paired = RoadCrewTachoBleChoice.pairedTachographs(List.of(truck, oldTruck, bmw));
    check(paired.size() == 2 && paired.contains(truck) && paired.contains(oldTruck));

    // The paired ones the scan heard, nearest first: the first is the one picked by itself.
    Candidate pairedFar = c("00:60:37:DD:EE:FF", "DTCO-55555555", true, Integer.MIN_VALUE);
    Candidate pairedFarHeard = c("00:60:37:DD:EE:FF", "DTCO-55555555", true, -90);
    List<Candidate> near = RoadCrewTachoBleChoice.nearbyPaired(List.of(truck, pairedFar, bmw),
        List.of(pairedFarHeard, truckHeard, next, bmwHeard));
    check(near.size() == 2 && near.get(0) == truckHeard && near.get(1) == pairedFarHeard);
    // An unpaired tachograph in range is never picked by itself, nor a car.
    check(RoadCrewTachoBleChoice.nearbyPaired(List.of(bmw), List.of(next, bmwHeard)).isEmpty());
    // Nothing heard: nothing is near, whatever is paired.
    check(RoadCrewTachoBleChoice.nearbyPaired(List.of(truck, oldTruck), List.of()).isEmpty());
    // The scan and the paired list may write an address in different case.
    Candidate lower = c("00:60:37:4b:39:0b", "DTCO-20908380", true, -61);
    check(RoadCrewTachoBleChoice.nearbyPaired(List.of(truck), List.of(lower)).size() == 1);

    // The "Тахограф" section: every paired tachograph, the heard ones first, nearest first, each
    // with the signal it was heard at; the ones not heard keep no signal.
    List<Candidate> listed = RoadCrewTachoBleChoice.pairedWithSignal(List.of(oldTruck, truck, bmw), List.of(truckHeard, next));
    check(listed.size() == 2);
    check(listed.get(0).address.equals(truck.address) && listed.get(0).rssi == -60 && listed.get(0).bonded);
    check(listed.get(1) == oldTruck && listed.get(1).rssi == Integer.MIN_VALUE);
    check(RoadCrewTachoBleChoice.heard(listed.get(0)) && !RoadCrewTachoBleChoice.heard(listed.get(1)));

    // "Нов тахограф": only tachographs the phone has not paired, nearest first - never a paired one
    // again, never a car.
    Candidate far = c("00:60:37:77:88:99", "DTCO-33333333", false, -95);
    Candidate nearNew = c("00:60:37:AA:BB:CC", "DTCO-44444444", false, -55);
    List<Candidate> fresh = RoadCrewTachoBleChoice.unpaired(List.of(truck, bmw),
        List.of(far, truckHeard, nearNew, next, bmwHeard));
    check(fresh.size() == 3 && fresh.get(0) == nearNew && fresh.get(1) == next && fresh.get(2) == far);
    check(RoadCrewTachoBleChoice.unpaired(List.of(), List.of()).isEmpty());

    // A connection the unit drops at the start is tried again, at most three times in all;
    // never once card data has come, never for a cancel or a device that is not a tachograph.
    check(RoadCrewTachoBleChoice.again(1, false, "Bluetooth disconnected (19)"));
    check(RoadCrewTachoBleChoice.again(2, false, "Bluetooth response timed out"));
    check(!RoadCrewTachoBleChoice.again(3, false, "Bluetooth disconnected (19)"));
    check(!RoadCrewTachoBleChoice.again(1, true, "Bluetooth disconnected (19)"));
    check(!RoadCrewTachoBleChoice.again(1, false, "Download cancelled"));
    check(!RoadCrewTachoBleChoice.again(1, false, "Unsupported tachograph Bluetooth profile"));
    check(!RoadCrewTachoBleChoice.again(1, false, "Pair the tachograph first"));
    check(RoadCrewTachoBleChoice.ATTEMPTS == 3);

    // Both documented name prefixes use the same rules (ROADMAP 422).
    Candidate stoneridge = c("12:34:56:00:00:01", "SE5000-1234567890", true, Integer.MIN_VALUE);
    Candidate stoneridgeHeard = c("12:34:56:00:00:01", "SE5000-1234567890", true, -60);
    Candidate newStoneridge = c("12:34:56:00:00:02", "SE5000-0987654321", false, -70);
    check(RoadCrewTachoBleChoice.isTachograph(stoneridge.name));
    check(!RoadCrewTachoBleChoice.isTachograph("HEADSET-SE5000-123"));
    check(!RoadCrewTachoBleChoice.isTachograph("SE500-123"));
    check(!RoadCrewTachoBleChoice.isTachograph("SE5000"));
    check(RoadCrewTachoBleChoice.nearbyPaired(List.of(oldTruck, stoneridge), List.of(stoneridgeHeard)).get(0) == stoneridgeHeard);
    check(RoadCrewTachoBleChoice.unpaired(List.of(bmw), List.of(newStoneridge, bmwHeard)).get(0) == newStoneridge);
    System.out.println(checks + " Bluetooth choice checks passed");
  }
}
