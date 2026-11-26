# Déployer HITECH DZ sur le serveur Ubuntu du client

Tout tourne dans Docker sur le serveur :

| Service | Rôle |
|---|---|
| `db` | PostgreSQL 17 (jamais exposé sur Internet) |
| `api` | backend Spring Boot |
| `site` | site (rendu serveur Node + relais `/backend`) |
| `caddy` | ports 80/443, certificat HTTPS automatique, `/api/*` → API, le reste → site |

Prérequis : Ubuntu 22.04 ou 24.04, 2 Go de RAM minimum (4 Go conseillés), 20 Go de disque, et un nom de domaine.

> Ne transmettez jamais le mot de passe du serveur par chat, WhatsApp ou e-mail. Changez-le dès la première connexion.

## 1. DNS (chez le fournisseur du domaine)
Créez deux enregistrements **A** vers l'adresse IP du serveur : `exemple.cm` et `www.exemple.cm`.
Vérifiez depuis votre PC : `ping exemple.cm` doit afficher l'IP du serveur (la propagation peut prendre jusqu'à quelques heures).

## 2. Première connexion et sécurisation
Depuis votre PC (PowerShell sous Windows, Terminal sous Mac/Linux) :
```bash
ssh root@ADRESSE_IP
passwd                                  # nouveau mot de passe root, tout de suite
adduser deploy                          # votre utilisateur de travail
usermod -aG sudo deploy
```
Toujours depuis **votre PC** (une seule fois, si vous n'avez pas encore de clé : `ssh-keygen -t ed25519`) :
```bash
ssh-copy-id deploy@ADRESSE_IP           # sous Windows : type $env:USERPROFILE\.ssh\id_ed25519.pub | ssh deploy@ADRESSE_IP "mkdir -p ~/.ssh && cat >> ~/.ssh/authorized_keys"
ssh deploy@ADRESSE_IP                   # doit marcher SANS mot de passe
```
Puis, connecté en `deploy`, désactivez la connexion root et par mot de passe :
```bash
sudo sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin no/; s/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
sudo systemctl restart ssh
```
Gardez la première fenêtre ouverte et vérifiez dans une nouvelle fenêtre que `ssh deploy@ADRESSE_IP` fonctionne encore avant de fermer quoi que ce soit.

## 3. Préparer le serveur (pare-feu, Docker, code, secrets)
```bash
curl -fsSL https://raw.githubusercontent.com/vinyssus/hightech-backend/main/deploy/setup-server.sh -o setup-server.sh
bash setup-server.sh
```
Le script affiche une **clé de déploiement** : ajoutez-la dans GitHub (dépôt `hitech-dz-commerce-suite` → Settings → Deploy keys → Add deploy key, **sans** « Allow write access »), puis appuyez sur Entrée.
Déconnectez-vous et reconnectez-vous ensuite (`exit` puis `ssh deploy@ADRESSE_IP`).

## 4. Configurer
```bash
cd /opt/hitech/hightech-backend/deploy
nano .env
```
- `DOMAIN` : votre domaine, sans `https://` ni `www` (ex. `exemple.cm`).
- `DB_PASSWORD`, `JWT_SECRET`, `RELAY_SECRET` : déjà générés, n'y touchez pas.
- Cloudinary, e-mails, Loov, assistant : recopiez les valeurs de Render (hightech-backend → Environment). Décommentez (retirez le `#`) uniquement les lignes que vous remplissez.
- `WHATSAPP_NUMBER` : le numéro du bouton WhatsApp (chiffres seulement, `237…`).

## 5. Reprendre les données de Render
Dans Render → la base PostgreSQL → **Connect** → copiez l'**External Database URL**, puis :
```bash
bash import-render-db.sh 'postgresql://…render.com/…'
```
Produits, catégories, commandes, comptes clients et réglages sont copiés ; les images restent sur Cloudinary.
(Nouvelle boutique sans données : sautez cette étape et mettez `ADMIN_SEED_ENABLED=true`, `ADMIN_EMAIL`, `ADMIN_PASSWORD` dans `.env`.)

## 6. Démarrer
```bash
bash update.sh
```
Le premier démarrage prend quelques minutes (construction des images). Vérifiez :
```bash
docker compose -f docker-compose.prod.yml --env-file .env ps          # 4 services « running »
docker compose -f docker-compose.prod.yml --env-file .env logs -f api # Ctrl+C pour quitter
curl -s https://exemple.cm/api/checkout/options
```
Ouvrez `https://exemple.cm` : le cadenas HTTPS doit apparaître.

## 7. Après la mise en ligne
1. **Loov** : URL du webhook → `https://exemple.cm/api/payments/webhooks/loov`.
2. **Sauvegardes quotidiennes** à 2 h :
   ```bash
   crontab -e
   # ajouter la ligne :
   0 2 * * * /opt/hitech/hightech-backend/deploy/backup.sh >> /opt/hitech/backups/backup.log 2>&1
   ```
   Copiez régulièrement `/opt/hitech/backups` hors du serveur (ex. depuis votre PC : `scp -r deploy@ADRESSE_IP:/opt/hitech/backups .`).
3. **Test complet** : inscription, commande avec paiement à la livraison, tableau de bord admin.
4. Quand tout fonctionne : supprimez la base et les services Render pour ne pas payer deux fois (gardez d'abord une sauvegarde).

## Au quotidien
| Besoin | Commande (dans `/opt/hitech/hightech-backend/deploy`) |
|---|---|
| Mettre à jour après des modifications sur GitHub | `bash update.sh` |
| Voir les journaux de l'API | `docker compose -f docker-compose.prod.yml --env-file .env logs -f api` |
| Redémarrer | `docker compose -f docker-compose.prod.yml --env-file .env restart` |
| Sauvegarder maintenant | `bash backup.sh` |
| Restaurer une sauvegarde | `bash restore-backup.sh /opt/hitech/backups/hitech-….dump` |

Les modifications faites dans Lovable arrivent sur GitHub ; elles ne sont en ligne sur le serveur qu'après `bash update.sh`.
