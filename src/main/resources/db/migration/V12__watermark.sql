-- =====================================================================
--  HITECH DZ - Logo incrusté automatiquement sur les nouvelles photos produits
--  logo_public_id NULL = logo HITECH DZ intégré à l'application (envoyé sur Cloudinary au premier usage)
-- =====================================================================
CREATE TABLE watermark_settings (
    id                      INTEGER                  PRIMARY KEY,
    enabled                 BOOLEAN                  NOT NULL DEFAULT TRUE,
    logo_public_id          VARCHAR(255),
    logo_url                VARCHAR(500),
    custom_logo             BOOLEAN                  NOT NULL DEFAULT FALSE,
    position                VARCHAR(20)              NOT NULL DEFAULT 'SOUTH_EAST',
    width_percent           INTEGER                  NOT NULL DEFAULT 33,
    margin_percent          INTEGER                  NOT NULL DEFAULT 3,
    opacity                 INTEGER                  NOT NULL DEFAULT 100,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_watermark_single CHECK (id = 1),
    CONSTRAINT ck_watermark_position CHECK (position IN ('SOUTH_EAST', 'SOUTH_WEST', 'SOUTH', 'NORTH_EAST', 'NORTH_WEST', 'CENTER')),
    CONSTRAINT ck_watermark_width CHECK (width_percent BETWEEN 10 AND 60),
    CONSTRAINT ck_watermark_margin CHECK (margin_percent BETWEEN 0 AND 10),
    CONSTRAINT ck_watermark_opacity CHECK (opacity BETWEEN 20 AND 100)
);

INSERT INTO watermark_settings (id, enabled, position, width_percent, margin_percent, opacity, updated_at)
VALUES (1, TRUE, 'SOUTH_EAST', 33, 3, 100, CURRENT_TIMESTAMP);
