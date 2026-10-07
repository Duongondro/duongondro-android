#!/bin/sh
# Publishes the signed release APK as a GitHub Release (make publish NOTES=<file>).
# Refuses unless the version was bumped since the last release, so a phone that
# installed the last APK can update to this one, and unless the commit is pushed,
# so the Source link in Settings opens it. The landing page's download link
# always serves the newest release's duongondro.apk.
set -eu
cd "$(dirname "$0")/.."

notes=${NOTES:?usage: make publish NOTES=<file with the release notes>}
[ -f "$notes" ] || { echo "no such notes file: $notes" >&2; exit 1; }

name=$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' app/build.gradle.kts)
code=$(sed -n 's/^ *versionCode = \([0-9][0-9]*\)$/\1/p' app/build.gradle.kts)
[ -n "$name" ] && [ -n "$code" ] || { echo "versionName or versionCode not found in app/build.gradle.kts" >&2; exit 1; }

git fetch -q origin --tags
head=$(git rev-parse HEAD)
[ "$(git ls-remote origin refs/heads/main | cut -f1)" = "$head" ] ||
  { echo "HEAD $head is not origin/main: push first" >&2; exit 1; }

last=$(gh release view --json tagName -q .tagName 2>/dev/null || true)
if [ -n "$last" ]; then
  [ "v$name" != "$last" ] ||
    { echo "v$name is already released: bump versionName and versionCode in app/build.gradle.kts" >&2; exit 1; }
  lastcode=$(git show "$last:app/build.gradle.kts" | sed -n 's/^ *versionCode = \([0-9][0-9]*\)$/\1/p')
  [ "$code" -gt "$lastcode" ] ||
    { echo "versionCode $code must be greater than $last's $lastcode" >&2; exit 1; }
fi

make apk
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
cp app/build/outputs/apk/release/app-release.apk "$out/duongondro.apk"
gh release create "v$name" "$out/duongondro.apk" --target "$head" \
  --title "Duongöndro $name for Android" --notes-file "$notes" --latest
