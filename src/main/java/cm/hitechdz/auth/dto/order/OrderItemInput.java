package cm.hitechdz.auth.dto.order;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record OrderItemInput(
        @NotBlank(message = "Le produit est obligatoire.")
        String productId,

        String sku,

        @NotNull(message = "La quantité est obligatoire.")
        @Min(value = 1, message = "La quantité doit être d'au moins 1.")
        @Max(value = 10, message = "Quantité maximale : 10 par article.")
        Integer qty
) {}
