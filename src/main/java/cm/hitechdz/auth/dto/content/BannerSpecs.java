package cm.hitechdz.auth.dto.content;

import java.util.List;
import java.util.Map;

/** Normes d'image, pour afficher les consignes et pré-valider côté frontend (le serveur revalide toujours). */
public record BannerSpecs(List<String> formats, long maxBytes, long maxGifBytes, int maxPerSlot,
                          int maxWidth, int maxHeight, int mobileMinWidth, double ratioTolerance,
                          Map<String, SlotSpec> slots) {

    public record SlotSpec(String ratio, int minWidth, int minHeight, String recommended) {}
}
