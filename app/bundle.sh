#!/bin/sh
# Baut das Play-Store-Bundle (build/klingel.aab) ohne Gradle, targetSdk 36 (Play-Pflicht 2026), signiert mit dem
# Upload-Schlüssel upload.jks (Passwort in upload.pass, nur für Google Play; Google signiert die App selbst neu).
#   VERSION=4 sh bundle.sh
set -e
cd "$(dirname "$0")"
SDK=${SDK:-/tmp/android-sdk}
BT=${BT:-$SDK/bt36/android-16}
BT35=${BT35:-$SDK/android-15}          # d8 aus build-tools 35 (Java-Teil, gleich)
JAR=${JAR:-$SDK/p36/android-36/android.jar}
BUNDLETOOL=${BUNDLETOOL:-$SDK/bundletool.jar}
VERSION=${VERSION:-1}
rm -rf build/aab && mkdir -p build/aab/gen build/aab/classes build/aab/dex build/aab/mod/manifest build/aab/mod/dex
"$BT/aapt2" compile --dir res -o build/aab/res.zip
"$BT/aapt2" link --proto-format -I "$JAR" --manifest AndroidManifest.xml --min-sdk-version 29 --target-sdk-version 36 \
  --version-code "$VERSION" --version-name "${VNAME:-1.$VERSION}" --java build/aab/gen -o build/aab/proto.apk build/aab/res.zip
javac -source 11 -target 11 -encoding UTF-8 -nowarn -classpath "$JAR" -d build/aab/classes $(find src build/aab/gen -name '*.java') 2>&1 | grep -v "^warning\|bootstrap\|^Note:\|^1 warning" || true
[ -n "$(find build/aab/classes -name '*.class')" ] || { echo "javac fehlgeschlagen"; exit 1; }
java -cp "$BT35/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 29 --lib "$JAR" --output build/aab/dex $(find build/aab/classes -name '*.class')
( cd build/aab/mod && unzip -qo ../proto.apk && mv AndroidManifest.xml manifest/ && cp ../dex/classes.dex dex/ && zip -qr ../base.zip . )
rm -f build/klingel.aab
java -jar "$BUNDLETOOL" build-bundle --modules=build/aab/base.zip --output=build/klingel.aab
if [ ! -f upload.jks ]; then
  head -c 24 /dev/urandom | base64 | tr -d '/+=' > upload.pass && chmod 600 upload.pass
  keytool -genkeypair -keystore upload.jks -storepass "$(cat upload.pass)" -keypass "$(cat upload.pass)" -alias upload \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ESPHomeRemote Tuerklingel Upload" >/dev/null 2>&1
  chmod 600 upload.jks
fi
jarsigner -keystore upload.jks -storepass "$(cat upload.pass)" -sigalg SHA256withRSA -digestalg SHA-256 build/klingel.aab upload >/dev/null
jarsigner -verify build/klingel.aab | tail -1
ls -la build/klingel.aab
