package net.osmand.plus.roadcrew.tacho;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Structural check of the unchanged VU export, not cryptographic signature verification. */
public final class RoadCrewTachoDdd {

	private RoadCrewTachoDdd() { }

	public static String validateAndGetCardNumber(byte[] ddd) throws IOException {
		Map<Integer, byte[]> blocks = new HashMap<>();
		boolean gen2 = false;
		for (int offset = 0; offset < ddd.length; ) {
			if (offset + 5 > ddd.length) throw new IOException("DDD header cut short");
			int fid = u16(ddd, offset), type = ddd[offset + 2] & 255, size = u16(ddd, offset + 3);
			if (type > 3 || size == 0 || offset + 5 + size > ddd.length) throw new IOException("Invalid DDD block");
			int tag = fid * 256 + type;
			if (blocks.put(tag, Arrays.copyOfRange(ddd, offset + 5, offset + 5 + size)) != null)
				throw new IOException("Duplicate DDD block");
			gen2 |= type >= 2;
			offset += 5 + size;
		}
		for (int generation = 0; generation <= (gen2 ? 2 : 0); generation += 2) {
			// Common driver-card application files. G2 version-specific files remain untouched.
			for (int fid : new int[] {0x0501, 0x0520, 0x0521, 0x0502, 0x0503, 0x0504,
					0x0505, 0x0506, 0x0507, 0x0508, 0x0522}) {
				require(blocks, fid * 256 + generation, 1);
				require(blocks, fid * 256 + generation + 1, generation == 0 ? 128 : 1);
			}
			byte[] application = require(blocks, 0x050100 + generation, generation == 0 ? 10 : 16);
			if (application[0] != 1) throw new IOException("Not a driver card");
			require(blocks, 0x052000 + generation, 143);
			require(blocks, 0x050400 + generation, 4);
		}
		for (int tag : blocks.keySet()) {
			if ((tag & 1) != 0 && !blocks.containsKey(tag - 1)) throw new IOException("DDD signature without data");
		}
		require(blocks, 0x000200, 1); require(blocks, 0x000500, 1);
		require(blocks, 0xc10000, 1); require(blocks, 0xc10800, 1);
		return new String(blocks.get(0x052000), 1, 16, StandardCharsets.US_ASCII)
				.replaceAll("[^A-Za-z0-9]", "");
	}

	private static byte[] require(Map<Integer, byte[]> blocks, int tag, int minimum) throws IOException {
		byte[] data = blocks.get(tag);
		if (data == null || data.length < minimum) throw new IOException("Incomplete DDD block " + Integer.toHexString(tag));
		return data;
	}

	private static int u16(byte[] bytes, int offset) { return ((bytes[offset] & 255) << 8) | (bytes[offset + 1] & 255); }
}
