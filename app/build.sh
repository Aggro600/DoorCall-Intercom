#!/bin/sh
# Baut die Klingel-App (Kopie des Streamer-Builds) ohne Gradle (2026-10-07). Braucht: JDK 17 (apk add openjdk17-jdk gcompat libstdc++),
# Android build-tools 35 und platform-35 (android.jar). Pfad zu den Werkzeugen: $SDK (Standard s. unten).
#   sh build.sh   -> build/esphomeremote-klingel.apk
set -e
cd "$(dirname "$0")"
SDK=${SDK:-/tmp/android-sdk}
BT=${BT:-$SDK/android-15}            # build-tools 35 (CI: $ANDROID_HOME/build-tools/35.0.0)
JAR=${JAR:-$SDK/android-35/android.jar}   # CI: $ANDROID_HOME/platforms/android-35/android.jar
VERSION=${VERSION:-1}
rm -rf build && mkdir -p build/gen build/classes build/dex build/res
"$BT/aapt2" compile --dir res -o build/res/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml --min-sdk-version 29 --target-sdk-version 34 \
  --version-code "$VERSION" --version-name "1.$VERSION" --java build/gen -o build/ohne_dex.apk build/res/res.zip
javac -source 11 -target 11 -encoding UTF-8 -nowarn -classpath "$JAR" -d build/classes $(find src build/gen -name '*.java') 2>&1 | grep -v "^warning\|bootstrap\|^Note:\|^1 warning" || true
[ -n "$(find build/classes -name '*.class')" ] || { echo "javac fehlgeschlagen"; exit 1; }
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 29 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
python3 -c "import zipfile,sys; z=zipfile.ZipFile('build/ohne_dex.apk','a'); z.write('build/dex/classes.dex','classes.dex'); z.close()"
"$BT/zipalign" -f -p 4 build/ohne_dex.apk build/ausgerichtet.apk
[ -f keystore.jks ] || keytool -genkeypair -keystore keystore.jks -storepass esphomeremote -keypass esphomeremote \
  -alias app -keyalg RSA -keysize 2048 -validity 20000 -dname "CN=ESPHomeRemote Klingel" >/dev/null 2>&1
java -jar "$BT/lib/apksigner.jar" sign --ks keystore.jks --ks-pass pass:esphomeremote --key-pass pass:esphomeremote \
  --out build/esphomeremote-klingel.apk build/ausgerichtet.apk
java -jar "$BT/lib/apksigner.jar" verify build/esphomeremote-klingel.apk && ls -la build/esphomeremote-klingel.apk
