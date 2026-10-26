package cm.hitechdz.auth.dto.order;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Commande vue par le client. guestAccessToken n'est présent que dans la réponse de création
 * d'une commande invitée : c'est la seule fois où il est transmis (il est stocké hashé).
 */
public record OrderResponse(
        String number,
        Instant createdAt,
        String status,
        String paymentStatus,
        String paymentMethod,
        String currency,
        long subtotal,
        long discount,
        long shipping,
        long total,
        String promoCode,
        List<OrderItemResponse> items,
        DeliveryResponse delivery,
        List<TimelineEntry> timeline,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant reservationExpiresAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String guestAccessToken
) {}
