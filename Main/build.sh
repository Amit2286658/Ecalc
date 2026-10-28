#!/bin/bash
# ============================================================
#  build.sh — simplified build runner for the Ecalc project
#
#  Usage:  ./build.sh [command]
#
#  Commands:
#    (no arg) / debug / build  build a debug APK with live log
#    release                   build a release APK with live log
#    clean                     delete build outputs
#    stop                      kill any running Gradle daemons/builds
#    status                    is a build running? last APK built?
#    log                       follow the latest build log live
#    apk                       show path of newest APK
#    env                       print the toolchain env this script uses
#    help                      show this list
#
#  This script automatically handles:
#   - Java/JDK setup (OpenJDK 21)
#   - The sandbox memory-permission shim needed to run the JVM
#   - Android SDK paths (aapt2 wrapper included)
# ============================================================

set -u
SELF="$(readlink -f "$0")"
ROOT="$(cd "$(dirname "$SELF")" && pwd)"
LOG_DIR="$ROOT/.build-logs"
LOG_FILE="$LOG_DIR/last.log"

# ---------- toolchain env ----------
if [ -d /usr/lib/jvm/java-21-openjdk ]; then
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
fi
if [ -d /opt/android_sdk ]; then
  export ANDROID_HOME=/opt/android_sdk
  export ANDROID_SDK_ROOT=/opt/android_sdk
fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}${ANDROID_HOME:+$ANDROID_HOME/cmdline-tools/latest/bin:}/opt/aapt2:$PATH"

# Memory-permission shim: rebuilds itself if deleted (e.g. /tmp cleanup)
if [ ! -f /tmp/wxshim.so ]; then
  cat > /tmp/wxshim.c <<'EOF'
#define _GNU_SOURCE
#include <stdio.h>
#include <string.h>
#include <dlfcn.h>
#include <sys/mman.h>
static int (*real_mprotect)(void *, size_t, int);
__attribute__((constructor)) static void init(void) { real_mprotect = dlsym(RTLD_NEXT, "mprotect"); }
int mprotect(void *addr, size_t len, int prot) {
  if ((prot & PROT_EXEC) && ((prot & (PROT_READ | PROT_WRITE)) != 0)) {
    void *tmp = mmap(NULL, len, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (tmp == MAP_FAILED) return -1;
    memcpy(tmp, addr, len);
    void *keep = mmap(tmp, len, PROT_READ, MAP_FIXED | MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    (void)keep;
    if (mmap(addr, len, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_FIXED | MAP_PRIVATE | MAP_ANONYMOUS, -1, 0) == MAP_FAILED)
      return -1;
    memcpy(addr, tmp, len);
    munmap(tmp, len);
    return 0;
  }
  return real_mprotect(addr, len, prot);
}
EOF
  cc /tmp/wxshim.c -o /tmp/wxshim.so -shared -fPIC -ldl 2>/dev/null
fi
if [ -f /tmp/wxshim.so ] && [ -f /tmp/execshim.so ]; then
  export LD_PRELOAD=/tmp/wxshim.so:/tmp/execshim.so
elif [ -f /tmp/wxshim.so ]; then
  export LD_PRELOAD=/tmp/wxshim.so
fi

# ---------- helpers ----------
gradle_running() {
  pgrep -f "org.gradle" >/dev/null 2>&1
}

newest_apk() {
  find "$ROOT/app/build/outputs/apk" -name "*.apk" -exec ls -t {} + 2>/dev/null | head -1
}

do_build() { # $1 = task, $2 = label
  if gradle_running; then
    echo "⚠️  A Gradle build is already running (./build.sh stop to kill it first)."
    exit 1
  fi
  mkdir -p "$LOG_DIR"
  echo "🏗  Building ($2)... log: $LOG_FILE"
  cd "$ROOT"
  chmod +x ./gradlew 2>/dev/null || true
  ./gradlew --no-daemon $1 --console=plain 2>&1 | tee "$LOG_FILE"
  STATUS=${PIPESTATUS[0]}
  echo
  grep -E "BUILD (SUCCESSFUL|FAILED)" "$LOG_FILE" | tail -1
  if [ $STATUS -eq 0 ]; then
    APK=$(newest_apk)
    if [ -n "$APK" ]; then
      mkdir -p "$ROOT/app builds"
      cp -v "$APK" "$ROOT/app builds/"
      echo "✅  APK: $ROOT/app builds/$(basename "$APK")"
    fi
  else
    echo "❌ Build failed. Run: ./build.sh log    (or tail -40 "$LOG_FILE")"
  fi
}

# ---------- commands ----------
CMD="${1:-debug}"
case "$CMD" in
  debug|build|"" ) do_build "assembleDebug"  "debug"      ;;
  release   ) do_build "assembleRelease" "release"   ;;
  clean     ) chmod +x ./gradlew 2>/dev/null || true ; ./gradlew clean --console=plain ; rm -f "$LOG_FILE" ;;
  stop      )
    pkill -f "GradleWrapperMain" 2>/dev/null; pkill -f "GradleDaemon" 2>/dev/null
    ./gradlew --stop 2>/dev/null | tail -1
    echo "🛑 Gradle processes stopped." ;;
  status    )
    if gradle_running; then echo "🟡 build RUNNING"; else echo "🟢 no build running"; fi
    APK=$(newest_apk)
    [ -n "$APK" ] && echo "🛢  newest APK: $APK ($(ls -lh "$APK" | awk '{print $5}'))"
    [ -f "$LOG_FILE" ] && echo "📜 log tail:" && tail -5 "$LOG_FILE" ;;
  log       ) [ -f "$LOG_FILE" ] && tail -f "$LOG_FILE" || echo "no log yet" ;;
  apk       ) APK=$(newest_apk); [ -n "$APK" ] && echo "$APK" || echo "no APK built yet" ;;
  env       ) env | grep -E "JAVA_HOME|ANDROID|LD_PRELOAD|PATH" ;;
  help|--help|-h ) sed -n '2,25p' "$0" | sed 's/^#//;s/^ //' ;;
  *         ) echo "Unknown command: $CMD"; echo; grep -A3 "Commands:" "$0" | head -14 ;;
esac
