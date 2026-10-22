package cm.hitechdz.auth.dto;

import cm.hitechdz.auth.domain.Role;
import jakarta.validation.constraints.NotNull;

public record UpdateRoleRequest(
        @NotNull(message = "Le rôle est obligatoire (CUSTOMER ou ADMIN).")
        Role role
) {}
