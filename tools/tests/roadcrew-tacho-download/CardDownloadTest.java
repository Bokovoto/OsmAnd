import net.osmand.plus.roadcrew.tacho.RoadCrewTachoCardDownload;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoDownloadDate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A simulated driver card that answers exactly as Appendix 2 prescribes
 * (TCS_39/42/43/124/125/130/131), and the checks the DDD must pass
 * (Appendix 7 DDP_035-046 as amended by 2018/502). ROADMAP 327.
 */
public class CardDownloadTest {

	static int passed = 0;

	static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError(what);
		}
		passed++;
	}

	static final byte[] AID_G1 = {(byte) 0xFF, 0x54, 0x41, 0x43, 0x48, 0x4F};
	static final byte[] AID_G2 = {(byte) 0xFF, 0x53, 0x4D, 0x52, 0x44, 0x54};
	static final byte[] BRAINPOOL_256 = {0x06, 0x09, 0x2B, 0x24, 0x03, 0x03, 0x02, 0x08, 0x01, 0x01, 0x07};
	static final byte[] NIST_384 = {0x06, 0x05, 0x2B, (byte) 0x81, 0x04, 0x00, 0x22};
	static final byte[] NIST_256 = {0x06, 0x08, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x03, 0x01, 0x07};
	/** The engine's order of the signed Tachograph_G2 files (TCS_152, DDP_035). */
	static final int[] SIGNED_G2 = {0x0501, 0xC100, 0x0520, 0x0521, 0x0502, 0x0503, 0x0504, 0x0505, 0x0506,
			0x0507, 0x0508, 0x0522, 0x0523, 0x0524, 0x0525, 0x0526, 0x0527, 0x0528, 0x0529, 0x0530};

	/** How a card answers a READ BINARY that runs past the end (TCS_43 allows both). */
	enum EndOfFile { SIX_C, SIX_SEVEN }

	static final class Card implements RoadCrewTachoDownloadDate.Channel {
		final Map<Integer, byte[]> mf = new LinkedHashMap<>();
		final Map<Integer, byte[]> g1 = new LinkedHashMap<>();
		final Map<Integer, byte[]> g2 = new LinkedHashMap<>();
		boolean hasG2;
		EndOfFile endOfFile = EndOfFile.SIX_C;
		boolean chainSignatures;
		int g2SignatureLength = 64;
		Map<Integer, byte[]> current = mf;
		String currentDf = "MF";
		Integer currentEf;
		Integer hashAlgorithm;
		final List<String> log = new ArrayList<>();
		final Map<String, Integer> hashUsed = new HashMap<>();
		int writes;
		byte[] pendingResponse;

		public byte[] exchange(byte[] c) throws IOException {
			int ins = c[1] & 0xFF;
			if (c[0] == 0x00 && ins == 0xA4 && c[2] == 0x04) {
				byte[] aid = Arrays.copyOfRange(c, 5, 5 + c[4]);
				if (Arrays.equals(aid, AID_G1)) {
					current = g1; currentDf = "G1";
				} else if (Arrays.equals(aid, AID_G2) && hasG2) {
					current = g2; currentDf = "G2";
				} else {
					return sw(0x6A82);
				}
				currentEf = null; hashAlgorithm = null;
				log.add("SELECT " + currentDf);
				return sw(0x9000);
			}
			if (c[0] == 0x00 && ins == 0xA4 && c[2] == 0x02 && c[3] == 0x0C) {
				int fid = ((c[5] & 0xFF) << 8) | (c[6] & 0xFF);
				hashAlgorithm = null;
				if (!current.containsKey(fid)) {
					currentEf = null;
					return sw(0x6A82);
				}
				currentEf = fid;
				log.add("SELECT " + currentDf + ":" + hex(fid));
				return sw(0x9000);
			}
			if ((c[0] & 0xFF) == 0x80 && ins == 0x2A && (c[2] & 0xFF) == 0x90) {
				if (c.length != 4) {
					return sw(0x6700);
				}
				if (currentEf == null) {
					return sw(0x6986);
				}
				// TCS_124 as amended by 2018/502: P2 is always '00h', the card
				// knows the algorithm (SHA-1 in DF Tachograph, the Card_Sign
				// suite's SHA-2 in Tachograph_G2). The real Gen2 card answers
				// 6A86 to the 2016 values 01..03.
				if ((c[3] & 0xFF) != 0x00) {
					return sw(0x6A86);
				}
				hashAlgorithm = c[3] & 0xFF;
				hashUsed.put(currentDf + ":" + hex(currentEf), hashAlgorithm);
				log.add("HASH " + currentDf + ":" + hex(currentEf));
				return sw(0x9000);
			}
			if (c[0] == 0x00 && ins == 0xB0) {
				if (currentEf == null) {
					return sw(0x6986);
				}
				byte[] file = current.get(currentEf);
				int offset = ((c[2] & 0x7F) << 8) | (c[3] & 0xFF);
				int le = c[4] & 0xFF;
				if (offset > file.length) {
					return sw(0x6B00);
				}
				if (offset + le > file.length) {
					return endOfFile == EndOfFile.SIX_C ? sw(0x6C00 | (file.length - offset)) : sw(0x6700);
				}
				log.add("READ " + currentDf + ":" + hex(currentEf) + "@" + offset);
				return withSw(Arrays.copyOfRange(file, offset, offset + le), 0x9000);
			}
			if (c[0] == 0x00 && ins == 0x2A && (c[2] & 0xFF) == 0x9E && (c[3] & 0xFF) == 0x9A) {
				if (hashAlgorithm == null) {
					return sw(0x6985);
				}
				int length = currentDf.equals("G1") ? 128 : g2SignatureLength;
				if ((c[4] & 0xFF) != length) {
					return sw(0x6C00 | length);
				}
				byte[] signature = signature(currentDf, currentEf, current.get(currentEf), length);
				hashAlgorithm = null;
				log.add("SIGN " + currentDf + ":" + hex(currentEf));
				if (chainSignatures) {
					pendingResponse = signature;
					return sw(0x6100 | (length & 0xFF));
				}
				return withSw(signature, 0x9000);
			}
			if (c[0] == 0x00 && ins == 0xC0) {
				byte[] rest = pendingResponse;
				pendingResponse = null;
				return rest == null ? sw(0x6985) : withSw(rest, 0x9000);
			}
			if (c[0] == 0x00 && ins == 0xD6) {
				if (currentEf == null || currentEf != 0x050E) {
					return sw(0x6982);
				}
				byte[] value = Arrays.copyOfRange(c, 5, 5 + c[4]);
				System.arraycopy(value, 0, current.get(0x050E), 0, 4);
				writes++;
				log.add("UPDATE " + currentDf + ":050E");
				return sw(0x9000);
			}
			return sw(0x6D00);
		}
	}

	static byte[] signature(String df, int fid, byte[] data, int length) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-512");
			digest.update(df.getBytes(StandardCharsets.US_ASCII));
			digest.update((byte) (fid >> 8));
			digest.update((byte) fid);
			digest.update(data);
			byte[] seed = digest.digest();
			byte[] out = new byte[length];
			for (int i = 0; i < length; i++) {
				out[i] = seed[i % seed.length];
			}
			return out;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	static byte[] sw(int sw) {
		return new byte[]{(byte) (sw >> 8), (byte) sw};
	}

	static byte[] withSw(byte[] data, int sw) {
		byte[] out = Arrays.copyOf(data, data.length + 2);
		out[data.length] = (byte) (sw >> 8);
		out[data.length + 1] = (byte) sw;
		return out;
	}

	static byte[] bytes(int length, int seed) {
		byte[] out = new byte[length];
		for (int i = 0; i < length; i++) {
			out[i] = (byte) (i * 31 + seed * 7 + (i >> 8));
		}
		return out;
	}

	static byte[] identification(String number) {
		byte[] id = bytes(143, 20);
		id[0] = 0x0B; // issuing member state
		byte[] n = String.format("%-16s", number).getBytes(StandardCharsets.ISO_8859_1);
		System.arraycopy(n, 0, id, 1, 16);
		return id;
	}

	/** EF Identification with a holder name: CardIdentification (65 bytes), then surname and first names (codePage + 35 bytes each). */
	static byte[] identification(String number, int codePage, String surname, String firstNames) throws Exception {
		byte[] id = identification(number);
		String charset = codePage == 5 ? "ISO-8859-5" : "ISO-8859-1";
		id[65] = (byte) codePage;
		byte[] s = String.format("%-35s", surname).getBytes(charset);
		System.arraycopy(s, 0, id, 66, 35);
		id[101] = (byte) codePage;
		byte[] f = String.format("%-35s", firstNames).getBytes(charset);
		System.arraycopy(f, 0, id, 102, 35);
		return id;
	}

	static byte[] applicationIdentification(int type, int length) {
		byte[] a = bytes(length, 1);
		a[0] = (byte) type;
		return a;
	}

	static Card gen1Card() {
		Card card = new Card();
		card.mf.put(0x0002, bytes(25, 2));
		card.mf.put(0x0005, bytes(8, 5));
		card.g1.put(0xC100, bytes(194, 100));
		card.g1.put(0xC108, bytes(194, 108));
		card.g1.put(0x0501, applicationIdentification(1, 10));
		card.g1.put(0x0520, identification("0000000123456700"));
		card.g1.put(0x050E, new byte[]{0x60, 0x00, 0x00, 0x00});
		card.g1.put(0x0521, bytes(53, 21));
		card.g1.put(0x0502, bytes(1728, 2));
		card.g1.put(0x0503, bytes(1152, 3));
		card.g1.put(0x0504, bytes(13780, 4));
		card.g1.put(0x0505, bytes(6202, 5));
		card.g1.put(0x0506, bytes(1121, 6));
		card.g1.put(0x0507, bytes(19, 7));
		card.g1.put(0x0508, bytes(46, 8));
		card.g1.put(0x0522, bytes(510, 22)); // exactly two chunks
		return card;
	}

	static Card gen2Card(byte[] curve, int signatureLength) {
		Card card = gen1Card();
		card.hasG2 = true;
		card.g2SignatureLength = signatureLength;
		byte[] sign = bytes(220, 101);
		System.arraycopy(curve, 0, sign, 60, curve.length);
		card.g2.put(0xC101, sign);
		card.g2.put(0xC100, bytes(220, 100));
		card.g2.put(0xC108, bytes(220, 108));
		card.g2.put(0x0501, applicationIdentification(1, 17));
		card.g2.put(0x0520, identification("0000000123456700"));
		card.g2.put(0x050E, new byte[]{0x60, 0x00, 0x00, 0x00});
		card.g2.put(0x0521, bytes(53, 121));
		card.g2.put(0x0502, bytes(3168, 102));
		card.g2.put(0x0503, bytes(1152, 103));
		card.g2.put(0x0504, bytes(13780, 104));
		card.g2.put(0x0505, bytes(9602, 105));
		card.g2.put(0x0506, bytes(2354, 106));
		card.g2.put(0x0507, bytes(19, 107));
		card.g2.put(0x0508, bytes(46, 108));
		card.g2.put(0x0522, bytes(562, 122));
		card.g2.put(0x0523, bytes(255, 123)); // exactly one chunk
		card.g2.put(0x0524, bytes(6308, 124));
		return card;
	}

	/**
	 * The shape of Galin's Gen2 working card as it answered on 25.09 (ROADMAP
	 * 327): every file size, a NIST P-256 signing key, Link_Certificate
	 * present but 205 zero bytes, version 2 files 0525-0530, 6700 past the end
	 * of a file, P2 00 only. No personal data: the contents are synthetic.
	 */
	static Card workingCardTwin() {
		Card card = gen2Card(NIST_256, 64);
		card.endOfFile = EndOfFile.SIX_SEVEN;
		card.g1.put(0x0522, bytes(280, 22));
		byte[] sign = bytes(204, 101);
		System.arraycopy(NIST_256, 0, sign, 60, NIST_256.length);
		card.g2.put(0xC101, sign);
		card.g2.put(0xC100, bytes(204, 100));
		card.g2.put(0xC108, bytes(205, 108));
		card.g2.put(0xC109, new byte[205]);
		card.g2.put(0x0523, bytes(2002, 123));
		card.g2.put(0x0524, bytes(6050, 124));
		card.g2.put(0x0525, bytes(10, 125));
		card.g2.put(0x0526, bytes(562, 126));
		card.g2.put(0x0527, bytes(1682, 127));
		card.g2.put(0x0528, bytes(19042, 128));
		card.g2.put(0x0529, bytes(32482, 129));
		card.g2.put(0x0530, bytes(1682, 130));
		return card;
	}

	/** The DDD as TLVs, in order: [tag, value]. */
	static List<Object[]> parse(byte[] ddd) {
		List<Object[]> out = new ArrayList<>();
		int i = 0;
		while (i < ddd.length) {
			int tag = ((ddd[i] & 0xFF) << 16) | ((ddd[i + 1] & 0xFF) << 8) | (ddd[i + 2] & 0xFF);
			int length = ((ddd[i + 3] & 0xFF) << 8) | (ddd[i + 4] & 0xFF);
			check(i + 5 + length <= ddd.length, "TLV runs past the end of the DDD");
			out.add(new Object[]{tag, Arrays.copyOfRange(ddd, i + 5, i + 5 + length)});
			i += 5 + length;
		}
		return out;
	}

	static void checkDdd(Card card, RoadCrewTachoCardDownload.Result result) {
		List<Object[]> tlvs = parse(result.ddd);
		List<String> tags = new ArrayList<>();
		for (Object[] t : tlvs) {
			tags.add(String.format("%06X", (Integer) t[0]));
		}
		List<String> expected = new ArrayList<>(Arrays.asList(
				"000200", "000500", "C10000", "C10800",
				"050100", "050101", "052000", "052001", "052100", "052101",
				"050200", "050201", "050300", "050301", "050400", "050401", "050500", "050501",
				"050600", "050601", "050700", "050701", "050800", "050801", "052200", "052201"));
		if (card.hasG2) {
			for (int fid : new int[]{0xC101, 0xC108, 0xC109}) {
				if (card.g2.containsKey(fid)) {
					expected.add(String.format("%04X02", fid));
				}
			}
			for (int fid : SIGNED_G2) {
				if (card.g2.containsKey(fid)) {
					expected.add(String.format("%04X02", fid));
					expected.add(String.format("%04X03", fid));
				}
			}
		}
		check(tags.equals(expected), "DDD tags in order\n expected " + expected + "\n got      " + tags);
		for (Object[] t : tlvs) {
			int tag = (Integer) t[0];
			int fid = tag >> 8;
			int appendix = tag & 0xFF;
			Map<Integer, byte[]> df = fid == 0x0002 || fid == 0x0005 ? card.mf
					: appendix <= 1 ? card.g1 : card.g2;
			if (appendix == 0 || appendix == 2) {
				check(Arrays.equals(df.get(fid), (byte[]) t[1]), "data of " + String.format("%06X", tag) + " is the file");
			} else {
				int length = appendix == 1 ? 128 : card.g2SignatureLength;
				check(Arrays.equals(signature(appendix == 1 ? "G1" : "G2", fid, df.get(fid), length), (byte[]) t[1]),
						"signature of " + String.format("%06X", tag) + " is the card's, for that file");
			}
		}
		check(!tags.contains("050E00") && !tags.contains("050E02"), "Card_Download is not downloaded (DDP_035)");
		check(card.writes == 0, "download writes nothing");
		check(result.cardNumber.equals("0000000123456700") && result.issuingMemberState == 0x0B, "card number");
		check(result.secondGeneration == card.hasG2, "generation");
	}

	static void checkOrderAndHash(Card card) {
		// DDP_038: SELECT, HASH, READ..., SIGN for every signed file; no READ of it before its HASH.
		String hashed = null;
		for (String entry : card.log) {
			if (entry.startsWith("READ ")) {
				String file = entry.substring(5, entry.indexOf('@'));
				boolean signedFile = card.hashUsed.containsKey(file);
				check(!signedFile || file.equals(hashed), "a signed file is read only after its hash: " + entry);
			} else if (entry.startsWith("HASH ")) {
				hashed = entry.substring(5);
			} else if (entry.startsWith("SIGN ")) {
				check(entry.substring(5).equals(hashed), "signature follows the hash of the same file: " + entry);
				hashed = null;
			}
		}
		for (Map.Entry<String, Integer> used : card.hashUsed.entrySet()) {
			int want = 0x00; // TCS_124 (2018/502): implicit in both DFs
			check(used.getValue() == want, "hash algorithm for " + used.getKey());
		}
	}

	public static void main(String[] args) throws Exception {
		for (EndOfFile end : EndOfFile.values()) {
			Card card = gen1Card();
			card.endOfFile = end;
			RoadCrewTachoCardDownload.Result result = RoadCrewTachoCardDownload.download(card);
			checkDdd(card, result);
			checkOrderAndHash(card);
			check(result.absent.isEmpty(), "gen1: nothing absent " + result.absent);
		}

		Card chained = gen1Card();
		chained.chainSignatures = true;
		checkDdd(chained, RoadCrewTachoCardDownload.download(chained));

		Card g2 = gen2Card(BRAINPOOL_256, 64);
		RoadCrewTachoCardDownload.Result g2Result = RoadCrewTachoCardDownload.download(g2);
		checkDdd(g2, g2Result);
		checkOrderAndHash(g2);
		check(g2Result.absent.contains("C109 (Tachograph_G2)"), "absent Link_Certificate is omitted and reported");

		Card g2p384 = gen2Card(NIST_384, 96);
		g2p384.endOfFile = EndOfFile.SIX_SEVEN;
		checkDdd(g2p384, RoadCrewTachoCardDownload.download(g2p384));
		checkOrderAndHash(g2p384);

		// The working card's twin: 38 files, and progress after every one of them.
		Card twin = workingCardTwin();
		List<int[]> steps = new ArrayList<>();
		RoadCrewTachoCardDownload.Result twinResult = RoadCrewTachoCardDownload.download(twin,
				(done, total, fid, secondGeneration) -> steps.add(new int[]{done, total, fid, secondGeneration ? 1 : 0}));
		checkDdd(twin, twinResult);
		checkOrderAndHash(twin);
		check(twinResult.storedTags.size() == 38 && twinResult.absent.isEmpty() && twinResult.warnings.isEmpty(),
				"twin: 38 files like the real card, nothing absent, no warnings");
		check(steps.size() == 38, "twin: one progress step per file, got " + steps.size());
		for (int i = 0; i < steps.size(); i++) {
			check(steps.get(i)[0] == i + 1 && steps.get(i)[1] == 38, "twin: step " + (i + 1) + " of 38");
		}
		check(steps.get(0)[2] == 0x0002 && steps.get(37)[2] == 0x0530 && steps.get(37)[3] == 1,
				"twin: from ICC to the last Tachograph_G2 file");

		List<int[]> steps1 = new ArrayList<>();
		RoadCrewTachoCardDownload.download(gen1Card(),
				(done, total, fid, secondGeneration) -> steps1.add(new int[]{done, total}));
		int[] last = steps1.get(steps1.size() - 1);
		check(last[0] == 15 && last[1] == 15,
				"gen1: the total drops to 15 once there is no Tachograph_G2, and ends at 15 of 15");
		for (int i = 0; i < steps1.size(); i++) {
			check(steps1.get(i)[0] <= steps1.get(i)[1], "done never exceeds total");
			check(i == 0 || steps1.get(i)[0] >= steps1.get(i - 1)[0], "done never goes back");
		}

		// What the screen shows before the download: read only, nothing written.
		Card named = gen2Card(BRAINPOOL_256, 64);
		named.g1.put(0x0520, identification("0000000123456700", 5, "ИВАНОВ", "ПЕТЪР"));
		RoadCrewTachoCardDownload.CardInfo info = RoadCrewTachoCardDownload.readInfo(named);
		check(info.driverCard, "info: a driver card");
		check(info.cardNumber.equals("0000000123456700") && info.issuingMemberState == 0x0B, "info: card number");
		check(info.surname.equals("ИВАНОВ") && info.firstNames.equals("ПЕТЪР"),
				"info: Cyrillic name through code page 5, got " + info.surname + " / " + info.firstNames);
		check(info.lastDownload == 0x60000000L, "info: LastCardDownload");
		check(named.writes == 0, "info: nothing written");
		Card latin = gen1Card();
		latin.g1.put(0x0520, identification("0000000123456700", 1, "MÜLLER", "ANNA"));
		RoadCrewTachoCardDownload.CardInfo latinInfo = RoadCrewTachoCardDownload.readInfo(latin);
		check(latinInfo.surname.equals("MÜLLER") && latinInfo.firstNames.equals("ANNA"), "info: code page 1");
		Card companyInfo = gen1Card();
		companyInfo.g1.put(0x0501, applicationIdentification(4, 10));
		check(!RoadCrewTachoCardDownload.readInfo(companyInfo).driverCard, "info: a company card is not a driver card");

		Card company = gen1Card();
		company.g1.put(0x0501, applicationIdentification(4, 10));
		try {
			RoadCrewTachoCardDownload.download(company);
			check(false, "a company card must not be downloaded as a driver card");
		} catch (IOException expected) {
			check(expected.getMessage().contains("Not a driver card"), "refusal names the reason");
		}
		check(company.writes == 0, "refused card: nothing written");

		Card noCa = gen1Card();
		noCa.g1.remove(0xC108);
		try {
			RoadCrewTachoCardDownload.download(noCa);
			check(false, "a missing CA_Certificate must fail the download");
		} catch (IOException expected) {
			check(expected.getMessage().contains("C108"), "missing mandatory file named");
		}

		Card unknownCurve = gen2Card(new byte[]{0x06, 0x03, 0x2B, 0x65, 0x70}, 64);
		try {
			RoadCrewTachoCardDownload.download(unknownCurve);
			check(false, "an unknown curve must not be guessed");
		} catch (IOException expected) {
			check(expected.getMessage().contains("curve"), "unknown curve refused");
		}

		Card noOptional = gen1Card();
		noOptional.g1.remove(0x0522);
		noOptional.mf.remove(0x0005);
		RoadCrewTachoCardDownload.Result partial = RoadCrewTachoCardDownload.download(noOptional);
		String tags = hexString(partial.ddd);
		check(!tags.contains("0522") || partial.absent.contains("0522 (Tachograph)"), "absent 0522 reported");
		check(partial.absent.contains("0005 (MF)"), "absent IC reported");

		// Marking: both DFs on a generation 2 card, each read back; an observer sees it first.
		Card mark = gen2Card(BRAINPOOL_256, 64);
		List<String> observed = new ArrayList<>();
		long now = 0x68D50000L;
		RoadCrewTachoCardDownload.markDownloaded(mark, now, true, (before, requested) ->
				observed.add(before + "->" + requested + "@" + mark.writes));
		check(mark.writes == 2, "gen2: Card_Download written in both DFs");
		check(observed.size() == 2 && observed.get(0).endsWith("@0") && observed.get(1).endsWith("@1"),
				"each write is recorded before it happens");
		byte[] expectedDate = {(byte) (now >> 24), (byte) (now >> 16), (byte) (now >> 8), (byte) now};
		check(Arrays.equals(mark.g1.get(0x050E), expectedDate) && Arrays.equals(mark.g2.get(0x050E), expectedDate),
				"both dates read back");

		Card mark1 = gen1Card();
		RoadCrewTachoCardDownload.markDownloaded(mark1, now, false, (before, requested) -> { });
		check(mark1.writes == 1, "gen1: one write");

		Card future = gen1Card();
		future.g1.put(0x050E, new byte[]{(byte) 0x7F, 0, 0, 0});
		try {
			RoadCrewTachoCardDownload.markDownloaded(future, now, false, (before, requested) -> { });
			check(false, "a later stored date must stop the write");
		} catch (IOException expected) {
			check(future.writes == 0, "nothing written when the clock looks wrong");
		}

		Card companyMark = gen1Card();
		companyMark.g1.put(0x0501, applicationIdentification(4, 10));
		try {
			RoadCrewTachoCardDownload.markDownloaded(companyMark, now, false, (before, requested) -> { });
			check(false, "no date on a non-driver card");
		} catch (IOException expected) {
			check(companyMark.writes == 0, "non-driver card: nothing written");
		}

		System.out.println(passed + " card download checks passed");
	}

	static String hexString(byte[] bytes) {
		StringBuilder b = new StringBuilder();
		for (byte x : bytes) {
			b.append(String.format("%02X", x));
		}
		return b.toString();
	}

	static String hex(int fid) {
		return String.format("%04X", fid);
	}
}
