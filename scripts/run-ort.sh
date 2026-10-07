#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${ROOT_DIR}/ort-out"

echo "==> Cleaning previous ORT output"
rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"

# The Gradle daemon must run on a JDK that Gradle 8.13 supports (<= 24), while
# ORT itself needs Java 25. Point ORT_GRADLE_JAVA_HOME at a JDK 21 to pin the
# JDK used for dependency resolution; unset, ORT uses its own JDK for Gradle.
GRADLE_JAVA_ARGS=()
if [[ -n "${ORT_GRADLE_JAVA_HOME:-}" ]]; then
  echo "==> Using JDK for Gradle: ${ORT_GRADLE_JAVA_HOME}"
  GRADLE_JAVA_ARGS=(-P "ort.analyzer.packageManagers.GradleInspector.options.javaHome=${ORT_GRADLE_JAVA_HOME}")
fi

echo "==> Running ORT analyze"
ort --info --stacktrace --config "${ROOT_DIR}/ort/config/config.yml" \
  ${GRADLE_JAVA_ARGS[@]+"${GRADLE_JAVA_ARGS[@]}"} \
  analyze \
  -i "${ROOT_DIR}" \
  -o "${OUT_DIR}/analyzer"

echo "==> Running ORT report"
ort --info --stacktrace --config "${ROOT_DIR}/ort/config/config.yml" \
  report \
  -i "${OUT_DIR}/analyzer/analyzer-result.yml" \
  -o "${OUT_DIR}/reports" \
  -f StaticHtml,WebApp,EvaluatedModel

echo "==> ORT completed"
echo "Analyzer: ${OUT_DIR}/analyzer"
echo "Reports:  ${OUT_DIR}/reports"
