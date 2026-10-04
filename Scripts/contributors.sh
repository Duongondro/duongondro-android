#!/bin/sh
# Prints Contributors.json for Settings › About: every human with a commit in
# this repository and its siblings (api, design, ios) checked out next to it,
# most commits first, ties alphabetical. Names go through .mailmap; bots and AI
# authors are left out (Co-Authored-By trailers are never read). Same rules as
# duongondro-ios/Scripts/build-info.sh.
set -eu
cd "$(dirname "$0")/.."
{
  # Siblings sit next to this checkout (~/working/<project>/<repo>) or, from a
  # workspace (~/working/<project>/workspaces/<repo>-<name>), two levels up.
  echo .
  for name in duongondro-api duongondro-design duongondro-ios; do
    for repo in "../$name" "../../$name"; do
      # Its own repository, not some enclosing one.
      top=$(git -C "$repo" rev-parse --show-toplevel 2>/dev/null) || continue
      if [ "$top" = "$(cd "$repo" && pwd -P)" ]; then echo "$repo"; break; fi
    done
  done
} | while read -r repo; do
  git -C "$repo" log --use-mailmap --format='%aN%x09%aE' 2>/dev/null || true
done \
  | grep -viE '\[bot\]|[-+.]bot@|^[^	]*	bot@|noreply@anthropic\.com|noreply@openai\.com|^(claude|copilot|github-actions|dependabot|renovate)	' \
  | cut -f1 | sort | uniq -c | sort -k1,1nr -k2 \
  | sed -E 's/^ *[0-9]+ //; s/\\/\\\\/g; s/"/\\"/g; s/.*/"&"/' \
  | paste -sd, - | sed 's/^/[/; s/$/]/' | sed 's/^\[\]$/[]/'
