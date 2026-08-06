#!/bin/bash
# Headless engine test-generation campaign — one run per scenario group,
# one commit per group. The COMMIT is the only judge (git log), never the
# presence of a file.
set -eo pipefail
GRPS="${@:?usage: run-tests-campaign.sh <group> [group...] e.g. OF GE PR}"
# Valid groups (see imvaluation-test-kit.md §2): VA OF GE PR IM GQ.
# 1) Validate arguments BEFORE invoking claude: each group must be one of
#    the known codes and appear at most once. Any offender stops the script
#    immediately with a message.
seen=""
for g in $GRPS; do
  case "$g" in
    VA|OF|GE|PR|IM|GQ) ;;
    *) echo "ABORT: invalid group '$g' — expected one of VA OF GE PR IM GQ."; exit 1 ;;
  esac
  case " $seen " in
    *" $g "*) echo "ABORT: duplicate group '$g' — each code must appear once."; exit 1 ;;
  esac
  seen="$seen $g"
done
for g in $GRPS; do
  expected="test: engine scenarios group $g"
  # 2) Skip is decided on the EXISTENCE OF THE COMMIT, never on the presence
  #    of a test file. If the group already landed its commit, move on.
  if git log --format=%s | grep -Fxq "$expected"; then
    echo "=== Group $g — already committed, skipping. ==="
    continue
  fi
  # 3) Purge partial, UNCOMMITTED *Test classes before every (re)launch —
  #    the lesson of the Mac restart: a half-written class from a crashed
  #    run must never be mistaken for done. Committed groups are untouched;
  #    only uncommitted test-tree changes are reset.
  git clean -fq -- 'src/test/**/*Test.java' 2>/dev/null || true
  git checkout -q -- src/test 2>/dev/null || true
  echo "=== Group $g ==="
  claude -p "/gen-group $g" 2>&1 | tee -a campaign-tests.log || { echo "FAILED: Group $g — stopping."; exit 1; }
  claude -p "Lance la campagne mvn appropriée au groupe $g (unitaire pur -Dtest=... pour OF/GE/PR ; RestAssured -DskipUTs=true -DskipITs=false pour VA/IM/GQ, voir §2 du kit). Si tout est vert et les branches à 100 % : signale les scénarios couverts et le résidu justifié, puis stage uniquement les classes de test du groupe $g (plus CLAUDE.md, .claude/ et reports/ s'ils ont changé) et commite avec exactement ce message : $expected — jamais git push. Si quelque chose est rouge : ne commite rien et explique." 2>&1 | tee -a campaign-tests.log || true
  # 4) Success is measured on the commit, not on claude's politeness: verify
  #    mechanically that the expected commit landed as HEAD, whatever claude
  #    returned.
  actual="$(git log -1 --format=%s)"
  if [ "$actual" != "$expected" ]; then
    echo "FAILED: Group $g — expected commit '$expected' not found (HEAD is '$actual')."
    exit 1
  fi
done
echo "Campaign done."
