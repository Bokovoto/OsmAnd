package net.osmand.plus.roadcrew.tacho;

/**
 * Which file of the driver card the tachograph is sending, read from the DDD stream itself:
 * every block starts with its tag (file id, type) and length. A file's data and signature are
 * one part. A VU sends 16 Gen1 files and, for a Gen2 (version 2) card, 25 more - Galin's card,
 * 11.10.2026: 73 blocks, 41 parts, 123 199 bytes (ROADMAP 411). The total stays 41 until the
 * transfer ends, so the bar only moves forward; a smaller card jumps to the end.
 */
public final class RoadCrewTachoVuProgress implements RoadCrewTachoVuDownload.Progress {

	public static final int FILES_G1 = 16;
	public static final int FILES_G2 = 25;

	private final byte[] head = new byte[5];
	private int headFill;
	private int blockLength;
	private int blockLeft;
	private int fileId = -1;
	private boolean secondGeneration;
	private boolean signature;
	private int filesDone;
	private int bytes;
	private boolean finished;

	@Override
	public void received(int bytes, int parts) {
		this.bytes = bytes;
	}

	@Override
	public void data(byte[] data, int offset, int length) {
		int end = offset + length;
		int at = offset;
		while (at < end) {
			if (blockLeft > 0) {
				int take = Math.min(blockLeft, end - at);
				blockLeft -= take;
				at += take;
				continue;
			}
			head[headFill++] = data[at++];
			if (headFill < head.length) {
				continue;
			}
			headFill = 0;
			int nextFile = (head[0] & 255) << 8 | head[1] & 255;
			boolean nextSecond = (head[2] & 2) != 0;
			if (fileId >= 0 && (nextFile != fileId || nextSecond != secondGeneration)) {
				filesDone++;
			}
			fileId = nextFile;
			secondGeneration = nextSecond;
			signature = (head[2] & 1) != 0;
			blockLength = (head[3] & 255) << 8 | head[4] & 255;
			blockLeft = blockLength;
		}
	}

	/** The transfer has ended: whatever the card had is in. */
	public void finish() {
		finished = true;
	}

	/** The file now coming, -1 before the first block. */
	public int fileId() {
		return fileId;
	}

	public boolean secondGeneration() {
		return secondGeneration;
	}

	public int filesDone() {
		return finished ? filesTotal() : filesDone;
	}

	public int filesTotal() {
		return finished ? filesDone + (fileId >= 0 ? 1 : 0) : FILES_G1 + FILES_G2;
	}

	/** How much of the current file is in, 0-100; its signature counts as the end of it. */
	public int filePercent() {
		if (fileId < 0) {
			return 0;
		}
		if (signature || blockLength == 0) {
			return 100;
		}
		return (blockLength - blockLeft) * 100 / blockLength;
	}

	/** All files, 0-1000; 1000 only once the transfer has ended. */
	public int permille() {
		if (finished) {
			return 1000;
		}
		if (fileId < 0) {
			return 0;
		}
		return Math.min(999, (filesDone * 1000 + filePercent() * 10) / (FILES_G1 + FILES_G2));
	}

	public int bytes() {
		return bytes;
	}
}
