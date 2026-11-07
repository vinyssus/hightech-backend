package cm.hitechdz.auth.dto.catalog;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Nouvel ordre d'affichage : tous les slugs des sous-catégories de la catégorie. */
public record SubcategoryOrderRequest(
        @NotNull(message = "La liste des sous-catégories est obligatoire.")
        List<String> slugs
) {}
