#!/bin/bash
# Headless e2e scenario test-generation campaign — one run per scenario-group
# LETTER, one commit per group. The COMMIT is the judge (git log), never the
# presence of a file.
#
# Unlike the engine/unit campaigns this judge is NOTE-AND-CONTINUE: a failing
# group is recorded in FAILED and the campaign KEEPS GOING to the end — it never
# stops on a red group; the FAILED list is printed in the final summary.
#
# The judge has TWO success branches: the expected commit is HEAD, OR the
# working tree is clean.
#
# No `set -e`: a never-stopping campaign must not abort on the first non-zero
# status. `pipefail` is kept (a failing claude inside a tee pipe is still seen).
set -o pipefail

GRPS="${@:?usage: run-e2e-campaign.sh <letter> [letter...] e.g. G H J K}"

# 1) Validate arguments BEFORE invoking claude: each group must be a known
#    letter and appear at most once. Any offender stops the script immediately.
#    B is intentionally excluded — group B (authentication) is already
#    calibrated as GroupBIT.
seen=""
for g in $GRPS; do
  case "$g" in
    A|C|D|E|F|G|H|I|J|K|L|M|N|O|P|Q) ;;
    *) echo "ABORT: invalid group '$g' — expected one of A C D E F G H I J K L M N O P Q."; exit 1 ;;
  esac
  case " $seen " in
    *" $g "*) echo "ABORT: duplicate group '$g' — each letter must appear once."; exit 1 ;;
  esac
  seen="$seen $g"
done

FAILED=""
for g in $GRPS; do
  expected="test: e2e scenarios group $g"
  # 2) Skip is decided on the EXISTENCE OF THE COMMIT anywhere in history, never
  #    on the presence of a test file. If the group already landed its commit,
  #    move on.
  if git log --format=%s | grep -Fxq "$expected"; then
    echo "=== Group $g — already committed, skipping. ==="
    continue
  fi
  # 3) Purge this group's OWN partial, uncommitted *IT class before every
  #    (re)launch — the lesson of the Mac restart: a half-written GroupXIT.java
  #    from a crashed run must never be mistaken for done. Scoped to THIS letter
  #    so committed groups and other groups' uncommitted work (e.g. GroupBIT) are
  #    left untouched.
  itf="src/test/java/com/intermarche/valuation/e2e/Group${g}IT.java"
  git checkout -q -- "$itf" 2>/dev/null || true   # revert tracked partial edits
  git clean -fq -- "$itf" 2>/dev/null || true     # remove untracked partial file
  echo "=== Group $g ==="
  # 4) Generation pass — one claude per group. It never commits (the script owns
  #    the commit). A crash here is note-and-continue, not a stop.
  claude -p "/gen-e2e-group $g" 2>&1 | tee -a campaign-e2e.log \
    || echo "WARN: Group $g — generation pass returned non-zero (continuing)."
  # 5) Commit pass — run the group's mvn campaign, and ONLY if green stage the
  #    group's own artifacts and commit with the EXACT message.
  claude -p "Lance la campagne e2e du groupe $g : mvn -q verify -DskipUTs=true -DskipJsTests=true -Dit.test=Group${g}IT -DskipITs=false. Si tout est vert (résidus [P] et [W]-sans-harnais en @Disabled justifié admis) : signale les scénarios couverts, l'étage de chacun (RestAssured / Playwright / @Disabled) et le résidu justifié, puis stage UNIQUEMENT la classe Group${g}IT.java du groupe, ses ressources de seed/golden si elles ont changé, reports/e2e-$g.md (plus CLAUDE.md et .claude/ s'ils ont changé) et commite avec EXACTEMENT ce message : $expected — jamais git push. Si quelque chose est rouge : ne commite rien et explique." 2>&1 | tee -a campaign-e2e.log || true
  # 6) TWO-BRANCH judge, measured mechanically — not on claude's politeness.
  #    Branch A: the expected commit landed as HEAD.
  #    Branch B: the working tree is clean (nothing left uncommitted).
  #    Anything else = the group failed → NOTE it in FAILED and CONTINUE.
  actual="$(git log -1 --format=%s)"
  if [ "$actual" = "$expected" ]; then
    echo "OK: Group $g — expected commit landed as HEAD."
  elif [ -z "$(git status --porcelain)" ]; then
    echo "OK: Group $g — working tree clean."
  else
    echo "FAILED: Group $g — no '$expected' commit and tree is dirty (HEAD is '$actual')."
    FAILED="$FAILED $g"
  fi
done

# 7) Final summary — the campaign never stopped; the FAILED groups are reported
#    here. Exit non-zero if any group failed, so CI still sees the red.
echo "==================== E2E CAMPAIGN SUMMARY ===================="
if [ -n "$FAILED" ]; then
  echo "FAILED groups:$FAILED"
  echo "Campaign done WITH FAILURES."
  exit 1
fi
echo "All requested groups green."
echo "Campaign done."
