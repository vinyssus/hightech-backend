package cm.hitechdz.auth.security;

import cm.hitechdz.auth.domain.Role;

import java.util.UUID;

/** Principal placé dans le SecurityContext à partir des claims du JWT. */
public record AuthenticatedUser(UUID id, String email, Role role) {}
