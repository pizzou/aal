#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REPORT_DIR="${REPORT_DIR:-$ROOT/release-reports/security}"
mkdir -p "$REPORT_DIR"

command -v mvn >/dev/null || { echo "Maven is required" >&2; exit 1; }
command -v npm >/dev/null || { echo "npm is required" >&2; exit 1; }

echo "==> Backend OWASP dependency scan"
(
  cd "$ROOT/backend"
  mvn -B -ntp org.owasp:dependency-check-maven:check \
    -DskipTests \
    -DfailBuildOnCVSS="${FAIL_BUILD_ON_CVSS:-9}" \
    -Dformats=HTML,JSON
) 2>&1 | tee "$REPORT_DIR/backend-dependency-check.log"

echo "==> Frontend dependency audit"
(
  cd "$ROOT/frontend"
  npm audit --omit=dev --audit-level="${NPM_AUDIT_LEVEL:-high}" --json > "$REPORT_DIR/npm-audit.json"
) || {
  echo "Frontend dependency audit failed. Review $REPORT_DIR/npm-audit.json" >&2
  exit 1
}

echo "Dependency/security scan passed."
