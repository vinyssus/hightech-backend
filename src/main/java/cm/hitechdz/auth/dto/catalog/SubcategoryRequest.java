package cm.hitechdz.auth.dto.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Création ou renommage d'une sous-catégorie (le slug ne change jamais après la création). */
public record SubcategoryRequest(
        @NotBlank(message = "Le nom de la sous-catégorie est obligatoire.")
        @Size(max = 80, message = "Le nom ne peut pas dépasser 80 caractères.")
        String name,
        @PositiveOrZero(message = "La position doit être positive ou nulle.")
        Integer position
) {}
