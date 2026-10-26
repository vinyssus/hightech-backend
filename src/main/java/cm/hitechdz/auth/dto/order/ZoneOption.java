package cm.hitechdz.auth.dto.order;

/** Zone proposée au client. codAllowed : paiement à la livraison possible ; requiresCity : ville obligatoire. */
public record ZoneOption(String id, String label, String delay, long fee, boolean codAllowed,
                         boolean requiresCity, boolean pickup) {}
