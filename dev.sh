#!/bin/bash
# Développement : tue l'instance de dev précédente avant de lancer la nouvelle.
# Quarkus ne libère pas le port tout seul — sans ce ménage, le second lancement
# échoue sur « port already in use » pendant que l'ancien vit encore.
set -eo pipefail
PORT="${QUARKUS_PORT:-8090}"

if pid=$(lsof -ti :"$PORT" 2>/dev/null) && [ -n "$pid" ]; then
    echo "Port $PORT occupé par le PID $pid — arrêt propre…"
    kill $pid 2>/dev/null || true
    for _ in $(seq 1 20); do
        lsof -ti :"$PORT" >/dev/null 2>&1 || break
        sleep 0.5
    done
    if lsof -ti :"$PORT" >/dev/null 2>&1; then
        echo "Refuse le TERM — kill -9."
        kill -9 $(lsof -ti :"$PORT") 2>/dev/null || true
        sleep 1
    fi
fi

exec ./mvnw quarkus:dev
