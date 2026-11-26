#!/usr/bin/env bash
set -euo pipefail

# One-step history & contributor cleanup for Mamm00/Rd12:
# - Replaces the root bot commit (481e408 by arena-ai-coding-agent[bot]) with a
#   clean single-author commit on 'main' authored 100% by Mamm00.
# - Removes this helper script prior to committing so the repository stays pure.
# - Re-points tag 'v1.0' to the clean commit so Release v1.0 has zero bot ancestry.
# - Deletes leftover remote session branches so only 'main' is listed publicly.

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

AUTHOR_NAME="Mamm00"
AUTHOR_EMAIL="89064315+Mamm00@users.noreply.github.com"

echo "[1/5] Removing cleanup script from working tree so final repo stays pure..."
rm -f scripts/clean-contributors.sh
rmdir scripts 2>/dev/null || true

echo "[2/5] Creating clean Mamm00-only commit on main..."
git checkout --orphan clean-main
git add -A

GIT_AUTHOR_NAME="$AUTHOR_NAME" \
GIT_AUTHOR_EMAIL="$AUTHOR_EMAIL" \
GIT_COMMITTER_NAME="$AUTHOR_NAME" \
GIT_COMMITTER_EMAIL="$AUTHOR_EMAIL" \
  git commit -m "feat: Boost12 v1.0 — On-Device Wireless ADB Performance Utility for Redmi 12 4G"

git branch -M clean-main main

echo "[3/5] Force-pushing clean main branch to GitHub..."
git push --force origin main

echo "[4/5] Re-pointing release tag v1.0 to clean main commit..."
GIT_COMMITTER_NAME="$AUTHOR_NAME" \
GIT_COMMITTER_EMAIL="$AUTHOR_EMAIL" \
  git tag -fa v1.0 -m "Boost12 v1.0 (Debug APK — Debugger Mode ON)"
git push --force origin refs/tags/v1.0

echo "[5/5] Deleting leftover remote session branches..."
git fetch --prune origin
for remote_branch in $(git branch -r | awk '{print $1}' | grep -E '^origin/arena/' || true); do
  branch_name="${remote_branch#origin/}"
  echo "  Deleting origin/${branch_name}..."
  git push origin --delete "$branch_name" || true
done

echo ""
echo "Success! 'Mamm00' is now the 100% sole contributor across main and v1.0."
echo "GitHub hides the public Contributors widget automatically once its cache refreshes (1-5 minutes)."
