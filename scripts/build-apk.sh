#!/usr/bin/env bash
# 一键构建 APK，并把产物复制到 dist/ 下（带版本号与 sha256）。
# 用法：bash scripts/build-apk.sh            # release（默认，可直接安装）
#       bash scripts/build-apk.sh debug      # debug
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VARIANT="release"
GRADLE_TASK=":app:assembleRelease"
if [[ "${1:-}" == "debug" ]]; then
  VARIANT="debug"
  GRADLE_TASK=":app:assembleDebug"
fi

bash "$ROOT/scripts/dev-gradle.sh" "$GRADLE_TASK"

SRC="$ROOT/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
if [[ ! -f "$SRC" ]]; then
  echo "构建产物不存在：$SRC" >&2
  exit 1
fi

VERSION="$(grep -oE 'versionName = "[^"]+"' "$ROOT/app/build.gradle.kts" | head -1 | cut -d'"' -f2)"
mkdir -p "$ROOT/dist"
OUT="$ROOT/dist/inkread-v${VERSION}-${VARIANT}.apk"
cp -f "$SRC" "$OUT"

echo
echo "APK: $OUT"
ls -lh "$OUT"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$OUT" | tee "$OUT.sha256"
fi
echo
echo "安装到设备（需开启 USB 调试）：adb install -r \"$OUT\""
