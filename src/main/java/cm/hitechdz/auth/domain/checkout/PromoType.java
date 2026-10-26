package cm.hitechdz.auth.domain.checkout;

public enum PromoType {
    /** value = pourcentage (1–100), plafonné par maxDiscount si renseigné. */
    PERCENT,
    /** value = montant fixe en FCFA. */
    FIXED,
    /** Livraison offerte. */
    FREE_SHIPPING
}
