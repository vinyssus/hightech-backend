package cm.hitechdz.auth.dto.catalog;

/** Combinaison telle que saisie dans l'admin (avec l'écart de prix et la couleur hex). */
public record AdminCombination(String sku, String color, String colorHex, String capacity, String ram,
                               long priceDelta, long price, int stock) {}
