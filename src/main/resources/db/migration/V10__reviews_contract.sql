-- =====================================================================
--  HITECH DZ - Avis clients : alignement sur le contrat du frontend
--  - auteur affiché « Prénom N. » (prénom + initiale du nom), jamais l'e-mail ni le téléphone
--  - ville jusqu'à 80 caractères
--  - index pour le tri par date
-- =====================================================================
ALTER TABLE reviews ALTER COLUMN author_city SET DATA TYPE VARCHAR(80);

UPDATE reviews r SET author_name = (
    SELECT CASE
               WHEN u.last_name IS NULL OR TRIM(u.last_name) = '' THEN SUBSTRING(TRIM(u.first_name), 1, 40)
               ELSE SUBSTRING(TRIM(u.first_name), 1, 36) || ' ' || UPPER(SUBSTRING(TRIM(u.last_name), 1, 1)) || '.'
           END
    FROM users u WHERE u.id = r.user_id)
WHERE r.user_id IS NOT NULL;

CREATE INDEX idx_reviews_product_created ON reviews (product_id, created_at DESC);
