package cm.hitechdz.auth.domain.order;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * États d'une commande. Les transitions autorisées sont vérifiées côté serveur.
 * PENDING_PAYMENT → CONFIRMED n'est possible que par un paiement vérifié (jamais par l'admin).
 */
public enum OrderStatus {
    PENDING_PAYMENT("En attente de paiement"),
    PENDING_CONFIRMATION("En attente de confirmation"),
    CONFIRMED("Confirmée"),
    PREPARING("En préparation"),
    SHIPPED("Expédiée"),
    OUT_FOR_DELIVERY("En cours de livraison"),
    DELIVERED("Livrée"),
    CANCELLED("Annulée");

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Transitions qu'un administrateur peut demander via PATCH /api/admin/orders/{number}/status. */
    private static final Map<OrderStatus, Set<OrderStatus>> ADMIN_TRANSITIONS = Map.of(
            PENDING_PAYMENT, EnumSet.of(CANCELLED),
            PENDING_CONFIRMATION, EnumSet.of(CONFIRMED, CANCELLED),
            CONFIRMED, EnumSet.of(PREPARING, CANCELLED),
            PREPARING, EnumSet.of(SHIPPED, DELIVERED, CANCELLED),     // DELIVERED : remise au retrait
            SHIPPED, EnumSet.of(OUT_FOR_DELIVERY, DELIVERED, CANCELLED),
            OUT_FOR_DELIVERY, EnumSet.of(DELIVERED, CANCELLED),
            DELIVERED, EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class)
    );

    public boolean adminCanMoveTo(OrderStatus target) {
        return ADMIN_TRANSITIONS.get(this).contains(target);
    }

    /** Le client peut annuler tant que la commande n'est pas en préparation. */
    public boolean customerCanCancel() {
        return this == PENDING_PAYMENT || this == PENDING_CONFIRMATION || this == CONFIRMED;
    }

    /** Stock à restituer en cas d'annulation (la marchandise n'est pas partie). */
    public boolean beforeShipping() {
        return this == PENDING_PAYMENT || this == PENDING_CONFIRMATION || this == CONFIRMED || this == PREPARING;
    }
}
