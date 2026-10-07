package net.osmand.plus.roadcrew.tacho;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class CcidTest {
	static int checks;
	interface Attempt { void run() throws IOException; }
	static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
	static void fails(String code, Attempt attempt) throws IOException {
		try { attempt.run(); throw new AssertionError("Expected " + code); }
		catch (IOException e) { check(e.getMessage().contains(code)); }
	}
	static byte[] frame(int type, int seq, int status, int error, byte... data) {
		byte[] out = new byte[10 + data.length];
		out[0] = (byte) type;
		put(out, 1, data.length);
		out[6] = (byte) seq; out[7] = (byte) status; out[8] = (byte) error;
		System.arraycopy(data, 0, out, 10, data.length);
		return out;
	}
	static void put(byte[] b, int p, int value) {
		for (int i = 0; i < 4; i++) b[p + i] = (byte) (value >>> (i * 8));
	}
	static final class Fake implements RoadCrewTachoCcidProtocol.Io {
		ArrayDeque<byte[]> replies = new ArrayDeque<>();
		List<byte[]> sent = new ArrayList<>();
		long time;
		int pauses;
		int tick;
		boolean shortWrite;
		public int write(byte[] b, int timeout) { sent.add(b.clone()); return shortWrite ? -1 : b.length; }
		public int read(byte[] b, int timeout) {
			time += tick;
			if (replies.isEmpty()) return -1;
			byte[] next = replies.remove(); System.arraycopy(next, 0, b, 0, next.length); return next.length;
		}
		public long now() { return time; }
		public void pause(int millis) { check(millis >= 10); pauses++; time += millis; }
		void add(byte[] b) { replies.add(b); }
		RoadCrewTachoCcidProtocol protocol() {
			return new RoadCrewTachoCcidProtocol(this, new RoadCrewTachoCcidProtocol.Capabilities(3, 3, 0x2000E, 4096));
		}
	}
	static RoadCrewTachoCcidProtocol.Frame exchange(RoadCrewTachoCcidProtocol p, int cmd) throws IOException {
		return p.exchange(cmd, new byte[0], (byte) 0, (byte) 0, (byte) 0);
	}
	public static void main(String[] args) throws Exception {
		// Every possible split, including inside the header and length field.
		byte[] good = frame(0x80, 0, 0, 0, (byte) 0x90, (byte) 0);
		for (int split = 1; split < good.length; split++) {
			Fake f = new Fake(); f.add(Arrays.copyOf(good, split)); f.add(Arrays.copyOfRange(good, split, good.length));
			check(Arrays.equals(exchange(f.protocol(), 0x6F).data, new byte[]{(byte) 0x90, 0}));
			check(f.sent.size() == 1);
		}
		Fake bytes = new Fake(); for (byte b : good) bytes.add(new byte[]{b});
		check(exchange(bytes.protocol(), 0x6F).data.length == 2);
		for (int cmd : new int[]{0x62, 0x6F, 0x63, 0x65, 0x61, 0x6C}) {
			int type = cmd == 0x61 || cmd == 0x6C ? 0x82 : cmd == 0x63 || cmd == 0x65 ? 0x81 : 0x80;
			Fake f = new Fake(); f.add(frame(type, 0, 0x80, 1)); f.add(frame(type, 0, 0, 0));
			check(!exchange(f.protocol(), cmd).failed()); check(f.sent.size() == 1);
		}
		for (int offset : new int[]{0, 5, 6}) {
			Fake f = new Fake(); byte[] bad = good.clone(); bad[offset]++; f.add(bad);
			fails("MISMATCH", () -> exchange(f.protocol(), 0x6F));
		}
		for (int size : new int[]{4087, -1, Integer.MAX_VALUE}) {
			Fake f = new Fake(); byte[] bad = new byte[10]; bad[0] = (byte) 0x80; put(bad, 1, size); f.add(bad);
			fails("TOO_LARGE", () -> exchange(f.protocol(), 0x6F));
		}
		Fake extra = new Fake(); extra.add(Arrays.copyOf(good, 13));
		fails("TRAILING", () -> exchange(extra.protocol(), 0x6F));
		Fake missing = new Fake(); missing.add(Arrays.copyOf(good, 11));
		RoadCrewTachoCcidProtocol lost = missing.protocol();
		fails("READ_FAILED", () -> exchange(lost, 0x6F));
		fails("RECONNECT", () -> exchange(lost, 0x6F)); check(missing.sent.size() == 1);
		Fake shortOut = new Fake(); shortOut.shortWrite = true;
		fails("WRITE_INCOMPLETE", () -> exchange(shortOut.protocol(), 0x6F)); check(shortOut.sent.size() == 1);
		Fake loop = new Fake(); for (int i = 0; i < 61; i++) loop.add(frame(0x80, 0, 0x80, 1));
		fails("EXTENSION_LIMIT", () -> exchange(loop.protocol(), 0x6F));
		Fake timeout = new Fake(); timeout.tick = 5000;
		for (int i = 0; i < 13; i++) timeout.add(frame(0x80, 0, 0x80, 1));
		fails("TIMEOUT", () -> exchange(timeout.protocol(), 0x6F));
		Fake invalid = new Fake(); invalid.add(frame(0x80, 0, 3, 0));
		fails("INVALID_STATUS", () -> exchange(invalid.protocol(), 0x6F));
		Fake removed = new Fake(); removed.add(frame(0x80, 0, 0x42, 0xFE));
		check(removed.protocol().powerOn().absent()); check(removed.sent.size() == 1);
		Fake fallback = new Fake();
		fallback.add(frame(0x80, 0, 0x41, 7)); fallback.add(frame(0x81, 1, 1, 0));
		fallback.add(frame(0x80, 2, 0x41, 0xFE)); fallback.add(frame(0x81, 3, 1, 0));
		fallback.add(frame(0x80, 4, 0, 0, (byte) 0x3B, (byte) 0));
		check(!fallback.protocol().powerOn().failed()); check(fallback.pauses == 2);
		check(fallback.sent.get(0)[7] == 0 && fallback.sent.get(2)[7] == 2 && fallback.sent.get(4)[7] == 1);
		Fake offFail = new Fake(); offFail.add(frame(0x80, 0, 0x41, 0xFE)); offFail.add(frame(0x81, 1, 0x41, 0xFB));
		fails("POWER_OFF_FAILED", () -> offFail.protocol().powerOn()); check(offFail.sent.size() == 2);
		Fake hardware = new Fake(); hardware.add(frame(0x80, 0, 0x41, 0xFB));
		check(hardware.protocol().powerOn().failed()); check(hardware.sent.size() == 1);
		Fake voltageClass = new Fake(); voltageClass.add(frame(0x80, 0, 0x41, 0xF5));
		voltageClass.add(frame(0x81, 1, 1, 0)); voltageClass.add(frame(0x80, 2, 0, 0, (byte) 0x3B, (byte) 0));
		check(!voltageClass.protocol().powerOn().failed()); check(voltageClass.sent.size() == 3);
		Fake badAtr = new Fake(); badAtr.add(frame(0x80, 0, 0x41, 0xF8));
		check(badAtr.protocol().powerOn().failed()); check(badAtr.sent.size() == 1);
		Fake emptyAtr = new Fake(); emptyAtr.add(frame(0x80, 0, 0, 0));
		fails("INVALID_ATR", () -> emptyAtr.protocol().powerOn());
		Fake chained = new Fake(); byte[] chain = good.clone(); chain[9] = 1; chained.add(chain);
		fails("CHAIN_UNSUPPORTED", () -> exchange(chained.protocol(), 0x6F));
		Fake uncertain = new Fake(); fails("READ_FAILED", () -> uncertain.protocol().powerOn()); check(uncertain.sent.size() == 1);
		Fake only3 = new Fake(); only3.add(frame(0x80, 0, 0, 0, (byte) 0x3B, (byte) 0));
		new RoadCrewTachoCcidProtocol(only3, new RoadCrewTachoCcidProtocol.Capabilities(2, 1, 0x10000, 271)).powerOn();
		check(only3.sent.get(0)[7] == 2);
		check(Arrays.equals(new RoadCrewTachoCcidProtocol.Capabilities(1, 1, 4, 271).powerChoices(), new int[]{0, 1}));
		check(new RoadCrewTachoCcidProtocol.Capabilities(4, 1, 0, 271).powerChoices().length == 0);
		byte[] descriptor = new byte[63]; descriptor[0] = 9; descriptor[1] = 4; descriptor[2] = 2; descriptor[5] = 11;
		descriptor[9] = 54; descriptor[10] = 0x21; descriptor[14] = 3;
		put(descriptor, 15, 3); put(descriptor, 49, 0x2000E); put(descriptor, 53, 271);
		RoadCrewTachoCcidProtocol.Capabilities c = RoadCrewTachoCcidProtocol.Capabilities.parse(descriptor, 2);
		check(c.features == 0x2000E && c.voltages == 3 && c.protocols == 3 && c.maxMessage == 271);
		fails("MISSING", () -> RoadCrewTachoCcidProtocol.Capabilities.parse(descriptor, 1));
		fails("TRUNCATED", () -> RoadCrewTachoCcidProtocol.Capabilities.parse(Arrays.copyOf(descriptor, 62), 2));
		fails("MISSING", () -> RoadCrewTachoCcidProtocol.Capabilities.parse(null, 0));
		Fake max = new Fake(); RoadCrewTachoCcidProtocol small = new RoadCrewTachoCcidProtocol(max,
				new RoadCrewTachoCcidProtocol.Capabilities(3, 1, 0, 10));
		fails("COMMAND_TOO_LARGE", () -> small.exchange(0x6F, new byte[1], (byte) 0, (byte) 0, (byte) 0));
		check(max.sent.isEmpty());
		Fake wrap = new Fake(); RoadCrewTachoCcidProtocol wrapped = wrap.protocol();
		for (int i = 0; i < 258; i++) {
			wrap.add(frame(0x81, i & 255, 1, 0)); exchange(wrapped, 0x65);
		}
		check((wrap.sent.get(256)[6] & 255) == 0);
		Fake zero = new Fake(); zero.add(new byte[0]);
		fails("READ_FAILED", () -> exchange(zero.protocol(), 0x65));
		Fake extensionData = new Fake(); extensionData.add(frame(0x80, 0, 0x80, 1, (byte) 1));
		fails("EXTENSION_LIMIT", () -> exchange(extensionData.protocol(), 0x6F));
		Fake disappears = new Fake(); disappears.add(frame(0x80, 0, 0x41, 0xFE));
		disappears.add(frame(0x81, 1, 2, 0));
		check(disappears.protocol().powerOn().absent()); check(disappears.sent.size() == 2);
		Fake activeOff = new Fake(); activeOff.add(frame(0x80, 0, 0x41, 0xFE)); activeOff.add(frame(0x81, 1, 0, 0));
		fails("POWER_OFF_FAILED", () -> activeOff.protocol().powerOn());
		Fake noVoltage = new Fake(); RoadCrewTachoCcidProtocol noPower = new RoadCrewTachoCcidProtocol(noVoltage,
				new RoadCrewTachoCcidProtocol.Capabilities(4, 1, 0, 271));
		fails("NO_SUPPORTED_CARD_VOLTAGE", noPower::powerOn); check(noVoltage.sent.isEmpty());
		Fake only5 = new Fake(); only5.add(frame(0x80, 0, 0, 0, (byte) 0x3B, (byte) 0));
		new RoadCrewTachoCcidProtocol(only5, new RoadCrewTachoCcidProtocol.Capabilities(1, 1, 0, 271)).powerOn();
		check(only5.sent.get(0)[7] == 1);
		byte[] badSize = descriptor.clone(); put(badSize, 53, 0);
		fails("MESSAGE_SIZE", () -> RoadCrewTachoCcidProtocol.Capabilities.parse(badSize, 2));
		byte[] wrongClass = descriptor.clone(); wrongClass[5] = 3;
		fails("MISSING", () -> RoadCrewTachoCcidProtocol.Capabilities.parse(wrongClass, 2));
		byte[] badDescriptor = descriptor.clone(); badDescriptor[0] = 1;
		fails("TRUNCATED", () -> RoadCrewTachoCcidProtocol.Capabilities.parse(badDescriptor, 2));
		// A timed-out UPDATE BINARY is never sent a second time.
		Fake write = new Fake(); RoadCrewTachoCcidProtocol wp = write.protocol();
		fails("READ_FAILED", () -> wp.exchange(0x6F, new byte[]{0, (byte) 0xD6, 0, 0, 1, 0}, (byte) 0, (byte) 0, (byte) 0));
		fails("RECONNECT", () -> exchange(wp, 0x6F)); check(write.sent.size() == 1);
		System.out.println(checks + " CCID checks passed");
	}
}
