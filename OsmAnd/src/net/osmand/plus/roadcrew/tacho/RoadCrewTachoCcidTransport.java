package net.osmand.plus.roadcrew.tacho;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * The USB CCID class protocol (USB Implementers Forum, "Specification for
 * Integrated Circuit(s) Cards Interface Devices", rev 1.1) - the same public
 * standard every plain smart-card reader speaks, the ACR39U among them
 * (confirmed bInterfaceClass=11 on 18.09). Knowing this protocol is what lets
 * RoadCrew talk to the reader directly: nothing here is specific to any one
 * reader or to the tachograph card's own data format - that is the next layer
 * up (RoadCrewTachoCardReader).
 *
 * This transport is not a read-only boundary: XfrBlock forwards the caller's
 * APDU, including writes. The caller owns operation policy and confirmation.
 */
final class RoadCrewTachoCcidTransport {

	private static final int PC_TO_RDR_ICC_POWER_OFF = 0x63;
	private static final int PC_TO_RDR_XFR_BLOCK = 0x6F;
	private static final int PC_TO_RDR_SET_PARAMETERS = 0x61;
	private static final int PC_TO_RDR_GET_SLOT_STATUS = 0x65;

	private final UsbDeviceConnection connection;
	private final UsbInterface ccidInterface;
	private final int features;
	private final int exchangeLevel;
	private int activeProtocol = 0;
	private final RoadCrewTachoCcidProtocol protocol;

	private static final int EXCHANGE_TPDU = 1;

	private RoadCrewTachoCcidTransport(UsbDeviceConnection connection, UsbInterface ccidInterface,
			UsbEndpoint bulkIn, UsbEndpoint bulkOut, RoadCrewTachoCcidProtocol.Capabilities caps) {
		this.connection = connection;
		this.ccidInterface = ccidInterface;
		this.features = caps.features;
		this.exchangeLevel = (caps.features >> 16) & 0x7;
		this.protocol = new RoadCrewTachoCcidProtocol(new RoadCrewTachoCcidProtocol.Io() {
			@Override public int write(byte[] bytes, int timeout) {
				return connection.bulkTransfer(bulkOut, bytes, bytes.length, timeout);
			}
			@Override public int read(byte[] bytes, int timeout) {
				return connection.bulkTransfer(bulkIn, bytes, bytes.length, timeout);
			}
			@Override public long now() { return android.os.SystemClock.elapsedRealtime(); }
			@Override public void diagnostic(String detail) { android.util.Log.i("RoadCrewTacho", detail); }
			@Override public void pause(int millis) throws IOException {
				try {
					Thread.sleep(millis);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("CCID_INTERRUPTED", e);
				}
			}
		}, caps);
	}

	/**
	 * dwFeatures from the reader's own CCID class descriptor (USB-IF CCID spec
	 * 5.1, offset 40 into the class descriptor) - what the reader says it does
	 * for us automatically, instead of guessing. Bit 6 (0x40) is "automatic
	 * parameter negotiation made by the CCID": if it is set the reader
	 * configures T=0/T=1 timing from the ATR itself and PC_to_RDR_SetParameters
	 * should not be sent; if it is clear, the host has to send it.
	 */
	int features() {
		return features;
	}

	/** The result of one CCID exchange: the payload, and the reader's own status/error bytes. */
	static final class Response {
		final int messageType;
		final byte status;
		final byte error;
		final byte[] data;

		Response(int messageType, byte status, byte error, byte[] data) {
			this.messageType = messageType;
			this.status = status;
			this.error = error;
			this.data = data;
		}

		/** Bits 6-7 of bStatus: 00 = the reader processed the command without error. */
		boolean commandFailed() {
			return (status & 0xC0) != 0x00;
		}

		/** Bits 0-1 of bStatus: whether a card is seated in the reader at all. */
		boolean cardAbsent() {
			return (status & 0x03) == 0x02;
		}
	}

	/**
	 * Whether this USB device is a smart-card reader: any maker, any model, as
	 * long as it speaks CCID - the same test Android's device filter applies.
	 */
	static boolean isCardReader(@NonNull UsbDevice device) {
		return cardReaderInterface(device) != null;
	}

	private static UsbInterface cardReaderInterface(@NonNull UsbDevice device) {
		for (int i = 0; i < device.getInterfaceCount(); i++) {
			UsbInterface candidate = device.getInterface(i);
			// Class 11 (0x0B) is the USB-IF assigned class for smart-card readers (CCID).
			if (candidate.getInterfaceClass() == UsbConstants.USB_CLASS_CSCID) {
				return candidate;
			}
		}
		return null;
	}

	/**
	 * Claims the reader's CCID interface and finds its two bulk endpoints. The
	 * caller owns the UsbDeviceConnection (already permission-checked and
	 * opened) and must close it when done; this class only borrows it.
	 */
	static RoadCrewTachoCcidTransport open(@NonNull UsbDevice device, @NonNull UsbDeviceConnection connection)
			throws IOException {
		UsbInterface ccidInterface = cardReaderInterface(device);
		if (ccidInterface == null) {
			throw new IOException("no CCID interface on this USB device");
		}
		if (!connection.claimInterface(ccidInterface, true)) {
			throw new IOException("could not claim the CCID interface (another app may be holding it)");
		}
		UsbEndpoint bulkIn = null;
		UsbEndpoint bulkOut = null;
		for (int i = 0; i < ccidInterface.getEndpointCount(); i++) {
			UsbEndpoint endpoint = ccidInterface.getEndpoint(i);
			if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
				continue;
			}
			if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
				bulkIn = endpoint;
			} else {
				bulkOut = endpoint;
			}
		}
		if (bulkIn == null || bulkOut == null) {
			connection.releaseInterface(ccidInterface);
			throw new IOException("the CCID interface is missing a bulk IN or OUT endpoint");
		}
		try {
			RoadCrewTachoCcidProtocol.Capabilities caps = RoadCrewTachoCcidProtocol.Capabilities.parse(
					connection.getRawDescriptors(), ccidInterface.getId());
			android.util.Log.i("RoadCrewTacho", "Reader " + device.getVendorId() + ":"
					+ device.getProductId() + " " + caps);
			int level = (caps.features >> 16) & 7;
			if (level != 1 && level != 2 && level != 4) {
				throw new IOException("CCID_UNSUPPORTED_EXCHANGE_LEVEL " + level);
			}
			return new RoadCrewTachoCcidTransport(connection, ccidInterface, bulkIn, bulkOut, caps);
		} catch (IOException e) {
			connection.releaseInterface(ccidInterface);
			throw e;
		}
	}

	void close() {
		connection.releaseInterface(ccidInterface);
	}

	/**
	 * Which protocol (0 or 1) SetParameters was last sent for - set by
	 * RoadCrewTachoCardReader after it decides which one to use, so
	 * {@link #transmit} knows whether to T=1-wrap an APDU. The current T=0
	 * path adapts case-1 APDUs for TPDU readers below.
	 */
	void setActiveProtocol(int protocol) {
		this.activeProtocol = protocol;
	}

	/** Powers the card up and returns its ATR (Answer To Reset) - the card's own self-description. */
	Response powerOn() throws IOException {
		RoadCrewTachoCcidProtocol.Frame frame = protocol.powerOn();
		return new Response(frame.type, frame.status, frame.error, frame.data);
	}

	void powerOff() throws IOException {
		exchange(PC_TO_RDR_ICC_POWER_OFF, new byte[0], (byte) 0x00, (byte) 0x00, (byte) 0x00);
	}

	/**
	 * PC_to_RDR_GetSlotStatus (CCID 6.1.10): bmICCStatus, bits 0-1 of bStatus -
	 * 0 a card is powered, 1 a card is seated but not powered, 2 no card. Asks
	 * the reader only; the card is not touched. bmCommandStatus may report a
	 * failure (no card to talk to) while bmICCStatus is still valid.
	 */
	int slotStatus() throws IOException {
		Response response = exchange(PC_TO_RDR_GET_SLOT_STATUS, new byte[0], (byte) 0x00, (byte) 0x00, (byte) 0x00);
		return response.status & 0x03;
	}

	/**
	 * PC_to_RDR_SetParameters for T=0 (bProtocolNum=0): the five-byte T=0
	 * parameter block (field layout from pcsc-lite's CCID driver,
	 * ifdhandler.c IFDHSetProtocolParameters) - Fi/Di, TCCKS, extra guard
	 * time, WaitingIntegerT0, clock stop. {@code fiDi} and {@code waitingInteger}
	 * should come from the card's own ATR (TA1, and TC2 if present) rather
	 * than pcsc-lite's generic defaults, when the ATR gives them - this card
	 * does (18.09).
	 */
	Response setParametersT0(byte fiDi, byte waitingInteger) throws IOException {
		byte[] params = {fiDi, 0x00, 0x00, waitingInteger, 0x00};
		return exchange(PC_TO_RDR_SET_PARAMETERS, params, (byte) 0x00, (byte) 0x00, (byte) 0x00);
	}

	/**
	 * PC_to_RDR_SetParameters for T=1 (bProtocolNum=1): the seven-byte T=1
	 * protocol parameter block from ISO 7816-3 / EMV (field layout from
	 * pcsc-lite's CCID driver). {@code ifsc} and {@code bwiCwi} should come
	 * from the card's own ATR (TA3, TB3) when it gives them, as this card
	 * does (18.09) - IFSC 0xFE, BWI/CWI 0x45 - rather than pcsc-lite's
	 * generic defaults (0xFE, 0x4D).
	 */
	Response setParametersT1(byte ifsc, byte bwiCwi) throws IOException {
		byte[] params = {0x11, 0x10, 0x00, bwiCwi, 0x00, ifsc, 0x00};
		// byte7 = bProtocolNum (1 = T=1), bytes 8-9 = RFU.
		return exchange(PC_TO_RDR_SET_PARAMETERS, params, (byte) 0x01, (byte) 0x00, (byte) 0x00);
	}

	/**
	 * Sends one ISO 7816-4 APDU to the card through the reader and returns the
	 * card's reply, status word included. This is the transport only - it does
	 * not know or care whether the APDU is a SELECT, a READ BINARY or anything
	 * else, and never assembles one itself.
	 *
	 * What actually goes on the wire depends on the reader's own exchange
	 * level (dwFeatures bits 16-18, read in {@link #open}): an APDU-level
	 * reader wraps the protocol itself, so the plain APDU goes straight out.
	 * A TPDU-level reader - what this ACR39U turned out to be (18.09; every
	 * plain APDU came back ICC_MUTE, the card never even answering) - does
	 * not: it expects an already-framed T=1 block, and hands back another
	 * block in reply, which is unwrapped here before the caller ever sees it.
	 */
	Response transmit(@NonNull byte[] apdu) throws IOException {
		boolean wrap = exchangeLevel == EXCHANGE_TPDU && activeProtocol == 1;
		byte[] wire = wrap ? wrapT1(apdu) : apdu;
		if (exchangeLevel == EXCHANGE_TPDU && activeProtocol == 0 && apdu.length == 4) {
			// A case-1 command (no data, no Le - PERFORM HASH OF FILE) is sent
			// over T=0 with P3 = 00 (ISO 7816-3 12.2.2); the reader passes
			// TPDUs through as they are.
			wire = java.util.Arrays.copyOf(apdu, 5);
		}
		// byte7 = bBWI (0 = use the reader's default wait time), bytes 8-9 = wLevelParameter
		// (0x0000 = the block is sent whole, not chained across multiple XfrBlocks).
		Response response = exchange(PC_TO_RDR_XFR_BLOCK, wire, (byte) 0x00, (byte) 0x00, (byte) 0x00);
		if (wrap && !response.commandFailed()) {
			response = new Response(response.messageType, response.status, response.error, unwrapT1(response.data));
		}
		return response;
	}

	/**
	 * One ISO 7816-3 T=1 I-block around an APDU: NAD, PCB, LEN, the APDU
	 * itself, then a one-byte LRC (XOR of every byte before it - the checksum
	 * this class's SetParameters call asked the reader for, TCCKS bit 0 clear).
	 * Single block only: every APDU this reader has been asked so far
	 * (SELECT, READ BINARY, UPDATE BINARY with at most 0xFF bytes) fits inside
	 * one block's usual 254-byte limit, so I-block chaining is not implemented.
	 */
	private static byte[] wrapT1(byte[] apdu) {
		byte nad = 0x00;
		byte pcb = 0x00; // I-block, N(S)=0, no more data.
		byte len = (byte) apdu.length;
		byte[] block = new byte[3 + apdu.length + 1];
		block[0] = nad;
		block[1] = pcb;
		block[2] = len;
		System.arraycopy(apdu, 0, block, 3, apdu.length);
		byte lrc = 0;
		for (int i = 0; i < block.length - 1; i++) {
			lrc ^= block[i];
		}
		block[block.length - 1] = lrc;
		return block;
	}

	/** The reverse of {@link #wrapT1}: strips NAD/PCB/LEN and the trailing LRC, keeping the APDU. */
	private static byte[] unwrapT1(byte[] block) throws IOException {
		if (block.length < 4) {
			throw new IOException("T=1 block from the reader is too short (" + block.length + " bytes)");
		}
		int len = block[2] & 0xFF;
		if (block.length != 3 + len + 1) {
			throw new IOException("T=1 block LEN (" + len + ") does not match what arrived (" + block.length + " bytes)");
		}
		byte lrc = 0;
		for (int i = 0; i < block.length - 1; i++) {
			lrc ^= block[i];
		}
		if (lrc != block[block.length - 1]) {
			throw new IOException("T=1 block failed its checksum - the reply was corrupted in transit");
		}
		byte[] apdu = new byte[len];
		System.arraycopy(block, 3, apdu, 0, len);
		return apdu;
	}

	private Response exchange(int messageType, byte[] payload, byte b7, byte b8, byte b9) throws IOException {
		RoadCrewTachoCcidProtocol.Frame frame = protocol.exchange(messageType, payload, b7, b8, b9);
		return new Response(frame.type, frame.status, frame.error, frame.data);
	}
}
