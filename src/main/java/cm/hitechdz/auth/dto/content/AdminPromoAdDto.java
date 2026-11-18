package cm.hitechdz.auth.dto.content;

import java.time.Instant;
import java.util.UUID;

/**
 * status calculé : LIVE (visible), SCHEDULED (à venir), EXPIRED (terminée), DISABLED (désactivée).
 * startsAtLocal / endsAtLocal : mêmes dates à l'heure de Douala (« 2026-12-01T08:00 »), pratiques pour un champ datetime-local.
 */
public record AdminPromoAdDto(UUID id, String imageUrl, String imagePublicId, String mobileImageUrl, String mobileImagePublicId,
                              String altText, String linkType, String linkValue, int position, boolean active,
                              Instant startsAt, Instant endsAt, String startsAtLocal, String endsAtLocal, String status,
                              int width, int height, long sizeBytes, String format, Integer frames, Integer durationMs,
                              UUID createdBy, Instant createdAt, Instant updatedAt) {}
