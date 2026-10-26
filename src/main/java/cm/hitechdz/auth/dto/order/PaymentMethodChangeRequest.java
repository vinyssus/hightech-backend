package cm.hitechdz.auth.dto.order;

import jakarta.validation.constraints.NotBlank;

public record PaymentMethodChangeRequest(
        @NotBlank(message = "Le mode de paiement est obligatoire.")
        String paymentMethod
) {}
