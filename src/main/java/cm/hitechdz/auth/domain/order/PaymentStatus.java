package cm.hitechdz.auth.domain.order;

/** État du paiement d'une commande. */
public enum PaymentStatus {
    UNPAID,
    PENDING,
    PAID,
    FAILED,
    EXPIRED,
    REFUND_PENDING,
    REFUNDED
}
