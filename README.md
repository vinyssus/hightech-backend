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

**Administration des utilisateurs** (rôle ADMIN requis)

| Méthode & route | Description |
|---|---|
| `GET /api/admin/stats` | `{ totalUsers, customers, admins, blockedUsers, newUsersLast7Days, newUsersLast30Days }` |
| `GET /api/admin/users?search=&role=&enabled=&page=0&size=20&sort=createdAt,desc` | Liste paginée `{ content, page, size, totalElements, totalPages }` |
| `GET /api/admin/users/{id}` | Détail (`enabled` inclus) |
| `PUT /api/admin/users/{id}/role` | `{ "role": "ADMIN" \| "CUSTOMER" }` |
| `PUT /api/admin/users/{id}/status` | `{ "enabled": false }` bloque, `true` débloque |
| `DELETE /api/admin/users/{id}` | 204 |

Garde-fous : pas d'action sur son propre compte (400), jamais zéro administrateur actif (409).
Un compte bloqué reçoit 403 à la connexion ; blocage, rétrogradation et suppression prennent effet
immédiatement (le compte est relu en base à chaque requête authentifiée, sessions révoquées).

```bash
curl -s "$API/admin/users?search=mbarga" -H "Authorization: Bearer $ADMIN"
curl -s -X PUT $API/admin/users/<id>/status -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d '{"enabled":false}'
```

**Rate limiting** — la 11ᵉ tentative en moins d'une minute renvoie 429
```bash
for i in $(seq 1 11); do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST $API/auth/login -H 'Content-Type: application/json' \
    -d '{"email":"x@example.cm","password":"Faux1234"}'
done
```

## Catalogue (produits, catégories, marques, avis, favoris)

Images hébergées sur **Cloudinary** (variables `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`,
`CLOUDINARY_API_SECRET`). Sans elles, l'application démarre mais les envois d'images renvoient 503.
Catalogue de démonstration : `SEED_CATALOG=true` (ou profil `dev`) sur une base vide.

**Public** : `GET /api/products` (q, cat, brands, min, max, promo, rating, inStock, sort, page, size + `facets`),
`GET /api/products/{slug}`, `/flash`, `/new`, `/best-sellers`, `/search?q=&limit=`, `GET /api/categories`,
`GET /api/brands`, `GET /api/products/{slug}/reviews`, `POST /api/cart/validate`.

**Client connecté** : `POST /api/products/{slug}/reviews`, `POST /api/reviews/{id}/helpful`,
`GET|PUT|DELETE /api/me/favorites[/{productId}]`, `POST /api/me/favorites/merge`.

**Admin** : `GET|POST /api/admin/products`, `GET /api/admin/products/{id}`, `/slug/{slug}`, `/low-stock`,
`PUT|DELETE /api/admin/products/{id}`, `PUT /api/admin/products/{id}/variants` (`{ combinations: [...] }`),
`POST /api/admin/uploads` (champ `files` → `{ urls }`), `POST /api/admin/uploads/images`, `POST /api/admin/products/{id}/images`,
`DELETE /api/admin/products/{id}/images/{publicId encodé}`, `PUT /api/admin/products/{id}/images/order`,
`GET|POST /api/admin/categories`, `PUT|DELETE /api/admin/categories/{slug}`,
`GET|POST /api/admin/brands`, `PUT|DELETE /api/admin/brands/{slug}` (JSON ou multipart avec `logo`),
`GET /api/admin/reviews`, `DELETE /api/admin/reviews/{id}`.

Le stock est réservé par `InventoryService.reserve` (verrou `SELECT … FOR UPDATE`) ; le module Commandes
appellera `confirmPayment`, `failPayment`, `cancel` et `markDelivered`.

## Commandes, livraison et paiements

Documentation interactive : `GET /swagger-ui.html` (OpenAPI : `/v3/api-docs`). Montants en francs CFA entiers (`XAF`).
Le serveur recalcule toujours prix, frais, remise, total et statut de paiement : les valeurs envoyées par le client ne font jamais foi.
Le site ne demande, ne reçoit et n'enregistre jamais le code secret Mobile Money : le client valide sur son téléphone.

### Mise en service (admin)

1. Valider puis activer les zones de livraison (créées **inactives** : douala, yaounde, autre, retrait) :
   `PUT /api/admin/shipping-zones/{id}` `{"label":"Douala","fee":2000,"delay":"24 à 48 h","active":true,"codAllowed":true}`.
   Tant qu'aucune zone n'est active, `GET /api/checkout/options` renvoie une liste vide et aucune commande n'est possible.
2. Réglages : `GET/PUT /api/admin/checkout/settings` `{"freeShippingFrom":null,"quoteValidityMinutes":15,"reservationMinutes":30,"paymentTimeoutMinutes":5}`.
3. Codes promo : `GET/POST/PUT/DELETE /api/admin/promos`. Aucun code n'existe au départ ; BIENVENUE10, HITECH5000 et LIVRAISON
   (démos du frontend) sont refusés tant qu'ils n'ont pas été créés après validation commerciale.

### Parcours client

| Méthode | Route | Accès |
|---|---|---|
| POST | `/api/cart/validate` | public (contrat inchangé) |
| GET | `/api/checkout/options` | public |
| POST | `/api/checkout/quote` | public |
| POST | `/api/orders` (en-tête `Idempotency-Key` obligatoire) | public ou connecté → 201 |
| GET | `/api/orders/{number}` | propriétaire (JWT) ou `X-Guest-Order-Token`, ou ADMIN |
| POST | `/api/orders/{number}/cancel` | idem (avant préparation) |
| PATCH | `/api/orders/{number}/payment-method` | idem (uniquement vers `CASH_ON_DELIVERY`) |
| POST | `/api/orders/{number}/payments` (`Idempotency-Key`) | idem → 202 |
| GET | `/api/orders/{number}/payments/{paymentId}` | idem (`Retry-After: 5` tant que `PENDING`) |
| GET | `/api/me/orders?page=0&size=20` | connecté |
| POST | `/api/payments/webhooks/{provider}` | prestataire (vérifié) |

Statuts commande : `PENDING_PAYMENT, PENDING_CONFIRMATION, CONFIRMED, PREPARING, SHIPPED, OUT_FOR_DELIVERY, DELIVERED, CANCELLED`.
Statuts paiement : `UNPAID, PENDING, PAID, FAILED, EXPIRED, REFUND_PENDING, REFUNDED`.

**Devis** — `POST /api/checkout/quote`
```json
{"items":[{"productId":"p_123","sku":null,"qty":2}],"zoneId":"douala","city":null,"promoCode":null}
```
```json
{"quoteId":"qt_Xy…","expiresAt":"2026-10-26T10:15:00Z","currency":"XAF",
 "items":[{"productId":"p_123","sku":null,"name":"…","image":"…","unitPrice":10000,"oldPrice":null,"stock":48,"qty":2,"available":true,"message":null}],
 "subtotal":20000,"discount":0,"shipping":2000,"total":22000,"promo":null,"canOrder":true,"issues":[]}
```
`issues[].code` : `PRICE_CHANGED`, `OUT_OF_STOCK`, `QUANTITY_EXCEEDED`, `INVALID_VARIANT`. Zone `autre` : `city` obligatoire.

**Commande** — `POST /api/orders`, en-tête `Idempotency-Key: <uuid généré une fois par tentative de commande>`
```json
{"quoteId":"qt_Xy…","items":[{"productId":"p_123","sku":null,"qty":2}],"promoCode":null,
 "delivery":{"name":"Jean Kamga","phone":"699123456","email":"jean@example.cm","zoneId":"douala","city":null,"address":"Akwa, face pharmacie","notes":""},
 "paymentMethod":"CASH_ON_DELIVERY"}
```
`paymentMethod` : `CASH_ON_DELIVERY`, `ORANGE_MONEY`, `MTN_MOMO` (aucun choix par défaut). Réponse 201 :
```json
{"number":"HD-7K2M9QXA","createdAt":"…","status":"PENDING_CONFIRMATION","paymentStatus":"UNPAID","paymentMethod":"CASH_ON_DELIVERY",
 "currency":"XAF","subtotal":20000,"discount":0,"shipping":2000,"total":22000,"promoCode":null,
 "items":[{"productId":"p_123","sku":null,"name":"…","variantLabel":null,"image":"…","qty":2,"unitPrice":10000,"lineTotal":20000}],
 "delivery":{"name":"Jean Kamga","phone":"+237699123456","email":"jean@example.cm","zoneId":"douala","zoneLabel":"Douala","city":null,"address":"Akwa, face pharmacie","notes":""},
 "timeline":[{"status":"PENDING_CONFIRMATION","label":"Commande enregistrée","at":"…"}],
 "guestAccessToken":"(invités uniquement, à garder en mémoire de session, jamais dans une URL)"}
```
- Même clé + même contenu → même commande (pas de doublon ; pour un invité, un nouveau jeton est émis). Même clé + contenu différent → 409.
- 409 `{"message","fieldErrors","code":"QUOTE_EXPIRED|QUOTE_MISMATCH|PRICE_CHANGED|STOCK_CHANGED","issues","items","subtotal"}` : refaire un devis.
- 400 `{"message","fieldErrors":{"delivery.phone":"…","paymentMethod":"…"}}`.
- Paiement en ligne : la commande est `PENDING_PAYMENT` / `PENDING`, le stock est réservé jusqu'à `reservationExpiresAt`.

**Paiement Mobile Money** — `POST /api/orders/{number}/payments` + `Idempotency-Key`
```json
{"operator":"ORANGE_MONEY","phone":"699123456"}
```
```json
{"paymentId":"pay_…","orderNumber":"HD-7K2M9QXA","operator":"ORANGE_MONEY","status":"PENDING","amount":22000,"currency":"XAF",
 "expiresAt":"…","message":"Validez le paiement sur votre téléphone.","checkoutUrl":null}
```
Puis interroger `GET /api/orders/{number}/payments/{paymentId}` toutes les 5 s : `PENDING` → `SUCCEEDED | FAILED | EXPIRED | CANCELLED`.
En cas d'échec : nouvel essai (nouvelle clé) ou `PATCH …/payment-method {"paymentMethod":"CASH_ON_DELIVERY"}`.
Seule la notification vérifiée du prestataire marque une commande payée. Paiement reçu après expiration et stock vendu :
commande `CANCELLED` + `REFUND_PENDING` (remboursement manuel puis `POST /api/admin/orders/{n}/mark-refunded`).

### Administration (rôle ADMIN)

| Méthode | Route |
|---|---|
| GET | `/api/admin/orders?q=&status=&paymentStatus=&paymentMethod=&page=&size=` |
| GET | `/api/admin/orders/{number}` (avec paiements et encaissement) |
| PATCH | `/api/admin/orders/{number}/status` `{"status":"CONFIRMED","note":"…"}` |
| POST | `/api/admin/orders/{number}/collect-payment` + `Idempotency-Key` `{"method":"CASH","reference":"Reçu 42"}` (livrée + à la livraison, une seule fois) |
| POST | `/api/admin/orders/{number}/mark-refunded` `{"reason":"…"}` |

Transitions : PENDING_CONFIRMATION → CONFIRMED → PREPARING → SHIPPED → OUT_FOR_DELIVERY → DELIVERED (retrait : PREPARING → DELIVERED) ;
annulation possible avant expédition (stock restitué une seule fois). Changer le statut logistique ne marque jamais une commande payée.

### curl (sans secrets)

```bash
API=https://hightech-backend.onrender.com
curl -s $API/api/checkout/options
curl -s -X POST $API/api/checkout/quote -H 'Content-Type: application/json' \
  -d '{"items":[{"productId":"<ID>","qty":1}],"zoneId":"douala"}'
curl -s -X POST $API/api/orders -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"quoteId":"<QUOTE>","items":[{"productId":"<ID>","qty":1}],"delivery":{"name":"Test","phone":"699000000","zoneId":"douala","address":"Akwa"},"paymentMethod":"CASH_ON_DELIVERY"}'
curl -s $API/api/orders/<NUMERO> -H "X-Guest-Order-Token: <JETON>"
curl -s "$API/api/admin/orders?status=PENDING_CONFIRMATION" -H "Authorization: Bearer <TOKEN_ADMIN>"
curl -s -X PATCH $API/api/admin/orders/<NUMERO>/status -H "Authorization: Bearer <TOKEN_ADMIN>" \
  -H 'Content-Type: application/json' -d '{"status":"CONFIRMED"}'
curl -si -X OPTIONS $API/api/orders -H 'Origin: https://hitech-dz-commerce-suite-kappa.vercel.app' \
  -H 'Access-Control-Request-Method: POST' -H 'Access-Control-Request-Headers: content-type,idempotency-key,x-guest-order-token'
```

### Variables d'environnement (Render, jamais dans le dépôt)

| Variable | Rôle |
|---|---|
| `PAYMENT_PROVIDER` | `none` (défaut : paiement à la livraison seulement), `loov` (production), `sandbox` (tests, **jamais en production**) |
| `LOOV_APP_KEY`, `LOOV_MERCHANT_KEY` | clés Loov (espace marchand) |
| `LOOV_BASE_URL` | facultatif, défaut `https://api.secure.payment.loov-solutions.com/v1` |
| `APP_PUBLIC_BASE_URL` | `https://hightech-backend.onrender.com` (adresse de notification envoyée à Loov) |
| `PAYMENT_SANDBOX_SECRET` | ≥ 16 caractères, uniquement avec `sandbox` |

Si le prestataire choisi n'est pas entièrement configuré, Orange Money et MTN MoMo ne sont pas proposés et toute tentative
renvoie 503 : aucune réussite n'est simulée. Loov ne publiant pas de signature de webhook, chaque notification est
revérifiée auprès de l'API de statut Loov (clés marchand) avant d'être appliquée, avec contrôle de la référence, du montant
et de la devise, et anti-rejeu en base.

Sandbox : webhook signé `X-Sandbox-Timestamp` (secondes) + `X-Sandbox-Signature` = HMAC-SHA256 hex de `"<timestamp>.<corps>"`
avec `PAYMENT_SANDBOX_SECRET` ; corps `{"eventId","reference","status":"SUCCEEDED|FAILED","amount","currency"}`.
