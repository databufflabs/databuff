#!/usr/bin/env bash
# CI guard: manifest.schema_version must equal max(V*.sql migration number).
# Run from repo root: bash deploy/test/check-manifest-schema.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MANIFEST="${ROOT}/deploy/common/upgrade-manifest.json"
MIGRATIONS_DIR="${ROOT}/deploy/common/sql/migrations"

if [ ! -f "$MANIFEST" ]; then
  echo "FAIL: manifest not found: $MANIFEST" >&2; exit 1
fi
if [ ! -d "$MIGRATIONS_DIR" ]; then
  echo "FAIL: migrations dir not found: $MIGRATIONS_DIR" >&2; exit 1
fi

manifest_ver=$(python3 -c "import json; print(json.load(open('$MANIFEST'))['schema_version'])")
max_sql=0
for f in "$MIGRATIONS_DIR"/V*.sql; do
  base=$(basename "$f")
  if [[ "$base" =~ ^V([0-9]+)__ ]]; then
    v=$((10#${BASH_REMATCH[1]}))
    if [ "$v" -gt "$max_sql" ]; then max_sql=$v; fi
  fi
done

echo "manifest.schema_version=${manifest_ver}"
echo "max(V*.sql)=${max_sql}"
if [ "$manifest_ver" -ne "$max_sql" ]; then
  echo "FAIL: manifest.schema_version ($manifest_ver) != max V*.sql ($max_sql)" >&2
  echo "Fix: either bump manifest to $max_sql, or merge V${max_sql} content into V${manifest_ver} and delete V${max_sql}." >&2
  exit 1
fi
echo "PASS: manifest.schema_version matches max V*.sql"
