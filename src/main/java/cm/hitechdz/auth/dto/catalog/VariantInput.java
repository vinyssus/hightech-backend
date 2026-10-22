package cm.hitechdz.auth.dto.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record VariantInput(
        @NotBlank(message = "Le SKU est obligatoire.")
        @Size(max = 80, message = "Le SKU ne peut pas dépasser 80 caractères.")
        String sku,

        @Size(max = 40) String color,

        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Couleur invalide (format #RRGGBB).")
        String colorHex,

        @Size(max = 40) String capacity,

        @Size(max = 40) String ram,

        Long priceDelta,

        @NotNull(message = "Le stock est obligatoire.")
        @PositiveOrZero(message = "Le stock doit être un entier positif ou nul.")
        Integer stock
) {}
