#!/bin/bash
# Headless unit test-generation campaign — one run per class, one commit per
# package. The COMMIT is the only judge (git log -1), never the presence of a
# test file.
set -eo pipefail
PKGS="${@:?usage: run-unit-campaign.sh <pkg> [pkg...] e.g. domain engine.offers imports}"
for pkg in $PKGS; do
  dir="src/main/java/com/intermarche/valuation/${pkg//.//}"
  for f in "$dir"/*.java; do
    [ -f "$f" ] || continue
    c=$(basename "$f" .java)
    fqcn="com.intermarche.valuation.${pkg}.${c}"
    # Skip a class whose <Class>Test.java already exists.
    [ -f "src/test/java/com/intermarche/valuation/${pkg//.//}/${c}Test.java" ] && continue
    echo "=== $fqcn ==="
    claude -p "/gen-tests $fqcn" 2>&1 | tee -a campaign-unit.log || { echo "FAILED: $fqcn — stopping."; exit 1; }
  done
  expected="test: full branch coverage for com.intermarche.valuation.$pkg"
  claude -p "Lance mvn -q verify -DskipITs complet. Si tout est vert : donne la couverture de branches JaCoCo de chaque classe du package com.intermarche.valuation.$pkg au format « classe : n/n (%) », signale toute classe sans test, puis stage uniquement les classes de test de ce package (plus CLAUDE.md et .claude/ s'ils ont changé) et commite avec exactement ce message : $expected — jamais git push. Si quelque chose est rouge : ne commite rien et explique." 2>&1 | tee -a campaign-unit.log || true
  # Success is measured on the commit, not on claude's politeness: verify
  # mechanically that the expected commit landed as HEAD.
  actual="$(git log -1 --format=%s)"
  if [ "$actual" != "$expected" ]; then
    echo "FAILED: package $pkg — expected commit '$expected' not found (HEAD is '$actual')."
    exit 1
  fi
done
echo "Campaign done."
