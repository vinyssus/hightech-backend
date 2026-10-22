package cm.hitechdz.auth.repository;

import cm.hitechdz.auth.domain.Role;
import cm.hitechdz.auth.domain.User;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Filtres dynamiques de la liste des utilisateurs (espace admin). */
public final class UserSpecifications {

    private static final char ESCAPE = '\\';

    private UserSpecifications() {}

    public static Specification<User> matching(String search, Role role, Boolean enabled) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (search != null && !search.isBlank()) {
                String like = "%" + escapeLike(search.trim().toLowerCase(Locale.ROOT)) + "%";
                Expression<String> firstName = cb.lower(root.<String>get("firstName"));
                Expression<String> lastName = cb.lower(root.<String>get("lastName"));
                Expression<String> fullName = cb.concat(cb.concat(firstName, " "), lastName);
                predicates.add(cb.or(
                        cb.like(firstName, like, ESCAPE),
                        cb.like(lastName, like, ESCAPE),
                        cb.like(fullName, like, ESCAPE),
                        cb.like(root.<String>get("email"), like, ESCAPE),   // e-mail déjà en minuscules
                        cb.like(root.<String>get("phone"), like, ESCAPE)));
            }
            if (role != null) {
                predicates.add(cb.equal(root.get("role"), role));
            }
            if (enabled != null) {
                predicates.add(cb.equal(root.get("enabled"), enabled));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
