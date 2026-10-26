package cm.hitechdz.auth.dto.order;

import cm.hitechdz.auth.dto.catalog.CartLine;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 409 lors de la commande : format d'erreur habituel { message, fieldErrors } complété par les lignes
 * recalculées (items), les anomalies (issues) et le sous-total, pour actualiser le panier.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckoutConflictResponse(String message, Map<String, String> fieldErrors, String code,
                                       List<QuoteIssue> issues, List<CartLine> items, Long subtotal) {}
