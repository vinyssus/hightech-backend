package cm.hitechdz.auth.dto.order;

/** type : PERCENT, FIXED ou FREE_SHIPPING ; discount : remise appliquée en FCFA. */
public record PromoSummary(String code, String type, String description, long discount) {}
