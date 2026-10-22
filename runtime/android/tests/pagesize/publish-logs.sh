#!/usr/bin/env bash
# Publish the 16 KB VM's trace logs as neutral check runs on the commit, so they
# can be read with the checks API (and on the PR's Checks tab) without
# downloading the artifact. Each log: its first and last 30,000 characters,
# without the linker's config warnings.
#
# usage: publish-logs.sh NAME_PREFIX FILE...   (needs GH_TOKEN, GITHUB_REPOSITORY, GITHUB_SHA)
set -uo pipefail
prefix=$1; shift
for f in "$@"; do
  sudo test -s "$f" || { echo "::warning title=16K log::$f is missing or empty"; continue; }
  body=$(mktemp)
  # The VM writes these as root.
  sudo cat "$f" | grep -v 'linker: Warning: failed to find generated linker configuration' > "$body.all"
  if [ "$(wc -c < "$body.all")" -le 60000 ]; then cp "$body.all" "$body"
  else { head -c 30000 "$body.all"; printf '\n\n[... %s bytes cut ...]\n\n' "$(wc -c < "$body.all")"; tail -c 30000 "$body.all"; } > "$body"; fi
  # Check run text is Markdown: keep the log verbatim in a code block.
  { echo '```'; sed 's/```/` ` `/g' "$body"; echo '```'; } > "$body.md"
  python3 - "$prefix $(basename "$f")" "$body.md" > "$body.json" <<'PY'
import json, os, sys
name, path = sys.argv[1], sys.argv[2]
text = open(path, errors='replace').read()[:65000]
print(json.dumps({"name": name, "head_sha": os.environ["GITHUB_SHA"], "status": "completed",
                  "conclusion": "neutral", "output": {"title": name, "summary": "trace log", "text": text}}))
PY
  gh api -X POST "repos/$GITHUB_REPOSITORY/check-runs" --input "$body.json" > "$body.out" 2>&1 ||
    echo "::warning title=16K log::could not publish $f: $(head -c 600 "$body.out" | tr '\n' ' ')"
  rm -f "$body.out"
  rm -f "$body" "$body.all" "$body.md" "$body.json"
done
