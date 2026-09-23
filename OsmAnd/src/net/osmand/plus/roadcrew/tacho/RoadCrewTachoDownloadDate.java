package net.osmand.plus.roadcrew.tacho;

import java.io.IOException;
import java.util.Arrays;

/** Test-card experiment, not a DDD download. EC1360/2002 Appendix 2, 3.6.1-3.6.3. */
public final class RoadCrewTachoDownloadDate {

	public interface Channel {
		byte[] exchange(byte[] command) throws IOException;
	}

	public interface BeforeWrite {
		void record(long previous, long requested) throws IOException;
	}

	private RoadCrewTachoDownloadDate() { }

	public static long read(Channel channel) throws IOException {
		selectDate(channel);
		return decode(readFour(channel));
	}

	public static long writeTestDate(Channel channel, long epochSeconds, BeforeWrite observer) throws IOException {
		if (epochSeconds <= 0 || epochSeconds > 0xFFFFFFFFL) {
			throw new IOException("Date outside TimeReal range");
		}
		selectDate(channel);
		long previous = decode(readFour(channel));
		if (epochSeconds <= previous) {
			throw new IOException("Test date must be newer than the stored date; check the phone clock");
		}
		observer.record(previous, epochSeconds);
		byte[] expected = {(byte) (epochSeconds >>> 24), (byte) (epochSeconds >>> 16),
				(byte) (epochSeconds >>> 8), (byte) epochSeconds};
		byte[] command = {0x00, (byte) 0xD6, 0x00, 0x00, 0x04,
				expected[0], expected[1], expected[2], expected[3]};
		// A missing reply may follow a successful write. Never automatically retry.
		try {
			checked(channel, command, 0);
			if (!Arrays.equals(expected, readFour(channel))) {
				throw new IOException("Date readback differs");
			}
		} catch (IOException e) {
			throw new IOException("Write attempted; result NOT verified. Read the card before any further write. "
					+ e.getMessage(), e);
		}
		return previous;
	}

	private static void selectDate(Channel channel) throws IOException {
		checked(channel, new byte[]{0x00, (byte) 0xA4, 0x04, 0x0C, 0x06,
				(byte) 0xFF, 0x54, 0x41, 0x43, 0x48, 0x4F}, 0);
		checked(channel, select(0x0501), 0);
		byte[] type = checked(channel, new byte[]{0x00, (byte) 0xB0, 0x00, 0x00, 0x01}, 1);
		if (type[0] != 1) {
			throw new IOException("Not a driver card; no date write allowed");
		}
		checked(channel, select(0x050E), 0);
	}

	private static byte[] select(int fid) {
		return new byte[]{0x00, (byte) 0xA4, 0x02, 0x0C, 0x02, (byte) (fid >>> 8), (byte) fid};
	}

	private static byte[] readFour(Channel channel) throws IOException {
		return checked(channel, new byte[]{0x00, (byte) 0xB0, 0x00, 0x00, 0x04}, 4);
	}

	private static byte[] checked(Channel channel, byte[] command, int length) throws IOException {
		byte[] response = channel.exchange(command);
		if (response == null || response.length < 2) {
			throw new IOException("Missing card response");
		}
		int sw = ((response[response.length - 2] & 0xFF) << 8) | (response[response.length - 1] & 0xFF);
		if (sw != 0x9000) {
			throw new IOException(String.format("INS=%02X SW=%04X", command[1] & 0xFF, sw));
		}
		if (response.length != length + 2) {
			throw new IOException("Unexpected card response length: " + (response.length - 2) + ", expected " + length);
		}
		return Arrays.copyOf(response, length);
	}

	private static long decode(byte[] value) {
		return ((value[0] & 0xFFL) << 24) | ((value[1] & 0xFFL) << 16)
				| ((value[2] & 0xFFL) << 8) | (value[3] & 0xFFL);
	}
}
