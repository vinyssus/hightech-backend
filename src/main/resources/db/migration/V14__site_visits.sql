-- =====================================================================
--  Visites du site (taux de conversion du tableau de bord)
-- =====================================================================
-- Une ligne par navigateur et par jour. visitor_hash = SHA-256(jour + identifiant aléatoire du
-- navigateur) : ni IP, ni compte, ni donnée personnelle ; impossible de suivre un visiteur d'un
-- jour à l'autre.
CREATE TABLE site_visits (
    visit_day    DATE        NOT NULL,
    visitor_hash VARCHAR(64) NOT NULL,
    PRIMARY KEY (visit_day, visitor_hash)
);

-- Statistiques par période de création des commandes
CREATE INDEX idx_orders_created_at ON orders (created_at);
