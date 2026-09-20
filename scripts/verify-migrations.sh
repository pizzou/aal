#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIR="$ROOT/backend/src/main/resources/db/migration"
mapfile -t files < <(find "$DIR" -maxdepth 1 -type f -name 'V*__*.sql' -printf '%f\n' | sort -V)

if ((${#files[@]} == 0)); then
  echo "No Flyway migrations found" >&2
  exit 1
fi

expected=1
for file in "${files[@]}"; do
  version="${file#V}"
  version="${version%%__*}"
  [[ "$version" =~ ^[0-9]+$ ]] || { echo "Invalid migration filename: $file" >&2; exit 1; }
  if (( version != expected )); then
    echo "Flyway migration gap/duplicate: expected V${expected}, found V${version} (${file})" >&2
    exit 1
  fi
  ((expected++))
done

echo "Flyway migrations verified: V1 through V$((expected-1)) (${#files[@]} files), no gaps or duplicates."
