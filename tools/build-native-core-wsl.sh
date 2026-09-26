#!/bin/bash
# Builds OsmAnd's legacy native core (libosmand.so + libc++_shared.so) for
# RoadCrew and copies it into android/OsmAnd/libs/<abi>/, where the Windows
# release build packages it (ROADMAP 328).
#
# Why: the core is built by OsmAnd's old-ndk-build.sh, which needs Linux; on
# Windows gradle's buildOsmAndCore only echoes "Not supported" and the APK
# ships without it. Without the core the app runs in safe mode - Java routing
# and rendering - and a truck route of ~800 km took 175-247 s instead of ~25 s.
#
# Run inside WSL (Ubuntu), from Windows:
#   wsl.exe -d Ubuntu -- bash /mnt/d/projets/truck-community-navigation/android/tools/build-native-core-wsl.sh
#   ... build-native-core-wsl.sh arm64-v8a          # one ABI only (test builds)
# The work tree lives in the WSL file system ($HOME/rc-native): building on
# /mnt/d is many times slower. First run: ~700 MB NDK, the externals' sources,
# about 20 min per ABI. libs/ is gitignored - the libraries are rebuilt, never
# committed.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_ROOT="$(cd "$HERE/.." && pwd)"
PROJECT_ROOT="$(cd "$ANDROID_ROOT/.." && pwd)"
WORK="${RC_NATIVE_WORK:-$HOME/rc-native}"
NDK_VERSION="r23c"
# The last core-legacy commit before the fork's upstream base (2026-07-30),
# so the JNI contract matches OsmAnd-java. Move it together with the base.
CORE_LEGACY_COMMIT="071503e280d77c2a959d3690845f5e56b2c7e4b3"
ABIS="${*:-arm64-v8a armeabi-v7a x86 x86_64}"
JOBS="${RC_NATIVE_JOBS:-3}"

mkdir -p "$WORK/bin"
NDK="$WORK/android-ndk-$NDK_VERSION"

# 1. Android NDK for Linux. Python extracts it because unzip may be missing,
#    keeping the executable bits and symlinks zipfile.extractall would drop.
if [ ! -x "$NDK/ndk-build" ]; then
	echo "== Downloading NDK $NDK_VERSION"
	curl -fsSL -o "$WORK/ndk.zip" "https://dl.google.com/android/repository/android-ndk-$NDK_VERSION-linux.zip"
	rm -rf "$NDK"
	python3 - "$WORK" <<'PY'
import os, shutil, stat, sys, zipfile
os.chdir(sys.argv[1])
with zipfile.ZipFile('ndk.zip') as z:
    for info in z.infolist():
        mode = (info.external_attr >> 16) & 0xFFFF
        path = info.filename
        if info.is_dir():
            os.makedirs(path, exist_ok=True)
            continue
        os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
        if stat.S_ISLNK(mode):
            if os.path.lexists(path):
                os.remove(path)
            os.symlink(z.read(info).decode(), path)
            continue
        with z.open(info) as src, open(path, 'wb') as dst:
            shutil.copyfileobj(src, dst)
        if mode & 0o777:
            os.chmod(path, mode & 0o777)
PY
	rm -f "$WORK/ndk.zip"
fi

# 2. Tools the externals' scripts call. Stand-ins when the system lacks them,
#    so no sudo is needed.
if ! command -v unzip >/dev/null; then
	cat > "$WORK/bin/unzip" <<'PY'
#!/usr/bin/env python3
# Minimal 'unzip [-q] [-o] archive -d dir' for the OsmAnd externals scripts.
import os, sys, zipfile
args = [a for a in sys.argv[1:] if a not in ('-q', '-qq', '-o')]
dest = '.'
if '-d' in args:
    i = args.index('-d'); dest = args[i + 1]; del args[i:i + 2]
os.makedirs(dest, exist_ok=True)
with zipfile.ZipFile(args[0]) as z:
    z.extractall(dest)
PY
	chmod +x "$WORK/bin/unzip"
fi
if ! command -v bzip2 >/dev/null; then
	cat > "$WORK/bin/bzip2" <<'PY'
#!/usr/bin/env python3
# Minimal 'bzip2 -d' (stdin -> stdout) so that 'tar -xjf' works.
import bz2, sys
if not any(a in ('-d', '--decompress') for a in sys.argv[1:]):
    sys.exit('only -d is supported')
d = bz2.BZ2Decompressor()
out = sys.stdout.buffer
while True:
    chunk = sys.stdin.buffer.read(1 << 20)
    if not chunk:
        break
    while chunk:
        out.write(d.decompress(chunk))
        if d.eof:
            chunk = d.unused_data
            d = bz2.BZ2Decompressor() if chunk else d
        else:
            chunk = b''
PY
	chmod +x "$WORK/bin/bzip2"
fi
if ! command -v python >/dev/null; then
	ln -sf "$(command -v python3)" "$WORK/bin/python"
fi
export PATH="$WORK/bin:$PATH"

# 3. Sources: core-legacy at the pinned commit; OsmAnd-build helpers and the
#    fork's jni makefiles from this project, with Unix line endings.
if [ ! -d "$WORK/core-legacy/.git" ]; then
	git clone -q --filter=blob:none https://github.com/osmandapp/OsmAnd-core-legacy.git "$WORK/core-legacy"
fi
if ! git -C "$WORK/core-legacy" cat-file -e "$CORE_LEGACY_COMMIT^{commit}" 2>/dev/null; then
	git -C "$WORK/core-legacy" fetch -q origin
fi
git -C "$WORK/core-legacy" checkout -q "$CORE_LEGACY_COMMIT"
rm -rf "$WORK/build" "$WORK/android/OsmAnd/jni"
cp -r "$PROJECT_ROOT/build" "$WORK/build"
mkdir -p "$WORK/android/OsmAnd"
cp -r "$ANDROID_ROOT/OsmAnd/jni" "$WORK/android/OsmAnd/jni"
cp "$ANDROID_ROOT/OsmAnd/old-ndk-build.sh" "$WORK/android/OsmAnd/"
find "$WORK/android" "$WORK/build" -type f \
	\( -name '*.sh' -o -name '*.mk' -o -name '*.cmake' -o -name 'CMakeLists.txt' \) \
	-exec sed -i 's/\r$//' {} +

# 4. Externals (downloaded and patched once, then kept) and the core itself.
export ANDROID_NDK_ROOT="$NDK" ANDROID_NDK="$NDK" ANDROID_SDK_ROOT="$WORK" BUILD_ONLY_OLD_LIB=1
echo "== Configuring externals"
"$WORK/core-legacy/externals/configure.sh"
echo "== Building for: $ABIS"
(cd "$WORK/android/OsmAnd" && "$NDK/ndk-build" -j"$JOBS" APP_ABI="$ABIS")

# 5. Copy out, checking every library exports the JNI entry points.
NM="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm"
for abi in $ABIS; do
	src="$WORK/android/OsmAnd/libs/$abi"
	dst="$ANDROID_ROOT/OsmAnd/libs/$abi"
	for lib in libosmand.so libc++_shared.so; do
		[ -f "$src/$lib" ] || { echo "MISSING $abi/$lib" >&2; exit 1; }
	done
	jni=$("$NM" -D --defined-only "$src/libosmand.so" | grep -c 'Java_net_osmand_NativeLibrary_' || true)
	if [ "$jni" -lt 15 ]; then
		echo "$abi/libosmand.so exports only $jni JNI entry points" >&2
		exit 1
	fi
	mkdir -p "$dst"
	cp "$src/libosmand.so" "$src/libc++_shared.so" "$dst/"
	echo "$abi: libosmand.so $(stat -c %s "$src/libosmand.so") bytes, $jni JNI entry points"
done
echo "Native core ready in $ANDROID_ROOT/OsmAnd/libs - run the release build."
