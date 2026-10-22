package cm.hitechdz.auth.repository.catalog;

import java.util.List;

/**
 * Filtres du catalogue public.
 * @param q       texte normalisé (minuscules, sans accents), découpé en mots côté repository
 * @param brands  noms de marques en minuscules
 */
public record ProductFilter(
        String q,
        String category,
        List<String> brands,
        Long min,
        Long max,
        boolean promo,
        Double rating,
        boolean inStock
) {
    public ProductFilter withoutBrands() {
        return new ProductFilter(q, category, List.of(), min, max, promo, rating, inStock);
    }

    public ProductFilter withoutPrice() {
        return new ProductFilter(q, category, brands, null, null, promo, rating, inStock);
    }

    public boolean hasQuery() {
        return q != null && !q.isBlank();
    }
}
