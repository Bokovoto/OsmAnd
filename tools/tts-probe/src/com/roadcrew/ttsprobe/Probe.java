package com.roadcrew.ttsprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Synthesizes RoadCrew's phrases to files with the phone's own engine and voice - nothing is played. */
public class Probe extends Instrumentation {

	static final String[] TEXTS = {
			"камера наблизо.",
			"Камера наблизо.",
			"ка̀мера наблизо.",
			"Ка̀мера наблизо.",
			"наблизо.",
			"ка̀мера.",
			"камера.",
			"ка̀мера по маршрута след 500 метра.",
			"камера по маршрута след 500 метра.",
			"След 500 метра: ка̀мера.",
			"ка̀мера напред след 500 метра.",
			"ка́мера наблизо.",
			"Внимание, ка̀мера наблизо.",
	};

	private volatile CountDownLatch current;
	private volatile String lastError = "";

	@Override
	public void onCreate(Bundle arguments) {
		super.onCreate(arguments);
		start();
	}

	@Override
	public void onStart() {
		Bundle result = new Bundle();
		try {
			run(result);
			finish(Activity.RESULT_OK, result);
		} catch (Throwable t) {
			result.putString("error", t.toString());
			finish(Activity.RESULT_CANCELED, result);
		}
	}

	private void run(Bundle result) throws Exception {
		Context context = getContext();
		final CountDownLatch init = new CountDownLatch(1);
		final int[] status = {-99};
		TextToSpeech tts = new TextToSpeech(context, new TextToSpeech.OnInitListener() {
			@Override
			public void onInit(int s) {
				status[0] = s;
				init.countDown();
			}
		});
		if (!init.await(30, TimeUnit.SECONDS)) {
			throw new IllegalStateException("engine did not start in 30 s");
		}
		result.putString("a_init", String.valueOf(status[0]));
		result.putString("b_engine", String.valueOf(tts.getDefaultEngine()));
		try {
			result.putString("c_engineVersion", context.getPackageManager()
					.getPackageInfo(tts.getDefaultEngine(), 0).versionName);
		} catch (Exception e) {
			result.putString("c_engineVersion", e.toString());
		}
		result.putString("d_setLanguage", String.valueOf(tts.setLanguage(new Locale("bg", "BG"))));
		Voice voice = tts.getVoice();
		result.putString("e_voice", voice == null ? "null" : voice.getName() + " " + voice.getLocale()
				+ " quality=" + voice.getQuality() + " network=" + voice.isNetworkConnectionRequired()
				+ " features=" + voice.getFeatures());
		tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
			@Override
			public void onStart(String utteranceId) {
			}

			@Override
			public void onDone(String utteranceId) {
				CountDownLatch latch = current;
				if (latch != null) {
					latch.countDown();
				}
			}

			@Override
			public void onError(String utteranceId) {
				lastError = utteranceId + " error";
				CountDownLatch latch = current;
				if (latch != null) {
					latch.countDown();
				}
			}

			@Override
			public void onError(String utteranceId, int errorCode) {
				lastError = utteranceId + " error " + errorCode;
				CountDownLatch latch = current;
				if (latch != null) {
					latch.countDown();
				}
			}
		});
		File dir = context.getFilesDir();
		File list = new File(dir, "texts.txt");
		String[] texts = list.exists() ? new String(Files.readAllBytes(list.toPath()), StandardCharsets.UTF_8).trim().split("\r?\n") : TEXTS;
		for (int i = 0; i < texts.length; i++) {
			File file = new File(dir, String.format(Locale.US, "%02d.wav", i));
			file.delete();
			lastError = "";
			current = new CountDownLatch(1);
			int queued = tts.synthesizeToFile(texts[i], new Bundle(), file, "u" + i);
			boolean done = current.await(30, TimeUnit.SECONDS);
			result.putString(String.format(Locale.US, "t%02d", i), texts[i] + " | queued=" + queued
					+ " done=" + done + " bytes=" + file.length() + " " + lastError);
		}
		tts.shutdown();
	}
}
