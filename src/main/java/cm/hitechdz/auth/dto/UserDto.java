package cm.hitechdz.auth.dto;

import cm.hitechdz.auth.domain.Role;
import cm.hitechdz.auth.domain.User;

import java.time.Instant;
import java.util.UUID;

/** Représentation publique d'un utilisateur. Le mot de passe n'est jamais exposé. */
public record UserDto(
        UUID id,
        String firstName,
        String lastName,
        String email,
        String phone,
        Role role,
        Instant createdAt
) {
    public static UserDto from(User u) {
        return new UserDto(u.getId(), u.getFirstName(), u.getLastName(), u.getEmail(),
                u.getPhone(), u.getRole(), u.getCreatedAt());
    }
}
