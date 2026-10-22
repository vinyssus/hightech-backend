package cm.hitechdz.auth.dto.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SpecDto(
        @NotBlank(message = "La clé de la caractéristique est obligatoire.")
        @Size(max = 100, message = "La clé ne peut pas dépasser 100 caractères.")
        String key,

        @NotBlank(message = "La valeur de la caractéristique est obligatoire.")
        @Size(max = 500, message = "La valeur ne peut pas dépasser 500 caractères.")
        String value
) {}
