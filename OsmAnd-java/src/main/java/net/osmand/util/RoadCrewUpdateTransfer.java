package net.osmand.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.CancellationException;

/** One cancellable APK transfer. Never exposes a partial file as an installable APK. */
public final class RoadCrewUpdateTransfer {

	public interface Listener {
		void onProgress(long received, long total);
		void onVerifying();
	}

	private final Listener listener;
	private final String userAgent;
	private volatile boolean cancelled;
	private volatile HttpURLConnection connection;
	private long received;

	public RoadCrewUpdateTransfer(String userAgent, Listener listener) {
		this.userAgent = userAgent;
		this.listener = listener;
	}

	public void requestCancel() {
		cancelled = true;
	}

	/** Call off the UI thread: some HTTP implementations block in disconnect(). */
	public void disconnect() {
		HttpURLConnection current = connection;
		if (current != null) {
			current.disconnect();
		}
	}

	private void checkCancelled() {
		if (cancelled) {
			throw new CancellationException();
		}
	}

	public File download(String apiUrl, String browserUrl, File apk, long expectedSize,
			String sha256) throws Exception {
		File partial = new File(apk.getPath() + ".part");
		try {
			checkCancelled();
			if (!apiUrl.isEmpty()) {
				try {
					downloadUrl(apiUrl, true, partial, expectedSize, sha256);
				} catch (Exception error) {
					checkCancelled();
					// Only fall back before any bytes arrive. A broken transfer needs an explicit retry.
					if (received > 0 || browserUrl.isEmpty()) {
						throw error;
					}
					downloadUrl(browserUrl, false, partial, expectedSize, sha256);
				}
			} else if (!browserUrl.isEmpty()) {
				downloadUrl(browserUrl, false, partial, expectedSize, sha256);
			} else {
				throw new IOException("No APK download URL");
			}
			checkCancelled();
			if (apk.exists() && !apk.delete()) {
				throw new IOException("Could not replace cached APK");
			}
			if (!partial.renameTo(apk)) {
				throw new IOException("Could not finalize APK");
			}
			return apk;
		} finally {
			if (partial.exists()) {
				partial.delete();
			}
		}
	}

	private void downloadUrl(String url, boolean apiHeaders, File partial, long expectedSize,
			String sha256) throws Exception {
		checkCancelled();
		received = 0;
		listener.onProgress(0, expectedSize);
		HttpURLConnection current = (HttpURLConnection) new URL(url).openConnection();
		connection = current;
		try {
			checkCancelled();
			current.setConnectTimeout(10000);
			current.setReadTimeout(60000);
			current.setInstanceFollowRedirects(true);
			current.setRequestProperty("User-Agent", userAgent);
			current.setRequestProperty("Accept-Encoding", "identity");
			if (apiHeaders) {
				current.setRequestProperty("Accept", "application/octet-stream");
			}
			if (current.getResponseCode() != HttpURLConnection.HTTP_OK) {
				throw new IOException("APK HTTP status: " + current.getResponseCode());
			}
			String type = current.getContentType();
			if (type != null && (type.toLowerCase(Locale.ROOT).contains("text/")
					|| type.toLowerCase(Locale.ROOT).contains("json"))) {
				throw new IOException("Unexpected APK content type");
			}
			long contentLength = current.getContentLengthLong();
			if (expectedSize > 0 && contentLength > 0 && contentLength != expectedSize) {
				throw new IOException("APK response size mismatch");
			}
			long total = expectedSize > 0 ? expectedSize : contentLength;
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			long lastProgress = 0;
			try (InputStream input = current.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
				byte[] buffer = new byte[64 * 1024];
				int count;
				while ((count = input.read(buffer)) != -1) {
					checkCancelled();
					output.write(buffer, 0, count);
					digest.update(buffer, 0, count);
					received += count;
					if (total > 0 && received > total) {
						throw new IOException("APK exceeds expected size");
					}
					long now = System.nanoTime();
					if (now - lastProgress >= 200000000L) {
						listener.onProgress(received, total);
						lastProgress = now;
					}
				}
			}
			checkCancelled();
			listener.onProgress(received, total);
			listener.onVerifying();
			if (received < 1024 * 1024 || (total > 0 && received != total)) {
				throw new IOException("Incomplete APK");
			}
			if (sha256 != null && !sha256.isEmpty()) {
				StringBuilder actual = new StringBuilder();
				for (byte value : digest.digest()) {
					actual.append(String.format(Locale.ROOT, "%02x", value & 0xff));
				}
				if (!actual.toString().equalsIgnoreCase(sha256)) {
					throw new IOException("APK SHA-256 mismatch");
				}
			}
		} finally {
			current.disconnect();
			connection = null;
		}
	}
}
