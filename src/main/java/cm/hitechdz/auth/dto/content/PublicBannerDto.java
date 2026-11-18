package cm.hitechdz.auth.dto.content;

public record PublicBannerDto(Long id, String imageUrl, String mobileImageUrl, String altText,
                              String linkType, String linkValue, int position) {}
