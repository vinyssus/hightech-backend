-- =====================================================================
--  Zones de livraison : contrat définitif et tarifs validés par la boutique
-- =====================================================================
-- Flyway n'exécute ce script qu'une seule fois : un redémarrage ou un redéploiement ne recrée,
-- ne duplique et n'écrase jamais une zone.

ALTER TABLE shipping_zones ADD COLUMN created_at TIMESTAMP WITH TIME ZONE;
UPDATE shipping_zones SET created_at = updated_at WHERE created_at IS NULL;
ALTER TABLE shipping_zones ALTER COLUMN created_at SET NOT NULL;

-- Les 4 zones de démonstration de V4 (inactives, sans délai) reçoivent les tarifs de la boutique
-- et sont activées. Une zone déjà modifiée par l'admin (activée, tarif ou délai changé) n'est
-- pas touchée.
UPDATE shipping_zones
   SET label = 'Douala', fee = 1000, delay = '24h', requires_city = FALSE, pickup = FALSE,
       cod_allowed = TRUE, active = TRUE, position = 1, updated_at = CURRENT_TIMESTAMP
 WHERE id = 'douala' AND active = FALSE AND delay IS NULL AND fee = 2000 AND label = 'Douala';

UPDATE shipping_zones
   SET label = 'Yaoundé', fee = 1500, delay = '24-48h', requires_city = FALSE, pickup = FALSE,
       cod_allowed = TRUE, active = TRUE, position = 2, updated_at = CURRENT_TIMESTAMP
 WHERE id = 'yaounde' AND active = FALSE AND delay IS NULL AND fee = 2000 AND label = 'Yaoundé';

UPDATE shipping_zones
   SET label = 'Retrait en boutique (Douala)', fee = 0, delay = 'Même jour', requires_city = FALSE,
       pickup = TRUE, cod_allowed = TRUE, active = TRUE, position = 3, updated_at = CURRENT_TIMESTAMP
 WHERE id = 'retrait' AND active = FALSE AND delay IS NULL AND fee = 0 AND label = 'Retrait en boutique';

UPDATE shipping_zones
   SET label = 'Autres villes du Cameroun', fee = 3000, delay = '2-5 jours', requires_city = TRUE,
       pickup = FALSE, cod_allowed = FALSE, active = TRUE, position = 4, updated_at = CURRENT_TIMESTAMP
 WHERE id = 'autre' AND active = FALSE AND delay IS NULL AND fee = 5000 AND label = 'Autres villes';

-- Table vide (zones supprimées avant cette version) : création des 4 zones.
-- Le comptage est figé avant les insertions pour ne jamais compléter une table non vide.
CREATE TABLE tmp_zone_seed AS SELECT COUNT(*) AS n FROM shipping_zones;
INSERT INTO shipping_zones (id, label, fee, delay, requires_city, pickup, cod_allowed, active, position, created_at, updated_at)
SELECT 'douala', 'Douala', 1000, '24h', FALSE, FALSE, TRUE, TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
 WHERE (SELECT n FROM tmp_zone_seed) = 0;
INSERT INTO shipping_zones (id, label, fee, delay, requires_city, pickup, cod_allowed, active, position, created_at, updated_at)
SELECT 'yaounde', 'Yaoundé', 1500, '24-48h', FALSE, FALSE, TRUE, TRUE, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
 WHERE (SELECT n FROM tmp_zone_seed) = 0;
INSERT INTO shipping_zones (id, label, fee, delay, requires_city, pickup, cod_allowed, active, position, created_at, updated_at)
SELECT 'retrait', 'Retrait en boutique (Douala)', 0, 'Même jour', FALSE, TRUE, TRUE, TRUE, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
 WHERE (SELECT n FROM tmp_zone_seed) = 0;
INSERT INTO shipping_zones (id, label, fee, delay, requires_city, pickup, cod_allowed, active, position, created_at, updated_at)
SELECT 'autre', 'Autres villes du Cameroun', 3000, '2-5 jours', TRUE, FALSE, FALSE, TRUE, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
 WHERE (SELECT n FROM tmp_zone_seed) = 0;
DROP TABLE tmp_zone_seed;

-- Copie du délai annoncé dans la commande (avec zoneId, zoneLabel et frais déjà copiés)
ALTER TABLE orders ADD COLUMN delivery_zone_delay VARCHAR(120);
