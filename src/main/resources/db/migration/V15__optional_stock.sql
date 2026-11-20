-- =====================================================================
--  Stock facultatif : produit créé sans stock = stock non suivi
-- =====================================================================
-- stock_tracked = FALSE : vendable sans limite de quantité, jamais en rupture ni en alerte,
-- stock non affiché (API : "stock": null). Les produits existants restent suivis.
ALTER TABLE products ADD COLUMN stock_tracked BOOLEAN NOT NULL DEFAULT TRUE;

-- Une ligne de commande ne remet en stock que ce qu'elle a réellement décrémenté
-- (un produit peut passer de « non suivi » à « suivi » entre la commande et son annulation).
ALTER TABLE order_items ADD COLUMN stock_decremented BOOLEAN NOT NULL DEFAULT TRUE;
