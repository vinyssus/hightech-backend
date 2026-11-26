#!/usr/bin/env bash
# Démarre ou met à jour HITECH DZ (dernière version de GitHub) : bash update.sh
set -euo pipefail
cd "$(dirname "$0")"
git -C /opt/hitech/hightech-backend pull --ff-only
git -C /opt/hitech/hitech-dz-commerce-suite pull --ff-only
docker compose -f docker-compose.prod.yml --env-file .env up -d --build
docker image prune -f >/dev/null
docker compose -f docker-compose.prod.yml --env-file .env ps
