package cm.hitechdz.auth.dto.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** icon et sub sont optionnels (icône par défaut : tv). */
public record CategoryRequest(
        @NotBlank(message = "Le nom de la catégorie est obligatoire.")
        @Size(max = 80, message = "Le nom ne peut pas dépasser 80 caractères.")
        String name,

        String icon,

        @Size(max = 30, message = "30 sous-catégories maximum.")
        List<@NotBlank(message = "Une sous-catégorie ne peut pas être vide.")
             @Size(max = 80, message = "Une sous-catégorie ne peut pas dépasser 80 caractères.") String> sub
) {}
