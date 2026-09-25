package net.osmand.plus.roadcrew.tacho;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Experimental ISO 7816-4 access through {@link RoadCrewTachoCcidTransport}.
 * Summary and scan operations only select and read files. A separate,
 * explicitly confirmed dev.tacho action delegates the experimental EF050E
 * date write to {@link RoadCrewTachoDownloadDate}. It is not a DDD download.
 *
 * The two file IDs below are the EU digital tachograph card structure's own
 * (Commission Implementing Regulation (EU) 2016/799, Annex 1C - the same
 * family of specification whichever card generation is inserted): the master
 * file, the Tachograph application's dedicated file, and its Application
 * Identification file, which on every compliant card is readable without any
 * security condition. They are read here as a first, harmless proof that
 * RoadCrew can hold a real conversation with a real tachograph card - not as
 * a complete reader.
 */
public final class RoadCrewTachoCardReader {

	private static final byte[] FID_TACHOGRAPH_DF = {0x05, 0x00};
	private static final byte[] FID_APPLICATION_IDENTIFICATION = {0x05, 0x01};
	// Commission Implementing Regulation (EU) 2016/799, Annex 1C section 3.5.1.1
	// "Selection by name (AID)" - the tachograph application's own AID, not a
	// guessed file id. FF + the application's name in ASCII (TACHO / a
	// Smart-tachograph abbreviation), confirmed by Codex 18.09 against the
	// regulation text.
	public static final byte[] AID_TACHOGRAPH_G1 = {(byte) 0xFF, 0x54, 0x41, 0x43, 0x48, 0x4F};
	public static final byte[] AID_TACHOGRAPH_G2 = {(byte) 0xFF, 0x53, 0x4D, 0x52, 0x44, 0x54};
	private static final int READ_BINARY_MAX_LENGTH = 0xFF;

	/** Everything this first pass could learn about the inserted card, step by step. */
	public static final class CardSummary {
		public final byte[] atr;
		public final List<Step> steps = new ArrayList<>();

		CardSummary(byte[] atr) {
			this.atr = atr;
		}
	}

	/** One APDU attempt and what the card said back - kept even when it failed. */
	public static final class Step {
		public final String label;
		public final boolean ok;
		public final String detail;

		Step(String label, boolean ok, String detail) {
			this.label = label;
			this.ok = ok;
			this.detail = detail;
		}
	}

	private RoadCrewTachoCardReader() {
	}

	/**
	 * Everything needed to keep talking to the same card after
	 * {@link #readSummary} returns: the open transport and which EF, if any,
	 * is currently selected. The date-only experiment uses a fresh selection
	 * and pre-read rather than trusting previous file selections.
	 */
	public static final class OpenCard implements AutoCloseable {
		private final RoadCrewTachoCcidTransport transport;
		public final byte[] atr;
		public final List<Step> steps = new ArrayList<>();

		private OpenCard(RoadCrewTachoCcidTransport transport, byte[] atr) {
			this.transport = transport;
			this.atr = atr;
		}

		@Override
		public void close() {
			try {
				transport.powerOff();
			} catch (IOException ignored) {
				// Best effort - the connection is being closed either way.
			}
			transport.close();
		}
	}

	/**
	 * Powers the card on and leaves the connection open for further calls
	 * (probing, and eventually a write) instead of closing it the way
	 * {@link #readSummary} does. The caller must close the result.
	 */
	@NonNull
	public static OpenCard open(@NonNull UsbDevice device, @NonNull UsbDeviceConnection connection) throws IOException {
		RoadCrewTachoCcidTransport transport = RoadCrewTachoCcidTransport.open(device, connection);
		RoadCrewTachoCcidTransport.Response powerOn = transport.powerOn();
		if (powerOn.cardAbsent()) {
			transport.close();
			throw new IOException("no card is seated in the reader");
		}
		if (powerOn.commandFailed()) {
			transport.close();
			throw new IOException("power-on failed: reader error 0x" + Integer.toHexString(powerOn.error & 0xFF));
		}
		OpenCard card = new OpenCard(transport, powerOn.data);
		card.steps.add(describeFeatures(transport.features()));
		negotiateParametersIfNeeded(transport, card.steps, card.atr);
		return card;
	}

	/**
	 * The ATR we got back today (18.09) needed something beyond bare power-on:
	 * every SELECT came back ICC_MUTE (0xFE - the card never answered, not a
	 * card-level refusal). If the reader does not say it negotiates T=0/T=1
	 * timing itself (dwFeatures bit 0x40), it needs to be told explicitly -
	 * and told the protocol this card's own ATR actually offers first (T=0
	 * here; T=1 is offered too, but second, and forcing T=1 by PPS still came
	 * back ICC_MUTE). Sends nothing to the card's file system; SetParameters
	 * only configures the reader and negotiates the electrical/timing
	 * protocol, which every card must accept to be read at all.
	 */
	private static void negotiateParametersIfNeeded(RoadCrewTachoCcidTransport transport, List<Step> steps,
			byte[] atr) throws IOException {
		AtrInterfaceBytes ib = AtrInterfaceBytes.parse(atr);
		if (ib.firstProtocol != 0) {
			throw new IOException("This diagnostic currently supports T=0 only");
		}
		if ((transport.features() & 0x40) != 0) {
			steps.add(new Step("SetParameters", true, "skipped: reader negotiates this itself"));
			transport.setActiveProtocol(ib.firstProtocol);
			return;
		}
		if (ib.firstProtocol == 0) {
			byte fiDi = ib.ta1 != null ? ib.ta1 : 0x11;
			byte waitingInteger = ib.tc2 != null ? ib.tc2 : 0x0A;
			RoadCrewTachoCcidTransport.Response response = transport.setParametersT0(fiDi, waitingInteger);
			if (response.commandFailed()) {
				throw new IOException("T=0 parameter negotiation failed");
			}
			steps.add(statusOnlyStepFromCcid(
					String.format("SetParameters (T=0, Fi/Di=%02X, WI=%02X)", fiDi, waitingInteger), response));
		} else {
			byte ifsc = ib.ta3 != null ? ib.ta3 : (byte) 0xFE;
			byte bwiCwi = ib.tb3 != null ? ib.tb3 : (byte) 0x4D;
			RoadCrewTachoCcidTransport.Response response = transport.setParametersT1(ifsc, bwiCwi);
			steps.add(statusOnlyStepFromCcid(
					String.format("SetParameters (T=1, IFSC=%02X, BWI/CWI=%02X)", ifsc, bwiCwi), response));
		}
		transport.setActiveProtocol(ib.firstProtocol);
	}

	/**
	 * The handful of ATR interface bytes this class needs, walked out by hand
	 * (ISO 7816-3 s8.2): which protocol the card offers first (the TD chain's
	 * first T value; implicit T=0 if no TD is present at all), and the T=0/T=1
	 * timing bytes if the card bothered to specify them, so SetParameters can
	 * use the card's own numbers instead of a generic default.
	 */
	private static final class AtrInterfaceBytes {
		int firstProtocol;
		Byte ta1;
		Byte tc2;
		Byte ta3;
		Byte tb3;

		static AtrInterfaceBytes parse(byte[] atr) {
			AtrInterfaceBytes result = new AtrInterfaceBytes();
			if (atr.length < 2) {
				return result;
			}
			int i = 2; // past TS, T0
			int y = (atr[1] >> 4) & 0xF;
			int level = 1;
			boolean firstProtocolSet = false;
			while (i < atr.length) {
				Byte ta = null, tb = null, tc = null;
				if ((y & 0x1) != 0 && i < atr.length) ta = atr[i++];
				if ((y & 0x2) != 0 && i < atr.length) tb = atr[i++];
				if ((y & 0x4) != 0 && i < atr.length) tc = atr[i++];
				if (level == 1) result.ta1 = ta;
				if (level == 2) result.tc2 = tc;
				if (level == 3) { result.ta3 = ta; result.tb3 = tb; }
				if ((y & 0x8) != 0 && i < atr.length) {
					byte td = atr[i++];
					if (!firstProtocolSet) {
						result.firstProtocol = td & 0x0F;
						firstProtocolSet = true;
					}
					y = (td >> 4) & 0xF;
					level++;
				} else {
					break;
				}
			}
			if (!firstProtocolSet) {
				result.firstProtocol = 0; // no TD at all: T=0 is the implicit default.
			}
			return result;
		}
	}

	private static Step statusOnlyStepFromCcid(String label, RoadCrewTachoCcidTransport.Response response) {
		if (response.commandFailed()) {
			return new Step(label, false, "reader error 0x" + Integer.toHexString(response.error & 0xFF));
		}
		return new Step(label, true, "accepted");
	}

	/**
	 * Selects each file id in the requested range and attempts a bounded read
	 * for successful selections. This diagnostic never writes to the card.
	 */
	@NonNull
	public static List<byte[]> probeFileIds(@NonNull OpenCard card, byte[] dfAid, int firstFid, int lastFidInclusive)
			throws IOException {
		List<byte[]> found = new ArrayList<>();
		// 18.09: select by AID, not by a guessed DF file id - see AID_TACHOGRAPH_G1/G2.
		selectByAid(card.transport, card.steps, "SELECT DF by AID (" + RoadCrewTachoCardReader.toHex(dfAid) + ")", dfAid);
		for (int fid = firstFid; fid <= lastFidInclusive; fid++) {
			byte[] candidate = {(byte) ((fid >> 8) & 0xFF), (byte) (fid & 0xFF)};
			RoadCrewTachoCcidTransport.Response response = card.transport.transmit(
					new byte[]{0x00, (byte) 0xA4, 0x02, 0x0C, 0x02, candidate[0], candidate[1]});
			Step step = statusOnlyStep("SELECT EF " + RoadCrewTachoCardReader.toHex(candidate), response);
			if (step.ok) {
				card.steps.add(step);
				found.add(candidate);
				// Found - also record how many bytes are actually there before moving on,
				// still nothing but reads.
				card.steps.add(runApdu(card.transport,
						"  READ BINARY " + RoadCrewTachoCardReader.toHex(candidate),
						new byte[]{0x00, (byte) 0xB0, 0x00, 0x00, (byte) READ_BINARY_MAX_LENGTH}));
			}
		}
		return found;
	}

	private static Step statusOnlyStep(String label, RoadCrewTachoCcidTransport.Response response) {
		if (response.commandFailed()) {
			return new Step(label, false, "reader error 0x" + Integer.toHexString(response.error & 0xFF));
		}
		if (response.data.length < 2) {
			return new Step(label, false, "reply too short");
		}
		int sw1 = response.data[response.data.length - 2] & 0xFF;
		int sw2 = response.data[response.data.length - 1] & 0xFF;
		return new Step(label, sw1 == 0x90 && sw2 == 0x00, String.format("SW=%02X%02X", sw1, sw2));
	}

	/**
	 * Powers the card on and attempts a few harmless, well-known reads. Every
	 * step is recorded whether it succeeds or not, so a wrong guess about the
	 * card's generation shows up as one failed step, not a thrown exception.
	 */
	@NonNull
	public static CardSummary readSummary(@NonNull UsbDevice device, @NonNull UsbDeviceConnection connection)
			throws IOException {
		RoadCrewTachoCcidTransport transport = RoadCrewTachoCcidTransport.open(device, connection);
		try {
			RoadCrewTachoCcidTransport.Response powerOn = transport.powerOn();
			if (powerOn.cardAbsent()) {
				throw new IOException("no card is seated in the reader");
			}
			if (powerOn.commandFailed()) {
				throw new IOException("power-on failed: reader error 0x" + Integer.toHexString(powerOn.error & 0xFF));
			}
			CardSummary summary = new CardSummary(powerOn.data);
			summary.steps.add(describeFeatures(transport.features()));
			negotiateParametersIfNeeded(transport, summary.steps, summary.atr);

			// 18.09: SELECT by AID (Gen1) is the confirmed-working step - SW=9000
			// against Galin's test card. Gen2's AID failing here (6A82, not found)
			// is expected and not a bug: this card is a Generation 1 card.
			long date = RoadCrewTachoDownloadDate.read(command -> checkedExchange(transport, command));
			summary.steps.add(new Step("LastCardDownload (G1 EF050E)", true, describeDate(date)));

			transport.powerOff();
			return summary;
		} finally {
			transport.close();
		}
	}

	private static void selectByAid(RoadCrewTachoCcidTransport transport, List<Step> steps,
			String label, byte[] aid) throws IOException {
		// ISO 7816-4 SELECT FILE, "select by name" (P1=0x04): the standard way to
		// pick an application by its AID rather than guessing a file id.
		byte[] apdu = new byte[5 + aid.length];
		apdu[0] = 0x00;
		apdu[1] = (byte) 0xA4;
		apdu[2] = 0x04;
		apdu[3] = 0x0C;
		apdu[4] = (byte) aid.length;
		System.arraycopy(aid, 0, apdu, 5, aid.length);
		steps.add(runApdu(transport, label, apdu));
	}

	private static void readBinaryBySfi(RoadCrewTachoCcidTransport transport, List<Step> steps,
			String label, byte sfi) throws IOException {
		// ISO 7816-4 READ BINARY, short EF identifier form: P1 = 1sssssss (bit 7
		// set, bits 0-4 the SFI), P2 = offset low byte (0 here). No SELECT EF
		// needed first - the file is addressed directly in this one command.
		byte[] apdu = {0x00, (byte) 0xB0, (byte) (0x80 | (sfi & 0x1F)), 0x00, (byte) READ_BINARY_MAX_LENGTH};
		steps.add(runApdu(transport, label, apdu));
	}

	private static void selectChildEf(RoadCrewTachoCcidTransport transport, List<Step> steps,
			String label, byte[] fid) throws IOException {
		// Tachograph-specific SELECT: EC1360/2002 Appendix 2, TCS_321.
		byte[] apdu = {0x00, (byte) 0xA4, 0x02, 0x0C, 0x02, fid[0], fid[1]};
		steps.add(runApdu(transport, label, apdu));
	}

	private static void selectByFid(RoadCrewTachoCcidTransport transport, List<Step> steps,
			String label, byte[] fid) throws IOException {
		byte[] apdu = {0x00, (byte) 0xA4, 0x02, 0x0C, 0x02, fid[0], fid[1]};
		steps.add(runApdu(transport, label, apdu));
	}

	private static void readBinary(RoadCrewTachoCcidTransport transport, List<Step> steps, String label)
			throws IOException {
		// ISO 7816-4 READ BINARY from offset 0 of whatever file is currently selected.
		byte[] apdu = {0x00, (byte) 0xB0, 0x00, 0x00, (byte) READ_BINARY_MAX_LENGTH};
		steps.add(runApdu(transport, label, apdu));
	}

	private static Step runApdu(RoadCrewTachoCcidTransport transport, String label, byte[] apdu) throws IOException {
		RoadCrewTachoCcidTransport.Response response = transport.transmit(apdu);
		if (response.commandFailed()) {
			return new Step(label, false, "reader error 0x" + Integer.toHexString(response.error & 0xFF));
		}
		if (response.data.length < 2) {
			return new Step(label, false, "reply too short to carry a status word (" + response.data.length + " bytes)");
		}
		int sw1 = response.data[response.data.length - 2] & 0xFF;
		int sw2 = response.data[response.data.length - 1] & 0xFF;
		boolean ok = sw1 == 0x90 && sw2 == 0x00;
		String hex = toHex(response.data);
		return new Step(label, ok, String.format("SW=%02X%02X, %d byte(s): %s", sw1, sw2, response.data.length, hex));
	}

	/**
	 * What the reader itself says it does automatically (USB-IF CCID spec
	 * 5.1, dwFeatures) - in particular whether it negotiates T=0/T=1
	 * parameters from the ATR on its own (bit 0x40) or expects the host to
	 * send PC_to_RDR_SetParameters. Read-only: this comes from the reader's
	 * own USB descriptor, never from the card.
	 */
	private static Step describeFeatures(int features) {
		boolean autoParams = (features & 0x40) != 0;
		boolean autoPps = (features & 0x80) != 0;
		int exchangeLevel = (features >> 16) & 0x7;
		String level;
		switch (exchangeLevel) {
			case 0: level = "character level"; break;
			case 1: level = "TPDU level"; break;
			case 2: level = "short APDU level"; break;
			case 3: level = "short and extended APDU level"; break;
			default: level = "unknown (" + exchangeLevel + ")"; break;
		}
		return new Step("reader dwFeatures", true,
				String.format("0x%08X: auto-parameters=%s, auto-PPS=%s, exchange=%s",
						features, autoParams, autoPps, level));
	}

	static long writeTestDownloadDate(OpenCard card, long epochSeconds,
			RoadCrewTachoDownloadDate.BeforeWrite observer) throws IOException {
		return RoadCrewTachoDownloadDate.writeTestDate(
				command -> checkedExchange(card.transport, command), epochSeconds, observer);
	}

	/** The card as a plain command channel, for the DDD download (ROADMAP 327). */
	static RoadCrewTachoDownloadDate.Channel channel(OpenCard card) {
		return command -> checkedExchange(card.transport, command);
	}

	static long readDownloadDate(OpenCard card) throws IOException {
		return RoadCrewTachoDownloadDate.read(command -> checkedExchange(card.transport, command));
	}

	private static byte[] checkedExchange(RoadCrewTachoCcidTransport transport, byte[] command) throws IOException {
		RoadCrewTachoCcidTransport.Response response = transport.transmit(command);
		if (response.commandFailed() || response.cardAbsent()) {
			throw new IOException("CCID failed: " + Integer.toHexString(response.error & 0xFF));
		}
		return response.data;
	}

	static String describeDate(long epochSeconds) {
		java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", java.util.Locale.ROOT);
		format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
		return format.format(new java.util.Date(epochSeconds * 1000L)) + " (" + epochSeconds + ")";
	}

	static String toHex(byte[] bytes) {
		StringBuilder builder = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			builder.append(String.format("%02X", b));
		}
		return builder.toString();
	}
}
