#!/usr/bin/env bash
# Prints a direct phone download link for the signed release APK of the latest
# Android-relevant commit on origin/master (com.nononsenseapps.feeder.debug, signed with
# shareddebug.keystore, so it installs over the existing fork app).
#
#   scripts/give-me-apk.sh            reuse a published APK if one exists, else build + publish
#   scripts/give-me-apk.sh --build    always build locally and publish
#
# 1. Looks for a GitHub Release asset (Codemagic or an earlier run of this script) whose name
#    contains the commit's short SHA, and prints its URL. Takes seconds.
# 2. Otherwise builds the same APK as codemagic.yaml in a separate worktree (never touches the
#    checkout you run it from), publishes it as release apk-<sha>, marked Latest, and prints the URL.
#
# Needs: gh logged in with write access to ashishpatel1323/Feeder; for builds, JDK 17+ and the
# Android SDK (ANDROID_HOME / ANDROID_SDK_ROOT, or sdk.dir in this checkout's local.properties).
# Never targets upstream spacecowboy/Feeder.
set -euo pipefail

REPO=ashishpatel1323/Feeder
FORCE_BUILD=false
[ "${1:-}" = "--build" ] && FORCE_BUILD=true

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
git fetch -q origin master

# Web-only commits don't change the APK (Codemagic skips them too).
SHA=$(git log -1 --format=%H origin/master -- . ':(exclude)web' ':(exclude).github/workflows/web.yml')
SHORT=${SHA::8}
echo "master APK commit: $SHORT $(git log -1 --format=%s "$SHA")" >&2

if ! $FORCE_BUILD; then
  url=$(gh api "repos/$REPO/releases?per_page=15" \
    --jq "[.[].assets[] | select(.name | test(\"$SHORT\")) | .browser_download_url][0] // empty")
  if [ -n "$url" ]; then
    echo "$url"
    exit 0
  fi
  echo "No published APK for $SHORT yet; building locally." >&2
fi

BUILD_DIR=${APK_BUILD_DIR:-"$ROOT/../Feeder-apk-build"}
if [ -d "$BUILD_DIR/.git" ] || [ -f "$BUILD_DIR/.git" ]; then
  git -C "$BUILD_DIR" checkout -q --detach --force "$SHA"
else
  git worktree add -q --detach "$BUILD_DIR" "$SHA"
fi
cd "$BUILD_DIR"
git submodule update -q --init --recursive 2>/dev/null || true

if [ ! -f local.properties ]; then
  sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
  [ -z "$sdk" ] && [ -f "$ROOT/local.properties" ] && sdk=$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties")
  [ -z "$sdk" ] && [ -d "$HOME/Library/Android/sdk" ] && sdk="$HOME/Library/Android/sdk"
  [ -n "$sdk" ] && echo "sdk.dir=$sdk" > local.properties
fi

./gradlew -q assembleFdroidRelease \
  -x lintVitalFdroidRelease \
  -PSTORE_FILE="$PWD/shareddebug.keystore" \
  -PSTORE_PASSWORD=android \
  -PKEY_ALIAS=AndroidDebugKey \
  -PKEY_PASSWORD=android \
  -PAPP_ID_SUFFIX=.debug \
  -PFAST_R8=true \
  -PABI_FILTERS=arm64-v8a >&2

NAME="feeder-fork-local-$SHORT.apk"
mkdir -p dist
cp app/build/outputs/apk/fdroid/release/app-fdroid-release.apk "dist/$NAME"
TAG="apk-$SHORT"

if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
  gh release upload "$TAG" "dist/$NAME" -R "$REPO" --clobber >&2
else
  gh release create "$TAG" "dist/$NAME" -R "$REPO" --target "$SHA" --latest \
    --title "Android build · local · $SHORT" \
    --notes "Built by scripts/give-me-apk.sh from $SHA on master. Installs over the previous fork build." >&2
fi
echo "https://github.com/$REPO/releases/download/$TAG/$NAME"
