package cm.hitechdz.auth.service.media;

/** Image hébergée : URL https (secure_url Cloudinary), identifiant et dimensions. */
public record StoredImage(String url, String publicId, Integer width, Integer height) {}
