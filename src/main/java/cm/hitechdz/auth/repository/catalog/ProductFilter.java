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
        String subcategory,
        List<String> brands,
        Long min,
        Long max,
        boolean promo,
        Double rating,
        boolean inStock
) {
    public ProductFilter withoutBrands() {
        return new ProductFilter(q, category, subcategory, List.of(), min, max, promo, rating, inStock);
    }

    public ProductFilter withoutPrice() {
        return new ProductFilter(q, category, subcategory, brands, null, null, promo, rating, inStock);
    }
    public ProductFilter withoutSubcategory() {
        return new ProductFilter(q, category, null, brands, min, max, promo, rating, inStock);
    }

    public boolean hasQuery() {
        return q != null && !q.isBlank();
    }
}
