#!/usr/bin/env bash
# Mendorong isi sebuah folder ke branch khusus (dibuat ulang tiap run) agar log dan gambar uji
# mudah dibaca tanpa perlu login. Pemakaian: push-logs.sh <branch> <folder>
set -euo pipefail
BRANCH="$1"
SRC="$2"
W="$(mktemp -d)"
cp -r "$SRC"/. "$W"/
cd "$W"
git init -q
git config user.name "github-actions"
git config user.email "actions@users.noreply.github.com"
git checkout -q -b "$BRANCH"
git add -A
git commit -q -m "log run ${GITHUB_RUN_NUMBER:-0} (${GITHUB_SHA:-local})"
git push -q -f "https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" "$BRANCH"
echo "Dipublikasikan ke branch $BRANCH"
