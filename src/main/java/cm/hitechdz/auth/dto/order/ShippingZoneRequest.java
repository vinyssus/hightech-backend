package cm.hitechdz.auth.dto.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** id utilisé seulement à la création (ex. « bafoussam ») ; ensuite immuable. */
public record ShippingZoneRequest(
        @Size(max = 40) String id,

        @NotBlank(message = "Le libellé est obligatoire.")
        @Size(max = 80, message = "Le libellé ne peut pas dépasser 80 caractères.")
        String label,

        @NotNull(message = "Les frais sont obligatoires (0 pour gratuit).")
        @PositiveOrZero(message = "Les frais doivent être positifs ou nuls.")
        Long fee,

        @Size(max = 120, message = "Le délai ne peut pas dépasser 120 caractères.")
        String delay,

        Boolean requiresCity,
        Boolean pickup,
        Boolean codAllowed,
        Boolean active,
        Integer position
) {}
