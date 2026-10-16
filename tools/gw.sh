#!/usr/bin/env bash
# Gradle wrapper launcher that keeps all build outputs outside the cloud-synced source tree.
#   tools/gw.sh :app:assembleDebug
# Env overrides: JAVA_HOME, ANDROID_HOME, SURVEILCAM_OUT (default ~/.build/SurveillanceCamera).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/jdk-21.0.12.1+1}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}"
OUT="${SURVEILCAM_OUT:-$HOME/.build/SurveillanceCamera}"
cd "$ROOT"
exec ./gradlew --project-cache-dir "$OUT/.gradle" \
  -Psurveilcam.buildRoot="$OUT" \
  -Pkotlin.project.persistent.dir="$OUT/.kotlin" \
  "$@"
