#!/usr/bin/env bash
# Restaure une sauvegarde faite par backup.sh :  bash restore-backup.sh /opt/hitech/backups/hitech-AAAAMMJJ-HHMM.dump
set -euo pipefail
cd "$(dirname "$0")"
FILE="${1:?Indiquez le fichier .dump à restaurer}"
set -a; . ./.env; set +a
COMPOSE="docker compose -f docker-compose.prod.yml --env-file .env"
read -r -p "La base actuelle sera remplacée par $FILE. Continuer ? (oui/non) " ok
[ "$ok" = "oui" ] || { echo "Annulé."; exit 1; }
$COMPOSE stop api site
$COMPOSE exec -T db psql -v ON_ERROR_STOP=1 -U "$DB_USERNAME" -d hitechdz -c "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public AUTHORIZATION \"$DB_USERNAME\";"
$COMPOSE exec -T db pg_restore --no-owner --exit-on-error -U "$DB_USERNAME" -d hitechdz < "$FILE"
$COMPOSE up -d
echo "Restauration terminée."
