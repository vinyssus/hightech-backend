package cm.hitechdz.auth.domain.order;

public enum OrderStatus {
    /** Stock réservé, paiement Mobile Money en attente (réservation limitée dans le temps). */
    PENDING_PAYMENT,
    PAID,
    DELIVERED,
    /** Paiement refusé : stock remis. */
    FAILED,
    /** Réservation expirée sans paiement : stock remis. */
    EXPIRED,
    /** Annulée : stock remis (et ventes décomptées si elle était payée). */
    CANCELLED
}
