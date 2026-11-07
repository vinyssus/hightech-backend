package cm.hitechdz.auth.dto.catalog;

import java.util.List;

/**
 * Facettes du catalogue : comptes par marque (sans le filtre marque), par sous-catégorie
 * (sans le filtre sous-catégorie, seulement si une catégorie est filtrée, sinon liste vide) et bornes de prix.
 */
public record Facets(List<BrandFacet> brands, List<SubcategoryFacet> subcategories, long priceMin, long priceMax) {}
