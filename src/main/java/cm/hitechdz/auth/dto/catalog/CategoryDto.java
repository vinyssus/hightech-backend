package cm.hitechdz.auth.dto.catalog;

import java.util.List;

/** sub = noms des sous-catégories (format historique conservé) ; subcategories = slug + nom. */
public record CategoryDto(String slug, String name, String icon, List<String> sub,
                          List<SubcategoryDto> subcategories) {}
