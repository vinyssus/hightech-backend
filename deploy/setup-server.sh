#!/usr/bin/env bash
# Prépare un serveur Ubuntu 22.04/24.04 pour HITECH DZ. À lancer avec l'utilisateur « deploy » (sudo) :
#   bash setup-server.sh
# Installe : mises à jour automatiques de sécurité, pare-feu (22/80/443), fail2ban, Docker ;
# crée /opt/hitech/hightech-backend/deploy/.env avec des secrets générés.
set -euo pipefail

if [ "$(id -u)" -eq 0 ]; then
  echo "Lancez ce script avec votre utilisateur (ex. deploy), pas root : il utilise sudo." >&2
  exit 1
fi

echo "==> Mises à jour du système"
sudo apt-get update -y
sudo DEBIAN_FRONTEND=noninteractive apt-get upgrade -y
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y ca-certificates curl git openssl ufw fail2ban unattended-upgrades
sudo dpkg-reconfigure -f noninteractive unattended-upgrades

echo "==> Pare-feu : SSH, HTTP, HTTPS uniquement"
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow 443/udp
sudo ufw --force enable
sudo systemctl enable --now fail2ban

if ! command -v docker >/dev/null 2>&1; then
  echo "==> Installation de Docker"
  curl -fsSL https://get.docker.com | sudo sh
fi
sudo usermod -aG docker "$USER"
sudo systemctl enable --now docker

echo "==> Code de l'application dans /opt/hitech"
sudo mkdir -p /opt/hitech
sudo chown "$USER":"$USER" /opt/hitech
cd /opt/hitech
[ -d hightech-backend ] || git clone https://github.com/vinyssus/hightech-backend.git
# Dépôt du site privé : clé de déploiement en lecture seule, propre à ce serveur
KEY="$HOME/.ssh/github_hitech_site"
if [ ! -d hitech-dz-commerce-suite ]; then
  mkdir -p "$HOME/.ssh" && chmod 700 "$HOME/.ssh"
  [ -f "$KEY" ] || ssh-keygen -t ed25519 -N "" -C "hitech-server-deploy" -f "$KEY" >/dev/null
  grep -q "github_hitech_site" "$HOME/.ssh/config" 2>/dev/null || cat >> "$HOME/.ssh/config" <<CFG
Host github-hitech-site
  HostName github.com
  User git
  IdentityFile $KEY
  IdentitiesOnly yes
CFG
  chmod 600 "$HOME/.ssh/config"
  ssh-keyscan -t ed25519 github.com >> "$HOME/.ssh/known_hosts" 2>/dev/null
  echo
  echo "Ajoutez cette clé dans GitHub : dépôt hitech-dz-commerce-suite → Settings → Deploy keys → Add deploy key"
  echo "(titre : serveur HITECH, NE PAS cocher « Allow write access ») :"
  echo
  cat "$KEY.pub"
  echo
  read -r -p "Appuyez sur Entrée une fois la clé ajoutée… " _
  git clone git@github-hitech-site:vinyssus/hitech-dz-commerce-suite.git
fi

ENV_FILE=/opt/hitech/hightech-backend/deploy/.env
if [ ! -f "$ENV_FILE" ]; then
  echo "==> Création de $ENV_FILE avec des secrets générés"
  cp /opt/hitech/hightech-backend/deploy/env.prod.example "$ENV_FILE"
  sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=$(openssl rand -hex 24)|" "$ENV_FILE"
  sed -i "s|^JWT_SECRET=.*|JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')|" "$ENV_FILE"
  sed -i "s|^RELAY_SECRET=.*|RELAY_SECRET=$(openssl rand -hex 32)|" "$ENV_FILE"
  chmod 600 "$ENV_FILE"
fi

mkdir -p /opt/hitech/backups
echo
echo "Terminé. Étapes suivantes :"
echo "  1. Déconnectez-vous puis reconnectez-vous (droits Docker)."
echo "  2. nano $ENV_FILE   (DOMAIN, Cloudinary, e-mails, Loov… depuis Render)"
echo "  3. Suivez DEPLOIEMENT.md (reprise des données, démarrage)."
