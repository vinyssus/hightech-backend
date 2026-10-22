-- Blocage des comptes par un administrateur + index pour les listes et statistiques admin
ALTER TABLE users ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX idx_users_created_at ON users (created_at);
CREATE INDEX idx_users_role ON users (role);
