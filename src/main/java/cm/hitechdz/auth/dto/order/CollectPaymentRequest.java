package cm.hitechdz.auth.dto.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** method : CASH, ORANGE_MONEY ou MTN_MOMO (moyen utilisé à la remise). */
public record CollectPaymentRequest(
        @NotBlank(message = "Le moyen d'encaissement est obligatoire.")
        String method,

        @Size(max = 120, message = "La référence ne peut pas dépasser 120 caractères.")
        String reference
) {}
