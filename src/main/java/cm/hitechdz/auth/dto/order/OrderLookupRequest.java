package cm.hitechdz.auth.dto.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Retrouver une commande sans compte : numéro + téléphone de livraison. */
public record OrderLookupRequest(
        @NotBlank(message = "Le numéro de commande est obligatoire.") @Size(max = 24) String number,
        @NotBlank(message = "Le téléphone est obligatoire.") @Size(max = 30) String phone
) {}
