package net.osmand.plus.roadcrew.tacho;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Appendix 7 card-only download over a paired transport. No calibration/activity commands. */
public final class RoadCrewTachoVuDownload {

	public interface Channel {
		void send(byte[] frame) throws IOException;
		byte[] receive(long timeoutMillis) throws IOException;
	}

	public interface Progress { void received(int bytes, int parts); }

	private static final int MAX_BYTES = 16 * 1024 * 1024;
	private static final long CARD_TIMEOUT = 20 * 60 * 1000L;
	private static final long REPLY_MILLIS = 5000;
	/**
	 * After "still working" (7F xx 78) the unit reads the card in silence. Galin's DTCO 4.1 was
	 * silent 11.8 s on 11.10.2026 and about a minute in the ROADMAP 407 capture, so five seconds
	 * gave up on every download.
	 */
	private static final long PENDING_MILLIS = 3 * 60 * 1000L;
	private RoadCrewTachoVuDownload() { }

	public static byte[] download(Channel channel, Progress progress) throws IOException {
		channel.send(new byte[] {(byte) 0x81, (byte) 0xee, (byte) 0xf0, (byte) 0x81, (byte) 0xe0});
		expect(channel, 0x81, new int[] {0xc1, 0xea, 0x8f});
		channel.send(request(0x10, 0x81)); expect(channel, 0x10, new int[] {0x50, 0x81});
		channel.send(request(0x35, 0, 0, 0, 0, 0, 255, 255, 255, 255));
		expect(channel, 0x35, new int[] {0x75, 0, 255});
		channel.send(request(0x36, 6));
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		int counter = 1, lastRequest = 0x36;
		long deadline = System.nanoTime() + CARD_TIMEOUT * 1_000_000L;
		for (;;) {
			long remaining = (deadline - System.nanoTime()) / 1_000_000L;
			if (remaining <= 0) throw new IOException("Card download timed out");
			byte[] data = receive(channel, lastRequest, remaining, true);
			if (data.length < 4 || u(data[0]) != 0x76 || u(data[1]) != 6) throw new IOException("Not a card response");
			int number = (u(data[2]) << 8) | u(data[3]);
			if (number != counter || counter >= 65535) throw new IOException("Card part sequence mismatch");
			if (output.size() + data.length - 4 > MAX_BYTES) throw new IOException("Card download too large");
			output.write(data, 4, data.length - 4);
			progress.received(output.size(), counter);
			if (data.length < 255) break;
			counter++;
			channel.send(request(0x83, 0x76, counter >> 8, counter & 255));
			lastRequest = 0x83;
		}
		if (output.size() == 0) throw new IOException("Empty card download");
		channel.send(request(0x37)); expect(channel, 0x37, new int[] {0x77});
		channel.send(request(0x82)); expect(channel, 0x82, new int[] {0xc2});
		return output.toByteArray();
	}

	private static void expect(Channel channel, int service, int[] expected) throws IOException {
		byte[] actual = receive(channel, service, 5000, false);
		if (actual.length != expected.length) throw new IOException("Unexpected response length");
		for (int i = 0; i < expected.length; i++) {
			if (u(actual[i]) != expected[i]) throw new IOException("Unexpected response for service " + service);
		}
	}

	private static byte[] receive(Channel channel, int service, long timeout, boolean card) throws IOException {
		long deadline = System.nanoTime() + timeout * 1_000_000L;
		long wait = REPLY_MILLIS;
		for (;;) {
			long remaining = (deadline - System.nanoTime()) / 1_000_000L;
			if (remaining <= 0) throw new IOException("Response timed out");
			// A pending response extends the wait, not a silent connection indefinitely.
			byte[] data = response(channel.receive(Math.min(remaining, wait)));
			if (u(data[0]) != 0x7f) return data;
			if (data.length == 3 && u(data[1]) == service && u(data[2]) == 0x78 && card) {
				wait = PENDING_MILLIS;
				continue;
			}
			throw new IOException("Tachograph declined service " + service + " (status "
					+ (data.length == 3 ? u(data[2]) : -1) + ")");
		}
	}

	public static byte[] request(int... data) {
		if (data.length < 1 || data.length > 255) throw new IllegalArgumentException("Frame length");
		byte[] frame = new byte[data.length + 5];
		frame[0] = (byte) 0x80; frame[1] = (byte) 0xee; frame[2] = (byte) 0xf0; frame[3] = (byte) data.length;
		for (int i = 0; i < data.length; i++) frame[i + 4] = (byte) data[i];
		frame[frame.length - 1] = checksum(frame);
		return frame;
	}

	public static byte[] response(byte[] frame) throws IOException {
		if (frame == null || frame.length < 6 || frame.length > 260 || u(frame[0]) != 0x80
				|| u(frame[1]) != 0xf0 || u(frame[2]) != 0xee || u(frame[3]) != frame.length - 5
				|| frame[frame.length - 1] != checksum(frame)) throw new IOException("Invalid tachograph frame");
		return Arrays.copyOfRange(frame, 4, frame.length - 1);
	}

	private static byte checksum(byte[] frame) {
		byte sum = 0;
		for (int i = 0; i < frame.length - 1; i++) sum += frame[i];
		return sum;
	}

	/** VDO download pipe observed on DTCO 4.1, distinct from Appendix 7 frames. */
	public static List<byte[]> segment(byte[] frame, int mtu) throws IOException {
		if (mtu < 23 || frame.length == 0 || frame.length > 260) throw new IOException("Invalid BLE frame size");
		int size = Math.min(mtu - 5, 235);
		int count = (frame.length + size - 1) / size;
		List<byte[]> segments = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			int offset = i * size, length = Math.min(size, frame.length - offset);
			byte[] segment = new byte[length + 2];
			segment[0] = (byte) (i == 0 ? count : 0); segment[1] = (byte) (i + 1);
			System.arraycopy(frame, offset, segment, 2, length); segments.add(segment);
		}
		return segments;
	}

	public static final class Assembler {
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		private int count, next;
		public byte[] accept(byte[] segment) throws IOException {
			if (segment == null || segment.length < 3 || segment.length > 237) throw new IOException("Invalid BLE segment");
			int total = u(segment[0]), index = u(segment[1]);
			if (total != 0) {
				if (count != 0 || index != 1 || total > 15) throw new IOException("Unexpected BLE frame start");
				count = total; next = 1;
			}
			if (count == 0 || index != next || bytes.size() + segment.length - 2 > 260) throw new IOException("BLE segment sequence mismatch");
			bytes.write(segment, 2, segment.length - 2); next++;
			if (index != count) return null;
			byte[] result = bytes.toByteArray(); bytes.reset(); count = 0; next = 0;
			return result;
		}
	}

	/**
	 * VDO's credit pipe as Tacho Manager drives it. The phone grants the unit a window of segments
	 * by writing it to the credit characteristic; the unit answers every grant (02 for the first,
	 * 00 later) and gives the phone one send credit back after each data write (01). Tacho Manager
	 * never writes data while a grant is unanswered (19 of 19 grants, 11.10.2026). RoadCrew wrote
	 * an ack 12 ms after a grant at 22:01:15: the unit answered the grant and never the ack.
	 */
	public static final class Credits {
		public static final int WINDOW = 51;
		private int sendCredits, consumed;
		private boolean grantUnanswered;

		public void unitIndicated(int value) throws IOException {
			if (value < 0 || value > 255) throw new IOException("Invalid Bluetooth credit");
			grantUnanswered = false;
			sendCredits += value;
			if (sendCredits > 1024) throw new IOException("Bluetooth credit overflow");
		}

		public void received() throws IOException {
			if (++consumed > WINDOW) throw new IOException("Bluetooth receive window exceeded");
		}

		public boolean grantDue() { return consumed == WINDOW && !grantUnanswered; }

		public void granted() { consumed = 0; grantUnanswered = true; }

		public boolean maySend() { return sendCredits > 0 && !grantUnanswered; }

		public void sent() { sendCredits--; }
	}

	private static int u(byte b) { return b & 255; }
}
