package cm.hitechdz.auth.service.media;

/**
 * Logo à incruster dans une image à son arrivée sur Cloudinary.
 *
 * @param logoPublicId identifiant Cloudinary du logo (PNG transparent)
 * @param gravity      position Cloudinary : south_east, south_west, south, north_east, north_west, center
 * @param widthRatio   largeur du logo / largeur de l'image (0.10 à 0.60)
 * @param marginRatio  marge / dimensions de l'image (0 à 0.10)
 * @param opacity      opacité du logo en % (20 à 100)
 */
public record Watermark(String logoPublicId, String gravity, double widthRatio, double marginRatio, int opacity) {}
