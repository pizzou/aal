#!/usr/bin/env bash
# Run INSIDE an authorized backend environment. Prints presence only, never credentials.
set -euo pipefail

present() { if [[ -n "${!1:-}" ]]; then echo "$1=SET"; else echo "$1=MISSING"; fi; }
for key in SPRING_PROFILES_ACTIVE SPRING_DATASOURCE_URL DB_URL DATABASE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD SPRING_FLYWAY_URL SPRING_FLYWAY_USER SPRING_FLYWAY_PASSWORD; do
  present "$key"
done

if [[ -z "${SPRING_DATASOURCE_URL:-}${DB_URL:-}${DATABASE_URL:-}" ]]; then
  echo 'ERROR: No PostgreSQL database URL is configured.' >&2
  exit 1
fi
if [[ -n "${SPRING_FLYWAY_URL:-}" && "${SPRING_FLYWAY_URL}" != jdbc:postgresql:* ]]; then
  echo 'ERROR: SPRING_FLYWAY_URL must start with jdbc:postgresql:// (unlike DATABASE_URL).' >&2
  exit 1
fi
if [[ -n "${SPRING_FLYWAY_USER:-}" && -z "${SPRING_FLYWAY_PASSWORD:-}" ]] || [[ -z "${SPRING_FLYWAY_USER:-}" && -n "${SPRING_FLYWAY_PASSWORD:-}" ]]; then
  echo 'ERROR: Supply both SPRING_FLYWAY_USER and SPRING_FLYWAY_PASSWORD, or neither.' >&2
  exit 1
fi
if [[ -z "${SPRING_FLYWAY_USER:-}" ]]; then
  echo 'WARNING: No separate Flyway role. The application DB role must have schema migration permissions; use a dedicated migrator for production.' >&2
fi
printf '%s\n' 'Environment presence check passed. It does not verify connectivity, permissions, or migrations.'
