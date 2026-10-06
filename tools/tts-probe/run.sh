#!/usr/bin/env bash
# The phone's own voice reads phrases into WAV files - nothing is played,
# nothing shows on the screen (ROADMAP 374). A separate debug app,
# com.roadcrew.ttsprobe, instrumentation only: RoadCrew is not touched or
# restarted, the driver can keep driving.
#
#   tools/tts-probe/run.sh <phrases.txt> <output-dir>
#
# One phrase per line, UTF-8. Prints the engine and voice, then per phrase
# the first 12 hex digits of the audio's sha256 (equal = the same audio),
# the seconds of speech, and the phrase. DEVICE defaults to Galin's phone
# over Tailscale; the probe is uninstalled at the end unless KEEP=1.
# Git Bash on Windows, ANDROID_HOME set, build-tools 36.0.0, android-35.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
phrases="$1"
out="$2"
device="${DEVICE:-100.103.139.103:5555}"
sdk="$(cygpath -u "$ANDROID_HOME")"
tools="$sdk/build-tools/36.0.0"
android_jar="$(cygpath -w "$sdk/platforms/android-35/android.jar")"
build="$(mktemp -d)"
mkdir -p "$build/classes" "$out"

"$tools/aapt2.exe" link -o "$build/base.apk" -I "$android_jar" --manifest "$here/AndroidManifest.xml" \
	--min-sdk-version 24 --target-sdk-version 34
javac --release 11 -encoding UTF-8 -classpath "$android_jar" -d "$build/classes" \
	"$here/src/com/roadcrew/ttsprobe/Probe.java" 2> "$build/javac.log" || { cat "$build/javac.log"; exit 1; }
(
	cd "$build"
	java -cp "$(cygpath -w "$tools/lib/d8.jar")" com.android.tools.r8.D8 --min-api 24 --lib "$android_jar" \
		--output . $(find classes -name '*.class')
	jar -u -M -f base.apk classes.dex
	"$tools/zipalign.exe" -f -p 4 base.apk aligned.apk
	java -jar "$(cygpath -w "$tools/lib/apksigner.jar")" sign --ks "$(cygpath -w ~/.android/debug.keystore)" \
		--ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out probe.apk aligned.apk
)

adb connect "$device" > /dev/null || true
adb -s "$device" install -r "$build/probe.apk" > /dev/null
adb -s "$device" exec-in run-as com.roadcrew.ttsprobe sh -c 'rm -f files/*.wav; cat > files/texts.txt' < "$phrases"
adb -s "$device" shell am instrument -w com.roadcrew.ttsprobe/.Probe \
	| grep -E '^INSTRUMENTATION_RESULT: ([a-e]_|error)' | sed 's/^INSTRUMENTATION_RESULT: //'
count=$(grep -c . "$phrases")
for ((i = 0; i < count; i++)); do
	name=$(printf '%02d.wav' "$i")
	adb -s "$device" exec-out run-as com.roadcrew.ttsprobe cat "files/$name" > "$out/$name"
done
[ "${KEEP:-0}" = 1 ] || adb -s "$device" uninstall com.roadcrew.ttsprobe > /dev/null
node "$here/measure.js" "$phrases" "$out"
