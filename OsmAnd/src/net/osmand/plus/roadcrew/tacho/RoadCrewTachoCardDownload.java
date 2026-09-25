package net.osmand.plus.roadcrew.tacho;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Downloads a driver card into one DDD file, generation 1 and generation 2,
 * following Commission Implementing Regulation (EU) 2016/799 Annex 1C,
 * Appendix 7 section 3 as amended by 2018/502 (DDP_035-DDP_046), with the
 * commands of Appendix 2 (TCS_39, TCS_42/43, TCS_124/125, TCS_130/131) and the
 * cipher suites of Appendix 11 (CSM_48, CSM_50). The rules as extracted from
 * the official text are in docs/tachograph-card-download-protocol.md, section 7.
 *
 * Pure Java on purpose: it talks to the card only through {@link
 * RoadCrewTachoDownloadDate.Channel}, so every rule here is tested against a
 * simulated card before it meets a real one (ROADMAP 327).
 *
 * It never writes to the card. Marking the card as downloaded is {@link
 * #markDownloaded}, a separate step the caller may take only after the DDD
 * returned here has been stored.
 */
public final class RoadCrewTachoCardDownload {

	static final byte[] AID_G1 = {(byte) 0xFF, 0x54, 0x41, 0x43, 0x48, 0x4F};
	static final byte[] AID_G2 = {(byte) 0xFF, 0x53, 0x4D, 0x52, 0x44, 0x54};

	static final int EF_ICC = 0x0002;
	static final int EF_IC = 0x0005;
	static final int EF_APPLICATION_IDENTIFICATION = 0x0501;
	static final int EF_CARD_DOWNLOAD = 0x050E;
	static final int EF_IDENTIFICATION = 0x0520;
	static final int EF_CARD_CERTIFICATE = 0xC100;
	static final int EF_CARD_SIGN_CERTIFICATE = 0xC101;
	static final int EF_CA_CERTIFICATE = 0xC108;
	static final int EF_LINK_CERTIFICATE = 0xC109;

	/**
	 * DF Tachograph, signed application EFs of a driver card, all except
	 * Card_Download (DDP_035). Application_Identification first: it tells a
	 * driver card from any other before anything else is read.
	 */
	static final int[] SIGNED_G1 = {0x0501, 0x0520, 0x0521, 0x0502, 0x0503, 0x0504,
			0x0505, 0x0506, 0x0507, 0x0508, 0x0522};

	/**
	 * DF Tachograph_G2 (TCS_152), signed, all except Card_Download. 0525-0530
	 * exist only on version 2 cards; an absent file is simply not stored
	 * (DDP_045).
	 */
	static final int[] SIGNED_G2 = {0x0501, 0xC100, 0x0520, 0x0521, 0x0502, 0x0503,
			0x0504, 0x0505, 0x0506, 0x0507, 0x0508, 0x0522, 0x0523, 0x0524, 0x0525,
			0x0526, 0x0527, 0x0528, 0x0529, 0x0530};

	/** Appendix bytes of the DDD tag (DDP_042/043, DDP_046 as amended by 2018/502). */
	static final int TAG_DATA_G1 = 0x00;
	static final int TAG_SIGNATURE_G1 = 0x01;
	static final int TAG_DATA_G2 = 0x02;
	static final int TAG_SIGNATURE_G2 = 0x03;

	/** Generation 1 signatures are 1024-bit RSA, PKCS#1 (TCS_128, TCS_133). */
	static final int SIGNATURE_LENGTH_G1 = 128;
	/**
	 * TCS_124 as amended by 2018/502: P2 is always '00h', "algorithm implicitly
	 * known" - SHA-1 in DF Tachograph, the SHA-2 of the Card_Sign cipher suite
	 * in Tachograph_G2. The original 2016 values 01..03 are refused (6A86).
	 */
	static final int HASH_IMPLICIT = 0x00;

	/** Largest Le of a short READ BINARY. */
	static final int CHUNK = 0xFF;
	/** P1 bit 8 must stay clear (TCS_42), so offsets end at 0x7FFF. */
	static final int MAX_OFFSET = 0x7FFF;

	private static final int SW_OK = 0x9000;
	private static final int SW_INTEGRITY_WARNING = 0x6281;
	private static final int SW_WRONG_LENGTH = 0x6700;
	private static final int SW_OFFSET_BEYOND_END = 0x6B00;
	private static final int SW_FILE_NOT_FOUND = 0x6A82;

	private RoadCrewTachoCardDownload() {
	}

	/** One downloaded card. */
	public static final class Result {
		public final byte[] ddd;
		public final boolean secondGeneration;
		/** Issuing member state code and the 16-character card number, as on the card. */
		public final int issuingMemberState;
		public final String cardNumber;
		/** Files stored, as their DDD data tags (FID << 8 | appendix). */
		public final List<Integer> storedTags;
		/** Files the card said it does not have (DDP_045: nothing stored). */
		public final List<String> absent;
		public final List<String> warnings;

		Result(byte[] ddd, boolean secondGeneration, int issuingMemberState, String cardNumber,
				List<Integer> storedTags, List<String> absent, List<String> warnings) {
			this.ddd = ddd;
			this.secondGeneration = secondGeneration;
			this.issuingMemberState = issuingMemberState;
			this.cardNumber = cardNumber;
			this.storedTags = Collections.unmodifiableList(storedTags);
			this.absent = Collections.unmodifiableList(absent);
			this.warnings = Collections.unmodifiableList(warnings);
		}
	}

	/** Reads the whole driver card. Writes nothing. */
	public static Result download(RoadCrewTachoDownloadDate.Channel channel) throws IOException {
		ByteArrayOutputStream ddd = new ByteArrayOutputStream();
		List<Integer> stored = new ArrayList<>();
		List<String> absent = new ArrayList<>();
		List<String> warnings = new ArrayList<>();

		// Common information under the MF, current right after the reset:
		// optional and unsigned (DDP_035).
		for (int fid : new int[]{EF_ICC, EF_IC}) {
			byte[] data = readUnsigned(channel, fid, warnings);
			if (data == null) {
				absent.add(hex(fid) + " (MF)");
			} else {
				tlv(ddd, fid, TAG_DATA_G1, data);
				stored.add(fid << 8 | TAG_DATA_G1);
			}
		}

		// DF Tachograph: every driver card has it, generation 2 cards too.
		expect(transceive(channel, selectByAid(AID_G1)), "SELECT DF Tachograph");
		for (int fid : new int[]{EF_CARD_CERTIFICATE, EF_CA_CERTIFICATE}) {
			byte[] data = readUnsigned(channel, fid, warnings);
			if (data == null) {
				throw new IOException("Mandatory " + hex(fid) + " missing in DF Tachograph");
			}
			tlv(ddd, fid, TAG_DATA_G1, data);
			stored.add(fid << 8 | TAG_DATA_G1);
		}
		byte[] identification = null;
		for (int fid : SIGNED_G1) {
			byte[][] signed = readSigned(channel, fid, SIGNATURE_LENGTH_G1, warnings);
			if (signed == null) {
				if (fid == EF_APPLICATION_IDENTIFICATION || fid == EF_IDENTIFICATION) {
					throw new IOException("Mandatory " + hex(fid) + " missing in DF Tachograph");
				}
				absent.add(hex(fid) + " (Tachograph)");
				continue;
			}
			if (fid == EF_APPLICATION_IDENTIFICATION && (signed[0].length < 1 || signed[0][0] != 1)) {
				// typeOfTachographCardId: only a driver card is downloaded here.
				throw new IOException("Not a driver card (type "
						+ (signed[0].length < 1 ? "?" : Integer.toString(signed[0][0] & 0xFF)) + ")");
			}
			if (fid == EF_IDENTIFICATION) {
				identification = signed[0];
			}
			tlv(ddd, fid, TAG_DATA_G1, signed[0]);
			tlv(ddd, fid, TAG_SIGNATURE_G1, signed[1]);
			stored.add(fid << 8 | TAG_DATA_G1);
		}

		// DF Tachograph_G2, present only on a generation 2 card.
		byte[] reply = transceive(channel, selectByAid(AID_G2));
		boolean secondGeneration = sw(reply) == SW_OK;
		if (!secondGeneration && sw(reply) != SW_FILE_NOT_FOUND) {
			throw new IOException(String.format("SELECT DF Tachograph_G2 SW=%04X", sw(reply)));
		}
		if (secondGeneration) {
			byte[] signCertificate = null;
			for (int fid : new int[]{EF_CARD_SIGN_CERTIFICATE, EF_CA_CERTIFICATE, EF_LINK_CERTIFICATE}) {
				byte[] data = readUnsigned(channel, fid, warnings);
				if (data == null) {
					if (fid != EF_LINK_CERTIFICATE) {
						throw new IOException("Mandatory " + hex(fid) + " missing in DF Tachograph_G2");
					}
					absent.add(hex(fid) + " (Tachograph_G2)");
					continue;
				}
				if (fid == EF_CARD_SIGN_CERTIFICATE) {
					signCertificate = data;
				}
				tlv(ddd, fid, TAG_DATA_G2, data);
				stored.add(fid << 8 | TAG_DATA_G2);
			}
			Curve curve = curveOf(signCertificate);
			for (int fid : SIGNED_G2) {
				byte[][] signed = readSigned(channel, fid, curve.signatureLength, warnings);
				if (signed == null) {
					if (fid == EF_APPLICATION_IDENTIFICATION || fid == EF_IDENTIFICATION) {
						throw new IOException("Mandatory " + hex(fid) + " missing in DF Tachograph_G2");
					}
					absent.add(hex(fid) + " (Tachograph_G2)");
					continue;
				}
				tlv(ddd, fid, TAG_DATA_G2, signed[0]);
				tlv(ddd, fid, TAG_SIGNATURE_G2, signed[1]);
				stored.add(fid << 8 | TAG_DATA_G2);
			}
		}

		int state = identification != null && identification.length >= 17 ? identification[0] & 0xFF : -1;
		String number = identification != null && identification.length >= 17
				? new String(identification, 1, 16, StandardCharsets.ISO_8859_1).trim() : "";
		return new Result(ddd.toByteArray(), secondGeneration, state, number, stored, absent, warnings);
	}

	/**
	 * Marks the card as downloaded: LastCardDownload = {@code epochSeconds} in
	 * EF Card_Download of DF Tachograph and, on a generation 2 card, of
	 * Tachograph_G2 (DDP_035 as amended). Each write is read back. Never
	 * retried: a write whose reply is missing may still have happened.
	 */
	public static void markDownloaded(RoadCrewTachoDownloadDate.Channel channel, long epochSeconds,
			boolean secondGeneration, RoadCrewTachoDownloadDate.BeforeWrite observer) throws IOException {
		if (epochSeconds <= 0 || epochSeconds > 0xFFFFFFFFL) {
			throw new IOException("Date outside TimeReal range");
		}
		markOne(channel, AID_G1, epochSeconds, observer);
		if (secondGeneration) {
			markOne(channel, AID_G2, epochSeconds, observer);
		}
	}

	private static void markOne(RoadCrewTachoDownloadDate.Channel channel, byte[] aid, long epochSeconds,
			RoadCrewTachoDownloadDate.BeforeWrite observer) throws IOException {
		expect(transceive(channel, selectByAid(aid)), "SELECT DF");
		expect(transceive(channel, selectEf(EF_APPLICATION_IDENTIFICATION)), "SELECT Application_Identification");
		byte[] type = expectData(transceive(channel, readBinary(0, 1)), 1, "READ card type");
		if (type[0] != 1) {
			throw new IOException("Not a driver card; no date is written");
		}
		expect(transceive(channel, selectEf(EF_CARD_DOWNLOAD)), "SELECT Card_Download");
		byte[] previous = expectData(transceive(channel, readBinary(0, 4)), 4, "READ Card_Download");
		long before = timeReal(previous);
		if (epochSeconds < before) {
			throw new IOException("The card holds a later download date than now; check the phone clock");
		}
		observer.record(before, epochSeconds);
		byte[] value = {(byte) (epochSeconds >>> 24), (byte) (epochSeconds >>> 16),
				(byte) (epochSeconds >>> 8), (byte) epochSeconds};
		try {
			expect(transceive(channel, new byte[]{0x00, (byte) 0xD6, 0x00, 0x00, 0x04,
					value[0], value[1], value[2], value[3]}), "UPDATE Card_Download");
			byte[] readBack = expectData(transceive(channel, readBinary(0, 4)), 4, "READ Card_Download");
			if (!Arrays.equals(value, readBack)) {
				throw new IOException("Card_Download read back differs");
			}
		} catch (IOException e) {
			throw new IOException("Write attempted; result NOT verified. Read the card before any further write. "
					+ e.getMessage(), e);
		}
	}

	/** An unsigned EF: SELECT, READ BINARY to the end. {@code null} if the card has no such file. */
	static byte[] readUnsigned(RoadCrewTachoDownloadDate.Channel channel, int fid, List<String> warnings)
			throws IOException {
		byte[] reply = transceive(channel, selectEf(fid));
		if (sw(reply) == SW_FILE_NOT_FOUND) {
			return null;
		}
		expect(reply, "SELECT " + hex(fid));
		return readWholeFile(channel, fid, warnings);
	}

	/**
	 * A signed EF, in the order DDP_038 prescribes: SELECT, PERFORM HASH OF
	 * FILE, READ BINARY to the end, PSO: COMPUTE DIGITAL SIGNATURE. Returns
	 * {data, signature}, or {@code null} if the card has no such file.
	 */
	static byte[][] readSigned(RoadCrewTachoDownloadDate.Channel channel, int fid, int signatureLength,
			List<String> warnings) throws IOException {
		byte[] reply = transceive(channel, selectEf(fid));
		if (sw(reply) == SW_FILE_NOT_FOUND) {
			return null;
		}
		expect(reply, "SELECT " + hex(fid));
		// Case 1 command: no data, no Le. The transport adds T=0's P3 itself.
		expect(transceive(channel, new byte[]{(byte) 0x80, 0x2A, (byte) 0x90, HASH_IMPLICIT}),
				"PERFORM HASH OF FILE " + hex(fid));
		byte[] data = readWholeFile(channel, fid, warnings);
		byte[] signature = expectData(transceive(channel,
				new byte[]{0x00, 0x2A, (byte) 0x9E, (byte) 0x9A, (byte) signatureLength}),
				signatureLength, "PSO: COMPUTE DIGITAL SIGNATURE " + hex(fid));
		return new byte[][]{data, signature};
	}

	/**
	 * READ BINARY from offset 0 to the end of the selected EF. The end is what
	 * the card reports (TCS_43): a short answer, 6B00 past the end, or 6700 /
	 * 6Cxx when the request runs over it - then the exact remainder is found,
	 * never guessed.
	 */
	static byte[] readWholeFile(RoadCrewTachoDownloadDate.Channel channel, int fid, List<String> warnings)
			throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int offset = 0;
		while (true) {
			if (offset > MAX_OFFSET) {
				throw new IOException(hex(fid) + " is larger than READ BINARY can address");
			}
			byte[] reply = transceive(channel, readBinary(offset, CHUNK));
			int sw = sw(reply);
			if (sw == SW_OK || sw == SW_INTEGRITY_WARNING) {
				if (sw == SW_INTEGRITY_WARNING) {
					warnings.add(hex(fid) + ": card reports an integrity error in stored data (6281)");
				}
				int length = reply.length - 2;
				out.write(reply, 0, length);
				offset += length;
				if (length < CHUNK) {
					return out.toByteArray();
				}
				continue;
			}
			if (sw == SW_OFFSET_BEYOND_END || (sw & 0xFF00) == 0x6C00) {
				// Past the end, or a 6C00 that names no length: nothing is left.
				return out.toByteArray();
			}
			if (sw == SW_WRONG_LENGTH) {
				int remaining = largestReadable(channel, offset);
				if (remaining > 0) {
					byte[] last = expectData(transceive(channel, readBinary(offset, remaining)), remaining,
							"READ BINARY " + hex(fid));
					out.write(last, 0, last.length);
				}
				return out.toByteArray();
			}
			throw new IOException(String.format("READ BINARY %s at %d SW=%04X", hex(fid), offset, sw));
		}
	}

	/** The largest length readable at {@code offset}, for a card that answers 6700 without saying it. */
	private static int largestReadable(RoadCrewTachoDownloadDate.Channel channel, int offset) throws IOException {
		int readable = 0;
		int unreadable = CHUNK;
		while (unreadable - readable > 1) {
			int middle = (readable + unreadable) >>> 1;
			int sw = sw(channel.exchange(readBinary(offset, middle)));
			if (sw == SW_OK || sw == SW_INTEGRITY_WARNING) {
				readable = middle;
			} else if (sw == SW_WRONG_LENGTH || sw == SW_OFFSET_BEYOND_END || (sw & 0xFF00) == 0x6C00) {
				unreadable = middle;
			} else {
				throw new IOException(String.format("READ BINARY length probe at %d SW=%04X", offset, sw));
			}
		}
		return readable;
	}

	/**
	 * Sends one command and resolves T=0's two indirect answers: 6Cxx (repeat
	 * with Le = xx) and 61xx (fetch with GET RESPONSE). Returns data + SW.
	 */
	static byte[] transceive(RoadCrewTachoDownloadDate.Channel channel, byte[] command) throws IOException {
		byte[] reply = checkedReply(channel.exchange(command));
		if ((sw(reply) & 0xFF00) == 0x6C00 && command.length == 5 && (sw(reply) & 0xFF) != 0) {
			byte[] repeated = command.clone();
			repeated[4] = (byte) sw(reply);
			reply = checkedReply(channel.exchange(repeated));
		}
		if ((sw(reply) & 0xFF00) != 0x6100) {
			return reply;
		}
		ByteArrayOutputStream collected = new ByteArrayOutputStream();
		collected.write(reply, 0, reply.length - 2);
		for (int guard = 0; (sw(reply) & 0xFF00) == 0x6100; guard++) {
			if (guard > 64) {
				throw new IOException("GET RESPONSE did not end");
			}
			reply = checkedReply(channel.exchange(new byte[]{0x00, (byte) 0xC0, 0x00, 0x00, (byte) sw(reply)}));
			collected.write(reply, 0, reply.length - 2);
		}
		collected.write(reply, reply.length - 2, 2);
		return collected.toByteArray();
	}

	/** The curve of a generation 2 card's signing key, from the domain parameter OID in its certificate. */
	static final class Curve {
		final String name;
		final int signatureLength;

		Curve(String name, int signatureLength) {
			this.name = name;
			this.signatureLength = signatureLength;
		}
	}

	/** Table 1 (CSM_48) with the signature length of each key size; ECDSA signature = r || s. */
	private static final Object[][] CURVES = {
			{"NIST P-256", new byte[]{0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x03, 0x01, 0x07}, 64},
			{"brainpoolP256r1", new byte[]{0x2B, 0x24, 0x03, 0x03, 0x02, 0x08, 0x01, 0x01, 0x07}, 64},
			{"NIST P-384", new byte[]{0x2B, (byte) 0x81, 0x04, 0x00, 0x22}, 96},
			{"brainpoolP384r1", new byte[]{0x2B, 0x24, 0x03, 0x03, 0x02, 0x08, 0x01, 0x01, 0x0B}, 96},
			{"brainpoolP512r1", new byte[]{0x2B, 0x24, 0x03, 0x03, 0x02, 0x08, 0x01, 0x01, 0x0D}, 128},
			{"NIST P-521", new byte[]{0x2B, (byte) 0x81, 0x04, 0x00, 0x23}, 132},
	};

	static Curve curveOf(byte[] certificate) throws IOException {
		if (certificate != null) {
			for (Object[] curve : CURVES) {
				byte[] oid = (byte[]) curve[1];
				// As a DER object identifier: tag 06, length, value.
				byte[] encoded = new byte[oid.length + 2];
				encoded[0] = 0x06;
				encoded[1] = (byte) oid.length;
				System.arraycopy(oid, 0, encoded, 2, oid.length);
				if (indexOf(certificate, encoded) >= 0) {
					return new Curve((String) curve[0], (Integer) curve[2]);
				}
			}
		}
		throw new IOException("CardSignCertificate names no curve of CSM_48; cannot choose the signature length");
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	private static void tlv(ByteArrayOutputStream out, int fid, int appendix, byte[] value) throws IOException {
		if (value.length >= 0xFFFF) {
			// 'FF FF' is reserved (DDP_044).
			throw new IOException(hex(fid) + " is too large for a DDD length field");
		}
		out.write(fid >>> 8);
		out.write(fid);
		out.write(appendix);
		out.write(value.length >>> 8);
		out.write(value.length);
		out.write(value, 0, value.length);
	}

	static byte[] selectByAid(byte[] aid) {
		byte[] command = new byte[5 + aid.length];
		command[0] = 0x00;
		command[1] = (byte) 0xA4;
		command[2] = 0x04;
		command[3] = 0x0C;
		command[4] = (byte) aid.length;
		System.arraycopy(aid, 0, command, 5, aid.length);
		return command;
	}

	/** SELECT EF by file identifier (TCS_39): P1 02, P2 0C. */
	static byte[] selectEf(int fid) {
		return new byte[]{0x00, (byte) 0xA4, 0x02, 0x0C, 0x02, (byte) (fid >>> 8), (byte) fid};
	}

	static byte[] readBinary(int offset, int length) {
		return new byte[]{0x00, (byte) 0xB0, (byte) (offset >>> 8), (byte) offset, (byte) length};
	}

	private static byte[] checkedReply(byte[] reply) throws IOException {
		if (reply == null || reply.length < 2) {
			throw new IOException("Missing card response");
		}
		return reply;
	}

	static int sw(byte[] reply) {
		return ((reply[reply.length - 2] & 0xFF) << 8) | (reply[reply.length - 1] & 0xFF);
	}

	private static void expect(byte[] reply, String what) throws IOException {
		if (sw(reply) != SW_OK) {
			throw new IOException(String.format("%s SW=%04X", what, sw(reply)));
		}
	}

	private static byte[] expectData(byte[] reply, int length, String what) throws IOException {
		expect(reply, what);
		if (reply.length - 2 != length) {
			throw new IOException(what + ": " + (reply.length - 2) + " bytes, expected " + length);
		}
		return Arrays.copyOf(reply, length);
	}

	private static long timeReal(byte[] value) {
		return ((value[0] & 0xFFL) << 24) | ((value[1] & 0xFFL) << 16)
				| ((value[2] & 0xFFL) << 8) | (value[3] & 0xFFL);
	}

	static String hex(int fid) {
		return String.format("%04X", fid);
	}
}
