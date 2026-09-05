package net.osmand.router;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Records the location stream exactly as RoadCrew receives it, so a real drive
 * can be replayed offline (ROADMAP section 184).
 *
 * Deliberately not a GPX. A GPX keeps positions and loses the things the
 * pipeline actually depends on: the monotonic elapsed clock, the accuracy, the
 * speed and bearing the platform reported, the gaps between fixes, duplicates,
 * and the lifecycle around them. Replaying a GPX would prove less than it
 * appears to.
 *
 * What this makes possible is a laboratory experiment: one recording, one map,
 * two builds, and the only variable is the code. The fault of 5 September would
 * have reproduced identically from a file, because it lives below the point
 * where locations enter.
 *
 * PRIVACY. This is a complete trace of where a vehicle went - precisely what
 * the rest of the system is built never to keep. So it is written only while
 * the phone is in the validation programme, it is never uploaded anywhere, it
 * is bounded, and it is deleted when observation consent is withdrawn. It
 * exists for a fault being chased, not as a feature.
 */
public final class RoadCrewLocationRecorder {

	/** About four hours at one fix a second; a long drive still fits. */
	public static final int MAX_LINES = 20_000;

	private final File file;
	private BufferedWriter writer;
	private int lines;
	private boolean broken;

	public RoadCrewLocationRecorder(File file) {
		this.file = file;
	}

	public File getFile() {
		return file;
	}

	public synchronized int getLineCount() {
		return lines;
	}

	/**
	 * One location, as it arrived. Anything that cannot be written is dropped
	 * silently and for good: a diagnostic must never disturb the drive.
	 */
	public synchronized void fix(long fixSequence, long wallClockMillis, long elapsedRealtimeMillis,
			double latitude, double longitude, double accuracyMeters,
			double speedMetersPerSecond, double bearingDegrees, double altitudeMeters) {
		StringBuilder line = new StringBuilder(160);
		line.append("{\"t\":\"fix\",\"seq\":").append(fixSequence)
				.append(",\"wall\":").append(wallClockMillis)
				.append(",\"elapsed\":").append(elapsedRealtimeMillis)
				.append(",\"lat\":").append(round(latitude, 7))
				.append(",\"lon\":").append(round(longitude, 7))
				.append(",\"acc\":").append(round(accuracyMeters, 2))
				.append(",\"spd\":").append(round(speedMetersPerSecond, 3))
				.append(",\"brg\":").append(round(bearingDegrees, 2))
				.append(",\"alt\":").append(round(altitudeMeters, 1))
				.append('}');
		write(line.toString());
	}

	/**
	 * A lifecycle transition. Without these a replay would reproduce the fixes
	 * and none of the conditions around them - a course starting, navigation
	 * stopping, the roads being reloaded.
	 */
	public synchronized void event(long fixSequence, String name, String detail) {
		write("{\"t\":\"event\",\"seq\":" + fixSequence
				+ ",\"name\":\"" + escape(name) + "\""
				+ ",\"detail\":\"" + escape(detail == null ? "" : detail) + "\"}");
	}

	public synchronized void close() {
		if (writer == null) {
			return;
		}
		try {
			writer.close();
		} catch (IOException ignored) {
			// Nothing useful can be done, and nothing may be thrown from here.
		}
		writer = null;
	}

	private void write(String line) {
		if (broken || lines >= MAX_LINES) {
			return;
		}
		try {
			if (writer == null) {
				File parent = file.getParentFile();
				if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
					broken = true;
					return;
				}
				writer = new BufferedWriter(new OutputStreamWriter(
						new FileOutputStream(file, true), StandardCharsets.UTF_8));
			}
			writer.write(line);
			writer.write('\n');
			// Flushed per line so a drive that ends in a crash or a kill still
			// leaves everything up to that moment - which is when it matters.
			writer.flush();
			lines++;
		} catch (IOException e) {
			broken = true;
			writer = null;
		}
	}

	private static String round(double value, int decimals) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return "null";
		}
		double factor = Math.pow(10, decimals);
		double rounded = Math.round(value * factor) / factor;
		String text = String.valueOf(rounded);
		return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
	}

	private static String escape(String value) {
		StringBuilder escaped = new StringBuilder(value.length() + 8);
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character == '"' || character == '\\') {
				escaped.append('\\').append(character);
			} else if (character < 0x20) {
				escaped.append(' ');
			} else {
				escaped.append(character);
			}
		}
		return escaped.toString();
	}
}
