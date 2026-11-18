-- =====================================================================
--  HITECH DZ - Panneau publicitaire flottant (bas gauche du site)
-- =====================================================================
CREATE TABLE promo_popup_ads (
    id                      UUID                     PRIMARY KEY,
    image_url               VARCHAR(500)             NOT NULL,
    image_public_id         VARCHAR(255),
    mobile_image_url        VARCHAR(500),
    mobile_image_public_id  VARCHAR(255),
    alt_text                VARCHAR(120)             NOT NULL,
    link_type               VARCHAR(20)              NOT NULL DEFAULT 'NONE',
    link_value              VARCHAR(500),
    position                INTEGER                  NOT NULL DEFAULT 0,
    active                  BOOLEAN                  NOT NULL DEFAULT TRUE,
    starts_at               TIMESTAMP WITH TIME ZONE,
    ends_at                 TIMESTAMP WITH TIME ZONE,
    width                   INTEGER                  NOT NULL,
    height                  INTEGER                  NOT NULL,
    size_bytes              BIGINT                   NOT NULL,
    format                  VARCHAR(10)              NOT NULL,
    frames                  INTEGER,
    duration_ms             INTEGER,
    created_by              UUID,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_promo_popup_link CHECK (link_type IN ('NONE', 'CATEGORY', 'SUBCATEGORY', 'PRODUCT', 'CATALOGUE', 'EXTERNAL')),
    CONSTRAINT ck_promo_popup_position CHECK (position >= 0),
    CONSTRAINT ck_promo_popup_dates CHECK (starts_at IS NULL OR ends_at IS NULL OR ends_at > starts_at)
);

CREATE INDEX idx_promo_popup_active_position ON promo_popup_ads (active, position);

-- Réglages (une seule ligne). updated_at = dernière modification du panneau (réglages ou publicités).
CREATE TABLE promo_popup_settings (
    id              INTEGER                  PRIMARY KEY,
    enabled         BOOLEAN                  NOT NULL DEFAULT TRUE,
    delay_ms        INTEGER                  NOT NULL DEFAULT 1500,
    interval_ms     INTEGER                  NOT NULL DEFAULT 4000,
    show_on_mobile  BOOLEAN                  NOT NULL DEFAULT TRUE,
    dismiss_mode    VARCHAR(20)              NOT NULL DEFAULT 'SESSION',
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_promo_popup_settings_single CHECK (id = 1),
    CONSTRAINT ck_promo_popup_settings_delay CHECK (delay_ms BETWEEN 500 AND 10000),
    CONSTRAINT ck_promo_popup_settings_interval CHECK (interval_ms BETWEEN 2000 AND 15000),
    CONSTRAINT ck_promo_popup_settings_dismiss CHECK (dismiss_mode IN ('SESSION', 'DAY', 'NEVER_AGAIN'))
);

INSERT INTO promo_popup_settings (id, enabled, delay_ms, interval_ms, show_on_mobile, dismiss_mode, updated_at)
VALUES (1, TRUE, 1500, 4000, TRUE, 'SESSION', CURRENT_TIMESTAMP);
