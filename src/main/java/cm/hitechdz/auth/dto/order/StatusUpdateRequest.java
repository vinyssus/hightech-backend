package cm.hitechdz.auth.dto.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StatusUpdateRequest(
        @NotBlank(message = "Le statut est obligatoire.")
        String status,

        @Size(max = 500, message = "La note ne peut pas dépasser 500 caractères.")
        String note
) {}
