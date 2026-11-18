package cm.hitechdz.auth.dto.content;

import java.time.Instant;
import java.util.List;

/** Réponse publique : réglages + publicités visibles ; updatedAt = dernière modification du panneau. */
public record PromoPopupResponse(PromoSettingsDto settings, List<PublicPromoAd> ads, Instant updatedAt) {}
