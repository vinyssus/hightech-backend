package cm.hitechdz.auth.dto.content;

import java.util.UUID;

public record PublicPromoAd(UUID id, String imageUrl, String mobileImageUrl, String altText,
                            String linkType, String linkValue, int position) {}
