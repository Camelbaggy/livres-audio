#!/usr/bin/env bash
# Installe le SDK Android en ligne de commande (environnement cloud / CI sans Android Studio).
# Idempotent : ne retélécharge rien si déjà présent.
# Usage : scripts/install-android-sdk.sh   (puis : export ANDROID_HOME=… ; voir la fin du script)
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
CMDLINE_TOOLS_BUILD="${CMDLINE_TOOLS_BUILD:-16111833}"
PLATFORM="${ANDROID_PLATFORM:-platforms;android-37.0}"
BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-build-tools;37.0.0}"

mkdir -p "$ANDROID_HOME"
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/tools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
fi

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null || true
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" "platform-tools" "$PLATFORM" "$BUILD_TOOLS" >/dev/null

# Permet à Gradle de trouver le SDK sans variable d'environnement.
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
echo "sdk.dir=$ANDROID_HOME" > "$repo_root/local.properties"

echo "SDK Android installé dans $ANDROID_HOME"
echo "export ANDROID_HOME=$ANDROID_HOME"
