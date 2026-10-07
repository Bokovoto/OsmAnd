package net.osmand.plus.roadcrew.tacho;

import java.io.IOException;
import java.util.Arrays;

/** CCID 1.1 framing and activation; no card-file commands or automatic APDU replay. */
final class RoadCrewTachoCcidProtocol {

	static final int LIMIT = 4096;
	private static final long COMMAND_MILLIS = 60_000;
	private static final int USB_MILLIS = 5_000;
	private static final int MAX_EXTENSIONS = 60;
	private static final int ICC_CLASS_NOT_SUPPORTED = 0xF5;
	private static final int ICC_MUTE = 0xFE;

	interface Io {
		int write(byte[] bytes, int timeout);
		int read(byte[] bytes, int timeout);
		long now();
		void pause(int millis) throws IOException;
		default void diagnostic(String detail) { }
	}

	static final class Capabilities {
		final int voltages;
		final int protocols;
		final int features;
		final int maxMessage;

		Capabilities(int voltages, int protocols, int features, int maxMessage) {
			this.voltages = voltages;
			this.protocols = protocols;
			this.features = features;
			this.maxMessage = maxMessage;
		}

		static Capabilities parse(byte[] raw, int interfaceId) throws IOException {
			boolean wanted = false;
			if (raw != null) {
				for (int p = 0; p + 1 < raw.length;) {
					int size = raw[p] & 255;
					int type = raw[p + 1] & 255;
					if (size < 2 || size > raw.length - p) {
						throw new IOException("CCID_DESCRIPTOR_TRUNCATED");
					}
					if (type == 4) {
						wanted = size >= 9 && (raw[p + 2] & 255) == interfaceId
								&& raw[p + 3] == 0 && raw[p + 5] == 11;
					} else if (type == 0x21 && wanted) {
						if (size < 54) throw new IOException("CCID_DESCRIPTOR_SHORT");
						long max = uint32(raw, p + 44);
						if (max < 10 || max > Integer.MAX_VALUE) {
							throw new IOException("CCID_DESCRIPTOR_MESSAGE_SIZE");
						}
						return new Capabilities(raw[p + 5] & 7, (int) uint32(raw, p + 6),
								(int) uint32(raw, p + 40), (int) max);
					}
					p += size;
				}
			}
			throw new IOException("CCID_DESCRIPTOR_MISSING");
		}

		int[] powerChoices() {
			int[] choices = new int[3];
			int count = 0;
			// Automatic activation (bit 2) requires AUTO on the initial request.
			if ((features & 0x0C) != 0) choices[count++] = 0;
			if ((voltages & 2) != 0) choices[count++] = 2;
			if ((voltages & 1) != 0) choices[count++] = 1;
			return Arrays.copyOf(choices, count);
		}

		@Override public String toString() {
			return "CCID voltages=" + voltages + " protocols=" + protocols
					+ " features=0x" + Integer.toHexString(features) + " maxMessage=" + maxMessage;
		}
	}

	static final class Frame {
		final int type;
		final byte status;
		final byte error;
		final byte[] data;

		Frame(byte[] bytes) {
			type = bytes[0] & 255;
			status = bytes[7];
			error = bytes[8];
			data = Arrays.copyOfRange(bytes, 10, bytes.length);
		}
		boolean failed() { return (status & 0xC0) != 0; }
		boolean absent() { return (status & 3) == 2; }
	}

	private final Io io;
	private final Capabilities caps;
	private int sequence;
	private boolean desynchronized;

	RoadCrewTachoCcidProtocol(Io io, Capabilities caps) {
		this.io = io;
		this.caps = caps;
	}

	Frame exchange(int command, byte[] payload, byte b7, byte b8, byte b9) throws IOException {
		if (desynchronized) throw new IOException("CCID_RECONNECT_REQUIRED");
		if (payload.length > Math.min(LIMIT, caps.maxMessage) - 10) {
			throw new IOException("CCID_COMMAND_TOO_LARGE");
		}
		int seq = sequence++ & 255;
		byte[] request = new byte[10 + payload.length];
		request[0] = (byte) command;
		for (int i = 0; i < 4; i++) request[1 + i] = (byte) (payload.length >>> (8 * i));
		request[6] = (byte) seq;
		request[7] = b7;
		request[8] = b8;
		request[9] = b9;
		System.arraycopy(payload, 0, request, 10, payload.length);
		long deadline = io.now() + COMMAND_MILLIS;
		try {
			if (io.write(request, USB_MILLIS) != request.length) {
				throw new IOException("CCID_USB_WRITE_INCOMPLETE");
			}
			int expected = command == 0x61 || command == 0x6C ? 0x82
					: command == 0x63 || command == 0x65 ? 0x81 : 0x80;
			for (int extensions = 0; ; extensions++) {
				byte[] bytes = receive(deadline);
				if ((bytes[0] & 255) != expected || bytes[5] != 0 || (bytes[6] & 255) != seq) {
					throw new IOException("CCID_RESPONSE_MISMATCH cmd=" + Integer.toHexString(command));
				}
				Frame frame = new Frame(bytes);
				if ((frame.status & 3) == 3 || (frame.status & 0xC0) == 0xC0) {
					throw new IOException("CCID_INVALID_STATUS");
				}
				if ((frame.status & 0xC0) != 0x80) {
					if (command == 0x6F && !frame.failed() && bytes[9] != 0) {
						throw new IOException("CCID_RESPONSE_CHAIN_UNSUPPORTED");
					}
					return frame;
				}
				if (frame.data.length != 0 || extensions >= MAX_EXTENSIONS) {
					throw new IOException("CCID_TIME_EXTENSION_LIMIT");
				}
			}
		} catch (IOException e) {
			// A late reply could belong to the lost command. Never reuse or replay it.
			desynchronized = true;
			throw new IOException("cmd=" + Integer.toHexString(command) + " " + e.getMessage(), e);
		}
	}

	private byte[] receive(long deadline) throws IOException {
		byte[] collected = new byte[LIMIT];
		byte[] chunk = new byte[LIMIT];
		int count = 0;
		int total = -1;
		while (total < 0 || count < total) {
			long left = deadline - io.now();
			if (left <= 0) throw new IOException("CCID_RESPONSE_TIMEOUT");
			int received = io.read(chunk, (int) Math.min(USB_MILLIS, left));
			if (received <= 0) throw new IOException("CCID_USB_READ_FAILED partial=" + count);
			if (received > LIMIT - count) throw new IOException("CCID_RESPONSE_TOO_LARGE");
			System.arraycopy(chunk, 0, collected, count, received);
			count += received;
			if (count >= 10 && total < 0) {
				long length = uint32(collected, 1);
				if (length > LIMIT - 10) throw new IOException("CCID_RESPONSE_TOO_LARGE");
				total = 10 + (int) length;
			}
			if (total >= 0 && count > total) throw new IOException("CCID_RESPONSE_TRAILING_BYTES");
		}
		return Arrays.copyOf(collected, total);
	}

	Frame powerOn() throws IOException {
		int[] choices = caps.powerChoices();
		if (choices.length == 0) throw new IOException("CCID_NO_SUPPORTED_CARD_VOLTAGE");
		Frame last = null;
		for (int i = 0; i < choices.length; i++) {
			if (i != 0) {
				Frame off = exchange(0x63, new byte[0], (byte) 0, (byte) 0, (byte) 0);
				if (off.absent()) return off;
				if (off.failed() || (off.status & 3) != 1) throw new IOException("CCID_POWER_OFF_FAILED");
				io.pause(20);
			}
			io.diagnostic("CCID_POWER_ON selector=" + choices[i]);
			last = exchange(0x62, new byte[0], (byte) choices[i], (byte) 0, (byte) 0);
			io.diagnostic("CCID_POWER_RESULT selector=" + choices[i] + " status="
					+ Integer.toHexString(last.status & 255) + " error=" + Integer.toHexString(last.error & 255)
					+ " atrBytes=" + last.data.length);
			if (last.absent()) return last;
			if (!last.failed()) {
				if ((last.status & 3) != 0 || last.data.length < 2
						|| (last.data[0] != 0x3B && last.data[0] != 0x3F)) {
					throw new IOException("CCID_POWER_ON_INVALID_ATR");
				}
				return last;
			}
			int error = last.error & 255;
			// Explicit voltage refusal or silent card only; never retry hardware errors.
			if ((last.status & 3) != 1
					|| (error != 7 && error != ICC_CLASS_NOT_SUPPORTED && error != ICC_MUTE)) return last;
		}
		return last;
	}

	private static long uint32(byte[] bytes, int offset) {
		return (bytes[offset] & 255L) | ((bytes[offset + 1] & 255L) << 8)
				| ((bytes[offset + 2] & 255L) << 16) | ((bytes[offset + 3] & 255L) << 24);
	}
}
