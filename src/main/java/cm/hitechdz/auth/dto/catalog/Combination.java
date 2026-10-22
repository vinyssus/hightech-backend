package cm.hitechdz.auth.dto.catalog;

/** Combinaison vendable : prix final (prix de base + écart) et stock. */
public record Combination(String sku, String color, String capacity, String ram, long price, int stock) {}
