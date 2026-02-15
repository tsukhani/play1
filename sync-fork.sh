#!/usr/bin/env bash
set -euo pipefail

echo "Fetching latest from origin..."
git fetch origin

LOCAL_COMMITS=$(git log --oneline origin/master..HEAD)
if [ -z "$LOCAL_COMMITS" ]; then
  echo "No local commits ahead of origin/master. Nothing to do."
  exit 0
fi

echo "Local commits to rebase:"
echo "$LOCAL_COMMITS"
echo

echo "Rebasing onto origin/master..."
if ! git rebase origin/master; then
  echo
  echo "Rebase failed due to conflicts. Aborting rebase and restoring previous state."
  git rebase --abort
  exit 1
fi

echo "Pushing to fork with --force-with-lease..."
git push fork master --force-with-lease

echo "Done. Fork is synced with upstream."
