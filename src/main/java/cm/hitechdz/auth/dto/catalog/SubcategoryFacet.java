package cm.hitechdz.auth.dto.catalog;

/** Nombre de produits par sous-catégorie (renseigné quand le filtre catégorie est présent). */
public record SubcategoryFacet(String slug, String name, long count) {}
