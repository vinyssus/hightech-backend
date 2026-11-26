#!/usr/bin/env bash
# Copie la base Render dans la base du serveur (À FAIRE UNE SEULE FOIS, avant la mise en ligne).
#   bash import-render-db.sh 'postgresql://UTILISATEUR:MOT_DE_PASSE@HOTE.render.com/BASE'
# L'URL est l'« External Database URL » (Render → base PostgreSQL → Connect). Elle n'est enregistrée nulle part.
# ATTENTION : remplace toutes les données de la base du serveur.
# Si Render indique une version de PostgreSQL plus récente que 17 : PG_CLIENT_IMAGE=postgres:18-alpine bash import-render-db.sh '…'
set -euo pipefail
cd "$(dirname "$0")"

RENDER_URL="${1:-}"
if [ -z "$RENDER_URL" ]; then
  echo "Usage : bash import-render-db.sh '<External Database URL de Render>'" >&2
  exit 1
fi
case "$RENDER_URL" in *sslmode=*) ;; *\?*) RENDER_URL="$RENDER_URL&sslmode=require" ;; *) RENDER_URL="$RENDER_URL?sslmode=require" ;; esac

set -a; . ./.env; set +a
COMPOSE="docker compose -f docker-compose.prod.yml --env-file .env"
DUMP="/opt/hitech/backups/render-$(date +%Y%m%d-%H%M%S).dump"

read -r -p "Toutes les données actuelles du serveur seront remplacées par celles de Render. Continuer ? (oui/non) " ok
[ "$ok" = "oui" ] || { echo "Annulé."; exit 1; }

echo "==> Arrêt de l'API et du site pendant la copie"
$COMPOSE stop api site 2>/dev/null || true
$COMPOSE up -d db
until $COMPOSE exec -T db pg_isready -U "$DB_USERNAME" -d hitechdz >/dev/null 2>&1; do sleep 2; done

echo "==> Export de la base Render vers $DUMP"
docker run --rm -e PGCONNECT_TIMEOUT=30 "${PG_CLIENT_IMAGE:-postgres:17-alpine}" pg_dump --format=custom --no-owner --no-privileges "$RENDER_URL" > "$DUMP"
chmod 600 "$DUMP"

echo "==> Import dans la base du serveur"
$COMPOSE exec -T db psql -v ON_ERROR_STOP=1 -U "$DB_USERNAME" -d hitechdz -c "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public AUTHORIZATION \"$DB_USERNAME\";"
$COMPOSE exec -T db pg_restore --no-owner --no-privileges --exit-on-error -U "$DB_USERNAME" -d hitechdz < "$DUMP"

echo "==> Vérification"
$COMPOSE exec -T db psql -U "$DB_USERNAME" -d hitechdz -c "select (select count(*) from products) as produits, (select count(*) from orders) as commandes, (select count(*) from users) as comptes;"
echo "Copie terminée. Démarrez tout : bash update.sh"
