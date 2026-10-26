package cm.hitechdz.auth.dto.order;

import jakarta.validation.constraints.NotBlank;

/** Le site ne reçoit jamais le code secret Mobile Money : le client valide sur son téléphone. */
public record PaymentInitRequest(
        @NotBlank(message = "Choisissez un opérateur.")
        String operator,

        @NotBlank(message = "Le numéro à débiter est obligatoire.")
        String phone
) {}
