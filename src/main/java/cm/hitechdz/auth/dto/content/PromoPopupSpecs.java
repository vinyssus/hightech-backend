package cm.hitechdz.auth.dto.content;

import java.util.List;

/** Règles du panneau, pour que le frontend valide avant l'envoi (le serveur revalide toujours). */
public record PromoPopupSpecs(List<String> formats, long maxGifBytes, long maxBytes, String ratio, double ratioTolerance,
                              int minWidth, int minHeight, int maxWidth, int maxHeight, String recommended,
                              int maxGifFrames, int maxGifDurationMs, int maxActive,
                              Range delayMs, Range intervalMs, List<String> catalogueValues, List<String> dismissModes,
                              String timezone) {

    public record Range(int min, int max, int defaultValue) {}
}
