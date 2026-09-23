#!/usr/bin/env bash
# Prints the short content hash that identifies a tunnel.aar build: the tracked files under tunnel/ at a commit,
# minus test-only files (Go never compiles them into the AAR), so test changes don't cut a new release.
set -euo pipefail
rev="${1:-HEAD}"
cd "$(git rev-parse --show-toplevel)"
entries=$(git ls-tree -r "$rev" -- tunnel \
  | awk -F'\t' '$2 !~ /_test\.go$/ && $2 !~ /\/testdata\// && $2 !~ /^tunnel\/internal\/testnet\//')
if [ -z "$entries" ]; then
  echo "no tunnel source files at $rev" >&2
  exit 1
fi
printf '%s\n' "$entries" | git hash-object --stdin | cut -c1-8
