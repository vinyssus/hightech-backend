package cm.hitechdz.auth.repository;

import cm.hitechdz.auth.domain.Role;
import cm.hitechdz.auth.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(String email, UUID id);

    long countByRole(Role role);

    long countByEnabledFalse();

    long countByCreatedAtAfter(Instant since);

    /** Administrateurs actifs : sert à empêcher de retirer le dernier. */
    long countByRoleAndEnabledTrue(Role role);
}
