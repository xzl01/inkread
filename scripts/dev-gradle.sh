#!/usr/bin/env bash
# 统一构建入口（Lead 与所有 teammate 都通过它跑 Gradle）：
#  1. 固定使用仓库内自带的 JDK 21（系统默认 JDK 27 与 Gradle 8.14 不兼容）；
#  2. 把所有工具链的 HOME / XDG / GRADLE 缓存目录都放在工作区内
#     （文件沙箱只允许写工作区，写 ~/.gradle、~/.android 会失败）；
#  3. 用 flock 串行化并行会话的构建，避免多个 agent 同时构建同一工程。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 普通开发者（clone 后没有 .tooling 目录）自动回退到标准 Gradle wrapper，
# 需要本机已安装 JDK 17~21 与 Android SDK。
if [[ ! -x "$ROOT/.tooling/gradle/bin/gradle" || ! -d "$ROOT/.tooling/jdk21" ]]; then
  exec "$ROOT/gradlew" "$@"
fi

export JAVA_HOME="$ROOT/.tooling/jdk21"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$ROOT/.tooling/gradle-home"
export ANDROID_USER_HOME="$ROOT/.tooling/android-home"
export XDG_CONFIG_HOME="$ROOT/.tooling/xdg-config"
export XDG_CACHE_HOME="$ROOT/.tooling/xdg-cache"
export XDG_DATA_HOME="$ROOT/.tooling/xdg-data"
mkdir -p "$GRADLE_USER_HOME" "$ANDROID_USER_HOME" "$XDG_CONFIG_HOME" "$XDG_CACHE_HOME" "$XDG_DATA_HOME"
exec flock /tmp/inkread-gradle.lock "$ROOT/.tooling/gradle/bin/gradle" "$@"
