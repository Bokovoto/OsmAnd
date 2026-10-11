import java.io.IOException;
import java.util.*;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoVuDownload;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoDdd;
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
