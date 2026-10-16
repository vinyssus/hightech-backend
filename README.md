# HITECH DZ — Backend d'authentification

Spring Boot 3.5 · Java 17+ · Spring Security + JWT · PostgreSQL · Flyway

## Démarrage rapide

```bash
# 1. Base de données
cp .env.example .env            # puis renseigner JWT_SECRET (openssl rand -base64 48)
docker compose up -d            # PostgreSQL sur localhost:5432

# 2. Lancer l'API (port 8081, le frontend React restant sur 8080)
set -a && source .env && set +a
mvn spring-boot:run

# 3. Tests d'intégration (H2 en mode PostgreSQL, aucune base requise)
mvn test
```

Au démarrage, Flyway crée les tables et le compte de démonstration
**admin@hitech-dz.cm / Admin123** (rôle ADMIN) est créé s'il n'existe pas.
En production : `ADMIN_SEED_ENABLED=false`.

Côté frontend, pointer l'URL de l'API sur `http://localhost:8081`.

## Structure

```
src/main/java/cm/hitechdz/auth/
├── HitechDzAuthApplication.java
├── config/        AppProperties, SecurityConfig (CORS, règles d'accès), DataInitializer (admin)
├── controller/    AuthController (/api/auth/**), AdminController (/api/admin/**)
├── domain/        User, Role, RefreshToken, PasswordResetCode
├── dto/           requêtes/réponses + ValidationRules (regex téléphone, mot de passe)
├── exception/     ApiException, GlobalExceptionHandler (format { message, fieldErrors })
├── repository/    UserRepository, RefreshTokenRepository, PasswordResetCodeRepository
├── security/      JwtService, JwtAuthenticationFilter, RateLimiter, RateLimitFilter,
│                  JsonAuthenticationEntryPoint (401), JsonAccessDeniedHandler (403)
└── service/       AuthService, RefreshTokenService, CleanupJob, mail/(MailService, Console, Smtp)
src/main/resources/
├── application.yml
└── db/migration/V1__create_auth_tables.sql
```

## Choix de sécurité

| Sujet | Implémentation |
|---|---|
| Access token | JWT HS256, 15 min, claims `sub` (id), `email`, `role`, `iss` |
| Refresh token | Opaque (256 bits aléatoires), **seul le hash SHA-256 est stocké** ; 30 j si `remember`, 1 j sinon |
| Rotation | Chaque `/refresh` révoque l'ancien token et en émet un nouveau (même durée) |
| Réutilisation | Présenter un refresh token déjà révoqué révoque **toutes** les sessions de l'utilisateur |
| Révocation | Colonne `revoked` en base (logout, reset de mot de passe) |
| Mots de passe | BCrypt (coût 12) ; 8+ caractères, 1 majuscule, 1 chiffre |
| Énumération | `/forgot-password` répond toujours 200 ; `/login` compare un hash factice si l'e-mail est inconnu |
| Code de reset | 6 chiffres, hashé BCrypt, 15 min, 5 essais max, usage unique |
| Admin | Règle d'URL `/api/admin/**` → `ROLE_ADMIN` **et** `@PreAuthorize` sur le contrôleur |
| Rate limiting | Par IP, fenêtre glissante : login 10/min, forgot-password 5/15 min → 429 + `Retry-After` |
| Secrets | `JWT_SECRET`, `DB_USERNAME`, `DB_PASSWORD` obligatoires, aucune valeur par défaut |

Remarques :
- Le rate limiter est en mémoire : suffisant pour une instance. En multi-instances, passer à Redis/Bucket4j.
- Derrière nginx, mettre `FORWARD_HEADERS_STRATEGY=native` pour que le rate limiting voie l'IP réelle.
- `/refresh` renvoie `{ user, accessToken, refreshToken }` (le `user` en plus du couple de tokens
  permet au frontend de rafraîchir aussi l'état utilisateur).

## Exemples curl

```bash
API=http://localhost:8081/api
```

**1. Inscription** (201)
```bash
curl -s -X POST $API/auth/register -H 'Content-Type: application/json' -d '{
  "firstName":"Jean","lastName":"Mbarga","email":"jean@example.cm",
  "phone":"6 77 12 34 56","password":"Secret123"}'
```
Doublon → 409 `{"message":"Un compte existe déjà avec cette adresse e-mail.", ...}`
Validation → 400 `{"message":"Certains champs sont invalides.","fieldErrors":{"phone":"..."}}`

**2. Connexion** (200) — conserver les tokens
```bash
RESP=$(curl -s -X POST $API/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"jean@example.cm","password":"Secret123","remember":true}')
ACCESS=$(echo "$RESP" | jq -r .accessToken)
REFRESH=$(echo "$RESP" | jq -r .refreshToken)
```
Mauvais identifiants → 401 `{"message":"E-mail ou mot de passe incorrect."}`

**3. Rafraîchissement** (rotation)
```bash
RESP=$(curl -s -X POST $API/auth/refresh -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH\"}")
ACCESS=$(echo "$RESP" | jq -r .accessToken)
REFRESH=$(echo "$RESP" | jq -r .refreshToken)
```

**4. Utilisateur courant**
```bash
curl -s $API/auth/me -H "Authorization: Bearer $ACCESS"
```

**5. Mise à jour du profil**
```bash
curl -s -X PUT $API/auth/profile -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"firstName":"Jean-Paul","lastName":"Mbarga","email":"jean@example.cm","phone":"+237 699887766"}'
```

**6. Changement de mot de passe**
```bash
curl -s -X PUT $API/auth/password -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"currentPassword":"Secret123","newPassword":"Nouveau123"}'
```

**7. Mot de passe oublié** — le code apparaît dans les logs de l'application (MailService console)
```bash
curl -s -X POST $API/auth/forgot-password -H 'Content-Type: application/json' \
  -d '{"email":"jean@example.cm"}'
```

**8. Réinitialisation**
```bash
curl -s -X POST $API/auth/reset-password -H 'Content-Type: application/json' \
  -d '{"email":"jean@example.cm","code":"123456","password":"Reset1234"}'
```

**9. Déconnexion**
```bash
curl -s -X POST $API/auth/logout -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH\"}"
```

**Admin** (403 pour un CUSTOMER, 200 pour l'admin)
```bash
ADMIN=$(curl -s -X POST $API/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@hitech-dz.cm","password":"Admin123"}' | jq -r .accessToken)
curl -s $API/admin/users -H "Authorization: Bearer $ADMIN"
```

**Rate limiting** — la 11ᵉ tentative en moins d'une minute renvoie 429
```bash
for i in $(seq 1 11); do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST $API/auth/login -H 'Content-Type: application/json' \
    -d '{"email":"x@example.cm","password":"Faux1234"}'
done
```
