package cm.hitechdz.auth.domain.catalog;

import java.util.Locale;

/** Statut d'un produit. En JSON : "published" ou "draft". */
public enum ProductStatus {
    PUBLISHED,
    DRAFT;

    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** @return le statut correspondant, ou null si la valeur est inconnue */
    public static ProductStatus fromJson(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "published" -> PUBLISHED;
            case "draft" -> DRAFT;
            default -> null;
        };
    }
}
