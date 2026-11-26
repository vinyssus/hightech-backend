#!/usr/bin/env bash
# Sauvegarde quotidienne de la base (garde 14 jours). Planification : voir DEPLOIEMENT.md (crontab).
# Copiez aussi régulièrement /opt/hitech/backups HORS du serveur : une sauvegarde sur le même disque ne protège pas d'une panne.
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a
DIR=/opt/hitech/backups
mkdir -p "$DIR"
FILE="$DIR/hitech-$(date +%Y%m%d-%H%M).dump"
docker compose -f docker-compose.prod.yml --env-file .env exec -T db pg_dump --format=custom -U "$DB_USERNAME" -d hitechdz > "$FILE"
chmod 600 "$FILE"
find "$DIR" -name 'hitech-*.dump' -mtime +14 -delete
echo "Sauvegarde : $FILE ($(du -h "$FILE" | cut -f1))"
