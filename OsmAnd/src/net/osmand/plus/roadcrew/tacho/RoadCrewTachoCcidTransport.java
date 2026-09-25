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

	private static final int MESSAGE_HEADER_LENGTH = 10;
	private static final int PC_TO_RDR_ICC_POWER_ON = 0x62;
	private static final int PC_TO_RDR_ICC_POWER_OFF = 0x63;
	private static final int PC_TO_RDR_XFR_BLOCK = 0x6F;
	private static final int PC_TO_RDR_SET_PARAMETERS = 0x61;
	private static final int RDR_TO_PC_DATA_BLOCK = 0x80;
	private static final int RDR_TO_PC_SLOT_STATUS = 0x81;

	private static final int TRANSFER_TIMEOUT_MILLIS = 5_000;
	private static final int MAX_RESPONSE_LENGTH = 4_096;
	/** Time extensions accepted for one command before it is treated as lost (each up to the transfer timeout). */
	private static final int MAX_TIME_EXTENSIONS = 60;

	private final UsbDeviceConnection connection;
	private final UsbInterface ccidInterface;
	private final UsbEndpoint bulkIn;
	private final UsbEndpoint bulkOut;
	private final int features;
	private final int exchangeLevel;
	private int activeProtocol = 0;
	private byte sequence;

	private static final int EXCHANGE_TPDU = 1;

	private RoadCrewTachoCcidTransport(UsbDeviceConnection connection, UsbInterface ccidInterface,
			UsbEndpoint bulkIn, UsbEndpoint bulkOut, int features) {
		this.connection = connection;
		this.ccidInterface = ccidInterface;
		this.bulkIn = bulkIn;
		this.bulkOut = bulkOut;
		this.features = features;
		this.exchangeLevel = (features >> 16) & 0x7;
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
	 * Claims the reader's CCID interface and finds its two bulk endpoints. The
	 * caller owns the UsbDeviceConnection (already permission-checked and
	 * opened) and must close it when done; this class only borrows it.
	 */
	static RoadCrewTachoCcidTransport open(@NonNull UsbDevice device, @NonNull UsbDeviceConnection connection)
			throws IOException {
		UsbInterface ccidInterface = null;
		for (int i = 0; i < device.getInterfaceCount(); i++) {
			UsbInterface candidate = device.getInterface(i);
			// Class 11 (0x0B) is the USB-IF assigned class for smart-card readers (CCID).
			if (candidate.getInterfaceClass() == UsbConstants.USB_CLASS_CSCID) {
				ccidInterface = candidate;
				break;
			}
		}
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
		int features = readFeatures(connection.getRawDescriptors(), ccidInterface.getId());
		return new RoadCrewTachoCcidTransport(connection, ccidInterface, bulkIn, bulkOut, features);
	}

	/**
	 * Walks the raw USB configuration descriptor - interface descriptor
	 * (type 0x04) after interface descriptor - to find the CCID class-specific
	 * functional descriptor (type 0x21) that belongs to our interface, and
	 * reads dwFeatures from it (offset 40, per the USB-IF CCID spec section
	 * 5.1). Read-only introspection of the reader itself, no card involved.
	 * Returns 0 (nothing automatic) if the descriptor cannot be found, so the
	 * caller falls back to doing parameter negotiation itself.
	 */
	private static int readFeatures(byte[] raw, int wantInterfaceId) {
		if (raw == null) {
			return 0;
		}
		int i = 0;
		boolean inWantedInterface = false;
		while (i + 1 < raw.length) {
			int length = raw[i] & 0xFF;
			int type = raw[i + 1] & 0xFF;
			if (length < 2) {
				break;
			}
			if (type == 0x04 && i + 3 < raw.length) { // interface descriptor
				int interfaceId = raw[i + 2] & 0xFF;
				inWantedInterface = interfaceId == wantInterfaceId;
			} else if (type == 0x21 && inWantedInterface && length >= 44 && i + 43 < raw.length) {
				return (raw[i + 40] & 0xFF) | ((raw[i + 41] & 0xFF) << 8)
						| ((raw[i + 42] & 0xFF) << 16) | ((raw[i + 43] & 0xFF) << 24);
			}
			i += length;
		}
		return 0;
	}

	void close() {
		connection.releaseInterface(ccidInterface);
	}

	/**
	 * Which protocol (0 or 1) SetParameters was last sent for - set by
	 * RoadCrewTachoCardReader after it decides which one to use, so
	 * {@link #transmit} knows whether to T=1-wrap an APDU. The current T=0
	 * diagnostic passes commands through unchanged. Case-1 APDU adaptation
	 * for TPDU readers must be added before implementing file hashing.
	 */
	void setActiveProtocol(int protocol) {
		this.activeProtocol = protocol;
	}

	/** Powers the card up and returns its ATR (Answer To Reset) - the card's own self-description. */
	Response powerOn() throws IOException {
		// byte7 = bPowerSelect: 0x00 lets the reader pick the voltage automatically.
		return exchange(PC_TO_RDR_ICC_POWER_ON, new byte[0], (byte) 0x00, (byte) 0x00, (byte) 0x00);
	}

	void powerOff() throws IOException {
		exchange(PC_TO_RDR_ICC_POWER_OFF, new byte[0], (byte) 0x00, (byte) 0x00, (byte) 0x00);
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
		byte seq = sequence++;
		byte[] message = new byte[MESSAGE_HEADER_LENGTH + payload.length];
		message[0] = (byte) messageType;
		writeLengthLittleEndian(message, 1, payload.length);
		message[5] = 0x00; // bSlot: this reader only ever has one.
		message[6] = seq;
		message[7] = b7;
		message[8] = b8;
		message[9] = b9;
		System.arraycopy(payload, 0, message, MESSAGE_HEADER_LENGTH, payload.length);

		int sent = connection.bulkTransfer(bulkOut, message, message.length, TRANSFER_TIMEOUT_MILLIS);
		if (sent != message.length) {
			throw new IOException("USB write to the reader was incomplete (" + sent + "/" + message.length + " bytes)");
		}

		for (int extensions = 0; ; extensions++) {
			byte[] buffer = new byte[MAX_RESPONSE_LENGTH];
			int received = connection.bulkTransfer(bulkIn, buffer, buffer.length, TRANSFER_TIMEOUT_MILLIS);
			if (received < MESSAGE_HEADER_LENGTH) {
				throw new IOException("the reader's reply was shorter than a CCID header (" + received + " bytes)");
			}
			int responseType = buffer[0] & 0xFF;
			int expectedType = messageType == PC_TO_RDR_SET_PARAMETERS ? 0x82
					: messageType == PC_TO_RDR_ICC_POWER_OFF ? RDR_TO_PC_SLOT_STATUS : RDR_TO_PC_DATA_BLOCK;
			if (responseType != expectedType || buffer[5] != 0) {
				throw new IOException("Unexpected CCID reply type or slot");
			}
			int declaredLength = readLengthLittleEndian(buffer, 1);
			byte responseSeq = buffer[6];
			if (responseSeq != seq) {
				throw new IOException("the reader replied out of order (expected seq " + seq + ", got " + responseSeq + ")");
			}
			byte status = buffer[7];
			byte error = buffer[8];
			if (responseType == RDR_TO_PC_DATA_BLOCK && (status & 0xC0) == 0x80) {
				// bmCommandStatus 2, "time extension requested" (CCID 6.2.6): the
				// card needs longer - a signature takes it seconds - and the real
				// reply follows on the same sequence number. Waiting is the
				// protocol; giving up here would lose the signature (ROADMAP 327).
				if (extensions >= MAX_TIME_EXTENSIONS) {
					throw new IOException("the card kept asking for more time; no result can be trusted");
				}
				continue;
			}
			int available = received - MESSAGE_HEADER_LENGTH;
			if (declaredLength != available || declaredLength < 0) {
				throw new IOException("Incomplete CCID reply; no card result can be trusted");
			}
			byte[] data = new byte[declaredLength];
			System.arraycopy(buffer, MESSAGE_HEADER_LENGTH, data, 0, declaredLength);
			return new Response(responseType, status, error, data);
		}
	}

	private static void writeLengthLittleEndian(byte[] out, int offset, int value) {
		out[offset] = (byte) (value & 0xFF);
		out[offset + 1] = (byte) ((value >> 8) & 0xFF);
		out[offset + 2] = (byte) ((value >> 16) & 0xFF);
		out[offset + 3] = (byte) ((value >> 24) & 0xFF);
	}

	private static int readLengthLittleEndian(byte[] in, int offset) {
		return (in[offset] & 0xFF) | ((in[offset + 1] & 0xFF) << 8)
				| ((in[offset + 2] & 0xFF) << 16) | ((in[offset + 3] & 0xFF) << 24);
	}
}
