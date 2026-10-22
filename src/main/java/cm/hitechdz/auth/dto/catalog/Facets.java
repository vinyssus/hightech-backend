package cm.hitechdz.auth.dto.catalog;

import java.util.List;

/** Facettes du catalogue : comptes par marque (sans le filtre marque) et bornes de prix. */
public record Facets(List<BrandFacet> brands, long priceMin, long priceMax) {}
