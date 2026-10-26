package cm.hitechdz.auth.dto.order;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Commande vue par l'admin : les champs client + compte, encaissement et paiements. */
public record AdminOrderResponse(
        @JsonUnwrapped OrderResponse order,
        UUID userId,
        boolean guest,
        boolean stockReserved,
        Collected collection,
        List<PaymentStatusResponse> payments
) {
    public record Collected(Instant at, UUID by, String method, String reference, Long amount) {}
}
