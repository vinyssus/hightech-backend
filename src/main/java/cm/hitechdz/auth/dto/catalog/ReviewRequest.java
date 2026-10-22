package cm.hitechdz.auth.dto.catalog;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewRequest(
        @NotNull(message = "La note est obligatoire.")
        @Min(value = 1, message = "La note doit être comprise entre 1 et 5.")
        @Max(value = 5, message = "La note doit être comprise entre 1 et 5.")
        Integer rating,

        @NotBlank(message = "Le titre est obligatoire.")
        @Size(min = 3, max = 120, message = "Le titre doit contenir entre 3 et 120 caractères.")
        String title,

        @NotBlank(message = "Le texte de l'avis est obligatoire.")
        @Size(min = 10, max = 3000, message = "L'avis doit contenir entre 10 et 3000 caractères.")
        String body,

        /** Optionnel : ville affichée avec le prénom. */
        @Size(max = 60, message = "La ville ne peut pas dépasser 60 caractères.")
        String city
) {}
