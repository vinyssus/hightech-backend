-- =====================================================================
--  HITECH DZ - Commandes saisies par un administrateur (WhatsApp) et suivi par numéro + téléphone
-- =====================================================================
ALTER TABLE orders ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'WEB';
ALTER TABLE orders ADD COLUMN created_by_admin UUID;
ALTER TABLE orders ADD CONSTRAINT ck_orders_source CHECK (source IN ('WEB', 'WHATSAPP'));
-- Clés d'idempotence admin préfixées « admin: » : 80 + 6 caractères
ALTER TABLE orders ALTER COLUMN idempotency_key SET DATA TYPE VARCHAR(100);
CREATE INDEX idx_orders_source ON orders (source);

-- Jetons de suivi supplémentaires (POST /api/orders/lookup), stockés hachés, expirables.
-- Ils s'ajoutent au jeton de la commande invitée sans le remplacer.
CREATE TABLE order_guest_tokens (
    order_id    UUID                     NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64)              NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_order_guest_tokens UNIQUE (token_hash)
);

CREATE INDEX idx_order_guest_tokens_order ON order_guest_tokens (order_id);
