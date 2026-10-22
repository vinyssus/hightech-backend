package cm.hitechdz.auth.dto;

import cm.hitechdz.auth.domain.Role;
import cm.hitechdz.auth.domain.User;

import java.time.Instant;
import java.util.UUID;

/** Vue administrateur d'un utilisateur (inclut le statut actif/bloqué). */
public record AdminUserDto(
        UUID id,
        String firstName,
        String lastName,
        String email,
        String phone,
        Role role,
        boolean enabled,
        Instant createdAt
) {
    public static AdminUserDto from(User u) {
        return new AdminUserDto(u.getId(), u.getFirstName(), u.getLastName(), u.getEmail(),
                u.getPhone(), u.getRole(), u.isEnabled(), u.getCreatedAt());
    }
}
