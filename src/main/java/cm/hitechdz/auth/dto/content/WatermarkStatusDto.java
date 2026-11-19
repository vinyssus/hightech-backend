package cm.hitechdz.auth.dto.content;

import java.time.Instant;
import java.util.List;

/**
 * État du logo incrusté. logoUrl : logo actuel (null tant que le logo par défaut n'a pas encore été envoyé) ;
 * customLogo : false = logo HITECH DZ intégré à l'application.
 */
public record WatermarkStatusDto(boolean enabled, String position, int widthPercent, int marginPercent, int opacity,
                                 String logoUrl, boolean customLogo, List<String> positions, Instant updatedAt) {}
