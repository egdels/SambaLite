#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${ROOT_DIR}/ort-out/scancode"

echo "==> Cleaning previous ScanCode output"
rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"

# Scan only the shipped app source code, not the entire repository.
# Large upstream submodules, build outputs, tests, and binary files are excluded.
# Root-level files (LICENSE, NOTICE, etc.) are covered by ORT Analyze + Report.
SCAN_DIR="${ROOT_DIR}/app/src/main"

echo "==> Running ScanCode on ${SCAN_DIR}"
scancode \
  --copyright \
  --license \
  --info \
  --license-references \
  --strip-root \
  --processes 4 \
  --timeout 120 \
  --ignore "*.so" \
  --ignore "*.ort" \
  --ignore "*.traineddata" \
  --ignore "*.ttf" \
  --ignore "*.otf" \
  --ignore "*.gz" \
  --ignore "*.png" \
  --ignore "*.jpg" \
  --ignore "*.jpeg" \
  --ignore "*.webp" \
  --ignore "*.pdf" \
  --json-pp "${OUT_DIR}/scancode-result.json" \
  "${SCAN_DIR}"

echo "==> ScanCode completed"
echo "Result: ${OUT_DIR}/scancode-result.json"
