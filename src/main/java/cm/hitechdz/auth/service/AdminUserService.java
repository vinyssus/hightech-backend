package cm.hitechdz.auth.service;

import cm.hitechdz.auth.domain.Role;
import cm.hitechdz.auth.domain.User;
import cm.hitechdz.auth.dto.AdminStatsDto;
import cm.hitechdz.auth.dto.AdminUserDto;
import cm.hitechdz.auth.dto.PageResponse;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.repository.UserRepository;
import cm.hitechdz.auth.repository.UserSpecifications;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Gestion des utilisateurs par les administrateurs.
 * Garde-fous : pas d'action sur son propre compte, jamais zéro administrateur actif,
 * et toute rétrogradation / blocage ferme immédiatement les sessions de la personne.
 */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    static final String NOT_FOUND = "Utilisateur introuvable.";
    static final String SELF_ACTION = "Vous ne pouvez pas effectuer cette action sur votre propre compte.";
    static final String LAST_ADMIN = "Impossible de retirer ou supprimer le dernier administrateur.";
    static final String INVALID_SORT = "Tri invalide. Champs autorisés : createdAt, email, firstName, lastName, role.";

    private static final Set<String> SORTABLE = Set.of("createdAt", "email", "firstName", "lastName", "role");
    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;

    public AdminUserService(UserRepository userRepository, RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional(readOnly = true)
    public AdminStatsDto stats() {
        Instant now = Instant.now();
        return new AdminStatsDto(
                userRepository.count(),
                userRepository.countByRole(Role.CUSTOMER),
                userRepository.countByRole(Role.ADMIN),
                userRepository.countByEnabledFalse(),
                userRepository.countByCreatedAtAfter(now.minus(Duration.ofDays(7))),
                userRepository.countByCreatedAtAfter(now.minus(Duration.ofDays(30))));
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminUserDto> list(String search, Role role, Boolean enabled, int page, int size, String sort) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(safePage, safeSize, parseSort(sort));
        return PageResponse.from(
                userRepository.findAll(UserSpecifications.matching(search, role, enabled), pageable)
                        .map(AdminUserDto::from));
    }

    @Transactional(readOnly = true)
    public AdminUserDto get(UUID id) {
        return AdminUserDto.from(load(id));
    }

    @Transactional
    public AdminUserDto changeRole(UUID actorId, UUID id, Role newRole) {
        User user = load(id);
        forbidSelf(actorId, id);
        if (user.getRole() == newRole) {
            return AdminUserDto.from(user);
        }
        if (user.getRole() == Role.ADMIN && user.isEnabled()) {
            ensureAnotherActiveAdmin();
        }
        user.setRole(newRole);
        // Le flush a lieu avant la révocation (flushAutomatically) : le nouveau rôle est bien enregistré.
        refreshTokenService.revokeAll(id);
        log.info("Rôle de {} changé en {} par l'administrateur {}", user.getEmail(), newRole, actorId);
        return AdminUserDto.from(user);
    }

    @Transactional
    public AdminUserDto changeStatus(UUID actorId, UUID id, boolean enabled) {
        User user = load(id);
        forbidSelf(actorId, id);
        if (user.isEnabled() == enabled) {
            return AdminUserDto.from(user);
        }
        if (!enabled && user.getRole() == Role.ADMIN) {
            ensureAnotherActiveAdmin();
        }
        user.setEnabled(enabled);
        userRepository.flush();
        if (!enabled) {
            refreshTokenService.revokeAll(id);
        }
        log.info("Compte {} {} par l'administrateur {}", user.getEmail(), enabled ? "débloqué" : "bloqué", actorId);
        return AdminUserDto.from(user);
    }

    @Transactional
    public void delete(UUID actorId, UUID id) {
        User user = load(id);
        forbidSelf(actorId, id);
        if (user.getRole() == Role.ADMIN && user.isEnabled()) {
            ensureAnotherActiveAdmin();
        }
        // Les refresh tokens et codes de réinitialisation sont supprimés par ON DELETE CASCADE.
        userRepository.delete(user);
        log.info("Compte {} supprimé par l'administrateur {}", user.getEmail(), actorId);
    }

    // ------------------------------------------------------------------ helpers
    private User load(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
    }

    private static void forbidSelf(UUID actorId, UUID targetId) {
        if (actorId.equals(targetId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, SELF_ACTION);
        }
    }

    private void ensureAnotherActiveAdmin() {
        if (userRepository.countByRoleAndEnabledTrue(Role.ADMIN) <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, LAST_ADMIN);
        }
    }

    private static Sort parseSort(String sort) {
        String field = "createdAt";
        Sort.Direction direction = Sort.Direction.DESC;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            String requested = parts[0].trim();
            if (!SORTABLE.contains(requested)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_SORT);
            }
            field = requested;
            if (parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())) {
                direction = Sort.Direction.ASC;
            }
        }
        // Tri secondaire sur l'id pour une pagination stable
        return Sort.by(direction, field).and(Sort.by(Sort.Direction.ASC, "id"));
    }
}
