package cm.hitechdz.auth.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateStatusRequest(
        @NotNull(message = "Le statut est obligatoire (true ou false).")
        Boolean enabled
) {}
