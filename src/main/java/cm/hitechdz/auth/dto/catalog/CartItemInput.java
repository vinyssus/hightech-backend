package cm.hitechdz.auth.dto.catalog;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** price est optionnel : le prix vu par le client, pour signaler « Le prix a changé ». */
public record CartItemInput(
        @NotBlank(message = "Le produit est obligatoire.")
        String productId,

        String sku,

        @NotNull(message = "La quantité est obligatoire.")
        @Min(value = 1, message = "La quantité doit être d'au moins 1.")
        Integer qty,

        Long price
) {}
