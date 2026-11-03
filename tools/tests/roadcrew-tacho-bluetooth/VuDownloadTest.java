import java.io.IOException;
import java.util.*;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoVuDownload;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoDdd;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoVuProgress;
import java.io.ByteArrayOutputStream;

public class VuDownloadTest {
  static int checks;
  interface Checked { void run() throws Exception; }
  static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
  static void fails(Checked action) throws Exception {
    checks++;
    try { action.run(); } catch (IOException expected) { return; }
    throw new AssertionError("expected IOException, check " + checks);
  }
  static byte[] hex(String s) { return HexFormat.of().parseHex(s); }
  static byte[] reply(int... data) {
    byte[] out = new byte[data.length + 5];
    out[0] = (byte) 128; out[1] = (byte) 240; out[2] = (byte) 238; out[3] = (byte) data.length;
    for (int i = 0; i < data.length; i++) out[i + 4] = (byte) data[i];
    for (int i = 0; i < out.length - 1; i++) out[out.length - 1] += out[i];
    return out;
  }
  static class Wire implements RoadCrewTachoVuDownload.Channel {
    Deque<byte[]> replies = new ArrayDeque<>();
    List<byte[]> sent = new ArrayList<>();
    Wire() {
      replies.add(reply(0xc1, 0xea, 0x8f)); replies.add(reply(0x50, 0x81)); replies.add(reply(0x75, 0, 255));
    }
    public void send(byte[] b) { sent.add(b); }
    public byte[] receive(long timeout) throws IOException {
      if (replies.isEmpty()) throw new IOException("disconnected");
      return replies.remove();
    }
    void end() { replies.add(reply(0x77)); replies.add(reply(0xc2)); }
  }
  /** Replies that can come after a silence; a read shorter than the silence times out, as on the phone. */
  static class SlowWire extends Wire {
    final Deque<Long> silence = new ArrayDeque<>(List.of(0L, 0L, 0L));
    void add(long millis, byte[] reply) { silence.add(millis); replies.add(reply); }
    @Override void end() { add(0, reply(0x77)); add(0, reply(0xc2)); }
    @Override public byte[] receive(long timeout) throws IOException {
      if (replies.isEmpty()) throw new IOException("disconnected");
      if (timeout < silence.peek()) throw new IOException("timed out after " + timeout + " ms");
      silence.remove();
      return replies.remove();
    }
  }
  static byte[] run(Wire w) throws IOException { return RoadCrewTachoVuDownload.download(w, (bytes, parts) -> {}); }
  static void block(ByteArrayOutputStream out, int tag, byte[] data) {
    out.write(tag >> 16); out.write(tag >> 8); out.write(tag);
    out.write(data.length >> 8); out.write(data.length); out.writeBytes(data);
  }
  static byte[] card(boolean gen2) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int tag : new int[] {0x000200, 0x000500, 0xc10000, 0xc10800}) block(out, tag, new byte[] {1});
    for (int generation = 0; generation <= (gen2 ? 2 : 0); generation += 2) {
      for (int fid : new int[] {0x0501, 0x0520, 0x0521, 0x0502, 0x0503, 0x0504, 0x0505, 0x0506, 0x0507, 0x0508, 0x0522}) {
        byte[] data = new byte[fid == 0x0520 ? 143 : fid == 0x0501 ? 17 : 4];
        if (fid == 0x0501) data[0] = 1;
        if (fid == 0x0520) System.arraycopy("TEST012345678901".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, data, 1, 16);
        block(out, fid * 256 + generation, data);
        block(out, fid * 256 + generation + 1, new byte[generation == 0 ? 128 : 64]);
      }
    }
    return out.toByteArray();
  }
  public static void main(String[] args) throws Exception {
    // GATT capability combinations, independently for FIFO and credits (ROADMAP 422).
    // Keep VDO's no-response writes when available, accept acknowledged writes otherwise.
    for (int bits = 0; bits < 16; bits++) {
      boolean indications = (bits & 1) != 0, cccd = (bits & 2) != 0;
      boolean withoutResponse = (bits & 4) != 0, withResponse = (bits & 8) != 0;
      if (indications && cccd && (withoutResponse || withResponse)) {
        check(RoadCrewTachoVuDownload.useWriteWithoutResponse(indications, cccd,
            withoutResponse, withResponse) == withoutResponse);
      } else {
        fails(() -> RoadCrewTachoVuDownload.useWriteWithoutResponse(indications, cccd,
            withoutResponse, withResponse));
      }
    }
    check(RoadCrewTachoVuDownload.useWriteWithoutResponse(true, true, true, false));
    check(!RoadCrewTachoVuDownload.useWriteWithoutResponse(true, true, false, true));

    Wire w = new Wire();
    w.replies.add(reply(0x7f, 0x36, 0x78));
    int[] full = new int[255]; full[0] = 0x76; full[1] = 6; full[3] = 1;
    Arrays.fill(full, 4, full.length, 42);
    w.replies.add(reply(full)); w.replies.add(reply(0x7f, 0x83, 0x78));
    w.replies.add(reply(0x76, 6, 0, 2, 99)); w.end();
    byte[] result = run(w);
    check(result.length == 252 && result[251] == 99 && result[0] == 42);
    check(Arrays.equals(w.sent.get(0), hex("81eef081e0")));
    check(Arrays.equals(w.sent.get(1), hex("80eef0021081f1")));
    check(Arrays.equals(w.sent.get(2), hex("80eef00a350000000000ffffffff99")));
    check(Arrays.equals(w.sent.get(3), hex("80eef00236069c")));
    check(Arrays.equals(w.sent.get(4), hex("80eef004837600025d")));
    check(Arrays.equals(w.sent.get(5), hex("80eef0013796")));
    check(Arrays.equals(w.sent.get(6), hex("80eef00182e1")));

    Wire exact = new Wire(); exact.replies.add(reply(full));
    exact.replies.add(reply(0x76, 6, 0, 2)); exact.end(); check(run(exact).length == 251);
    Wire truncated = new Wire(); truncated.replies.add(reply(full)); fails(() -> run(truncated));
    Wire wrongCounter = new Wire(); wrongCounter.replies.add(reply(0x76, 6, 0, 2, 1)); fails(() -> run(wrongCounter));
    Wire duplicate = new Wire(); duplicate.replies.add(reply(full)); duplicate.replies.add(reply(full)); fails(() -> run(duplicate));
    Wire denied = new Wire(); denied.replies.add(reply(0x7f, 0x36, 0x22)); fails(() -> run(denied));
    Wire unrelated = new Wire(); unrelated.replies.add(reply(0x7f, 0x35, 0x78)); fails(() -> run(unrelated));
    Wire wrongType = new Wire(); wrongType.replies.add(reply(0x76, 1, 0, 1, 1)); fails(() -> run(wrongType));
    Wire empty = new Wire(); empty.replies.add(reply(0x76, 6, 0, 1)); fails(() -> run(empty));
    Wire badEnd = new Wire(); badEnd.replies.add(reply(0x76, 6, 0, 1, 1)); badEnd.replies.add(reply(0x7f, 0x37, 0x22)); fails(() -> run(badEnd));
    Wire badStop = new Wire(); badStop.replies.add(reply(0x76, 6, 0, 1, 1)); badStop.replies.add(reply(0x77)); fails(() -> run(badStop));

    // Galin's DTCO 4.1, 11.10.2026: after 7F 36 78 it stayed silent 11.8 s while reading the card
    // (Tacho Manager, 20:38:13 -> 20:38:24.9); RoadCrew gave up after 5.0 s (20:36:17.3 -> 22.3).
    // The 407 capture waited about a minute.
    for (long quiet : new long[] {11_800, 60_000}) {
      SlowWire slow = new SlowWire(); slow.add(0, reply(0x7f, 0x36, 0x78));
      slow.add(quiet, reply(0x76, 6, 0, 1, 7)); slow.end();
      check(run(slow).length == 1);
    }
    // Without "still working" a silent unit is still given up on, and so is one silent for ten minutes.
    SlowWire mute = new SlowWire(); mute.silence.clear(); mute.silence.addAll(List.of(11_800L, 0L, 0L));
    fails(() -> run(mute));
    SlowWire gone = new SlowWire(); gone.add(0, reply(0x7f, 0x36, 0x78));
    gone.add(600_000, reply(0x76, 6, 0, 1, 7)); gone.end(); fails(() -> run(gone));

    // VDO's credit pipe as Tacho Manager drives it (all 19 grants on 11.10.2026, 20:38-20:41):
    // after the phone grants a window, its next data write waits for the unit's answer.
    // RoadCrew wrote the ack 12 ms after its grant at 22:01:15; the unit answered the grant (00)
    // and never the ack, and the download stopped.
    var credits = new RoadCrewTachoVuDownload.Credits();
    credits.granted();
    check(!credits.maySend());
    credits.unitIndicated(2);
    check(credits.maySend());
    credits.sent(); credits.unitIndicated(1);
    for (int i = 0; i < 50; i++) { credits.received(); check(!credits.grantDue()); }
    credits.received();
    check(credits.grantDue());
    credits.granted();
    check(!credits.grantDue() && !credits.maySend());
    credits.unitIndicated(0);
    check(credits.maySend());
    credits.sent(); credits.sent();
    check(!credits.maySend());
    credits.unitIndicated(1);
    check(credits.maySend());
    var greedy = new RoadCrewTachoVuDownload.Credits(); greedy.granted(); greedy.unitIndicated(2);
    for (int i = 0; i < 51; i++) greedy.received();
    fails(() -> greedy.received());
    fails(() -> greedy.unitIndicated(2000));

    // Which part of the card is coming (ROADMAP 411): the 73 blocks of the card RoadCrew read on
    // 11.10.2026 22:26 - tags and lengths only, contents zero - in the unit's order, fed as the
    // 76 06 parts bring them.
    int[][] blocks = {
      {0x0002,0,25},{0x0005,0,8},{0x0501,0,10},{0x0501,1,128},{0xc100,0,194},{0xc108,0,194},
      {0x0520,0,143},{0x0520,1,128},{0x050e,0,4},{0x050e,1,128},{0x0521,0,53},{0x0521,1,128},
      {0x0502,0,1728},{0x0502,1,128},{0x0503,0,1152},{0x0503,1,128},{0x0504,0,13780},{0x0504,1,128},
      {0x0505,0,6202},{0x0505,1,128},{0x0506,0,1121},{0x0506,1,128},{0x0507,0,19},{0x0507,1,128},
      {0x0508,0,46},{0x0508,1,128},{0x0522,0,280},{0x0522,1,128},
      {0x0002,2,25},{0x0005,2,8},{0x0501,2,17},{0x0501,3,64},{0xc101,2,204},{0xc108,2,205},{0xc109,2,205},
      {0x0520,2,143},{0x0520,3,64},{0x050e,2,4},{0x050e,3,64},{0x0521,2,53},{0x0521,3,64},
      {0x0502,2,3168},{0x0502,3,64},{0x0503,2,1152},{0x0503,3,64},{0x0504,2,13780},{0x0504,3,64},
      {0x0505,2,9602},{0x0505,3,64},{0x0506,2,2354},{0x0506,3,64},{0x0507,2,19},{0x0507,3,64},
      {0x0508,2,46},{0x0508,3,64},{0x0522,2,562},{0x0522,3,64},{0x0523,2,2002},{0x0523,3,64},
      {0x0524,2,6050},{0x0524,3,64},{0x0525,2,10},{0x0525,3,64},{0x0526,2,562},{0x0526,3,64},
      {0x0527,2,1682},{0x0527,3,64},{0x0528,2,19042},{0x0528,3,64},{0x0529,2,32482},{0x0529,3,64},
      {0x0530,2,1682},{0x0530,3,64}};
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    for (int[] b : blocks) block(stream, b[0] << 8 | b[1], new byte[b[2]]);
    byte[] whole = stream.toByteArray();
    check(whole.length == 123199);
    var seen = new RoadCrewTachoVuProgress();
    check(seen.fileId() == -1 && seen.permille() == 0);
    int last = 0; boolean sawActivity = false, sawLoads = false;
    for (int at = 0; at < whole.length; at += 250) {
      int n = Math.min(250, whole.length - at);
      seen.data(whole, at, n);
      check(seen.permille() >= last && seen.permille() < 1000);
      last = seen.permille();
      if (seen.fileId() == 0x0504 && !seen.secondGeneration() && seen.filePercent() >= 50 && !sawActivity) {
        sawActivity = true; check(seen.filesDone() == 10 && seen.filesTotal() == 41);
      }
      if (seen.fileId() == 0x0529 && seen.secondGeneration() && !sawLoads) {
        sawLoads = true; check(seen.filesDone() == 39);
      }
    }
    check(sawActivity && sawLoads);
    seen.finish();
    check(seen.filesDone() == 41 && seen.filesTotal() == 41 && seen.permille() == 1000);
    var gen1 = new RoadCrewTachoVuProgress();
    gen1.data(whole, 0, 26635); gen1.finish();
    check(gen1.filesDone() == 16 && gen1.filesTotal() == 16 && gen1.permille() == 1000);
    // The download hands the tracker each part's card bytes, nothing of the 76 06 framing.
    Wire framed = new Wire(); var tracked = new RoadCrewTachoVuProgress();
    byte[] head = Arrays.copyOf(whole, 35); // block 0002 (5 + 25) and the head of 0005
    int[] part = new int[4 + head.length]; part[0] = 0x76; part[1] = 6; part[3] = 1;
    for (int i = 0; i < head.length; i++) part[4 + i] = head[i] & 255;
    framed.replies.add(reply(part)); framed.end();
    check(RoadCrewTachoVuDownload.download(framed, tracked).length == 35);
    check(tracked.fileId() == 0x0005 && tracked.filesDone() == 1);

    byte[] good = reply(0x76, 6, 0, 1, 88);
    check(Arrays.equals(RoadCrewTachoVuDownload.response(good), hex("7606000158")));
    for (int i = 0; i < good.length; i++) {
      byte[] broken = good.clone(); broken[i] ^= 1; fails(() -> RoadCrewTachoVuDownload.response(broken));
    }
    fails(() -> RoadCrewTachoVuDownload.response(new byte[0]));
    fails(() -> RoadCrewTachoVuDownload.response(Arrays.copyOf(good, good.length - 1)));
    byte[] big = new byte[260]; Arrays.fill(big, (byte) 42);
    for (int mtu : new int[] {23, 64, 240, 517}) {
      var pieces = RoadCrewTachoVuDownload.segment(big, mtu);
      var assembly = new RoadCrewTachoVuDownload.Assembler(); byte[] joined = null;
      for (int i = 0; i < pieces.size(); i++) {
        check(pieces.get(i).length <= mtu - 3);
        joined = assembly.accept(pieces.get(i));
        check((joined != null) == (i == pieces.size() - 1));
      }
      check(Arrays.equals(joined, big));
    }
    fails(() -> RoadCrewTachoVuDownload.segment(big, 22));
    fails(() -> RoadCrewTachoVuDownload.segment(new byte[261], 240));
    var a = new RoadCrewTachoVuDownload.Assembler();
    fails(() -> a.accept(hex("00022a")));
    a.accept(hex("02012a")); fails(() -> a.accept(hex("00032a")));
    var b = new RoadCrewTachoVuDownload.Assembler();
    b.accept(hex("02012a")); fails(() -> b.accept(hex("01012a")));
    fails(() -> new RoadCrewTachoVuDownload.Assembler().accept(hex("ff012a")));
    for (boolean gen2 : new boolean[] {false, true}) {
      byte[] ddd = card(gen2);
      check(RoadCrewTachoDdd.validateAndGetCardNumber(ddd).equals("TEST012345678901"));
      // No truncation of either the last data block or its signature passes as complete.
      for (int end = ddd.length - 65; end < ddd.length; end++) {
        byte[] cut = Arrays.copyOf(ddd, end); fails(() -> RoadCrewTachoDdd.validateAndGetCardNumber(cut));
      }
      ByteArrayOutputStream duplicateDdd = new ByteArrayOutputStream(); duplicateDdd.writeBytes(ddd);
      block(duplicateDdd, 0x052000, new byte[143]);
      fails(() -> RoadCrewTachoDdd.validateAndGetCardNumber(duplicateDdd.toByteArray()));
      ByteArrayOutputStream orphan = new ByteArrayOutputStream(); orphan.writeBytes(ddd);
      block(orphan, 0x053103, new byte[64]);
      fails(() -> RoadCrewTachoDdd.validateAndGetCardNumber(orphan.toByteArray()));
    }
    fails(() -> RoadCrewTachoDdd.validateAndGetCardNumber(new byte[0]));
    fails(() -> RoadCrewTachoDdd.validateAndGetCardNumber(hex("050100ffff")));
    System.out.println(checks + " Bluetooth download checks passed");
  }
}
