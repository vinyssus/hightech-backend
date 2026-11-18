package cm.hitechdz.auth.dto.content;

import java.time.Instant;

/** status calculé : ACTIVE (visible), SCHEDULED (à venir), EXPIRED (terminée), INACTIVE (désactivée). */
public record AdminBannerDto(Long id, String slot, String imageUrl, String imagePublicId, String mobileImageUrl,
                             String mobileImagePublicId, String altText, String linkType, String linkValue, int position,
                             boolean active, Instant startsAt, Instant endsAt, String status,
                             int width, int height, long sizeBytes, String format,
                             Integer mobileWidth, Integer mobileHeight, Instant createdAt, Instant updatedAt) {}
