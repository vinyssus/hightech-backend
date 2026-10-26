package cm.hitechdz.auth.service.order;

import cm.hitechdz.auth.domain.Role;
import cm.hitechdz.auth.domain.order.CustomerOrder;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.security.AuthenticatedUser;
import cm.hitechdz.auth.security.JwtAuthenticationFilter;
import cm.hitechdz.auth.util.Tokens;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.Instant;
import java.util.UUID;

/**
 * Qui accède à une commande : client propriétaire (JWT), administrateur, ou invité muni du jeton
 * reçu à la création (en-tête X-Guest-Order-Token, jamais dans l'URL).
 */
public record OrderAccess(UUID userId, boolean admin, String guestToken) {

    public static final String GUEST_HEADER = "X-Guest-Order-Token";
    static final String DENIED = "Accès refusé à cette commande.";

    /** Contexte de la requête en cours. */
    public static OrderAccess current(String guestToken) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser u) {
            return new OrderAccess(u.id(), u.role() == Role.ADMIN, blankToNull(guestToken));
        }
        return new OrderAccess(null, false, blankToNull(guestToken));
    }

    public static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AuthenticatedUser u ? u.id() : null;
    }

    /** Vérifie l'accès ; 401 si aucune preuve (ou JWT expiré, pour que le frontend rafraîchisse), 403 sinon. */
    public void check(CustomerOrder order) {
        if (admin) {
            return;
        }
        if (userId != null && userId.equals(order.getUserId())) {
            return;
        }
        if (guestToken != null && order.getUserId() == null && order.getGuestTokenHash() != null
                && Tokens.constantTimeEquals(order.getGuestTokenHash(), Tokens.sha256(guestToken))
                && (order.getGuestTokenExpiresAt() == null || order.getGuestTokenExpiresAt().isAfter(Instant.now()))) {
            return;
        }
        if (userId == null && guestToken == null) {
            if (JwtAuthenticationFilter.TOKEN_EXPIRED.equals(requestAttribute(JwtAuthenticationFilter.ATTR_TOKEN_ERROR))) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "Votre session a expiré. Veuillez vous reconnecter.");
            }
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Connectez-vous ou fournissez le jeton de la commande.");
        }
        throw new ApiException(HttpStatus.FORBIDDEN, DENIED);
    }

    private static Object requestAttribute(String name) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return attrs == null ? null : attrs.getAttribute(name, RequestAttributes.SCOPE_REQUEST);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
