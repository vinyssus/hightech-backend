package cm.hitechdz.auth.dto.order;

import jakarta.validation.constraints.Size;

public record CancelRequest(
        @Size(max = 500, message = "Le motif ne peut pas dépasser 500 caractères.")
        String reason
) {}
