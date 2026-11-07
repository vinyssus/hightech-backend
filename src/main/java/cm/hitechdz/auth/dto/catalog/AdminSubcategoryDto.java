package cm.hitechdz.auth.dto.catalog;

/** Sous-catégorie vue par l'admin : productCount = produits non supprimés qui l'utilisent. */
public record AdminSubcategoryDto(String slug, String name, int position, long productCount) {}
