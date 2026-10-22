package cm.hitechdz.auth.config;

import com.cloudinary.Cloudinary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Client Cloudinary, créé seulement si CLOUDINARY_CLOUD_NAME est renseigné.
 * Sans configuration, l'application démarre quand même : les envois d'images renvoient 503.
 */
@Configuration
public class CloudinaryConfig {

    private static final Logger log = LoggerFactory.getLogger(CloudinaryConfig.class);

    @Bean
    @ConditionalOnExpression("!'${app.cloudinary.cloud-name:}'.isBlank() or !'${app.cloudinary.url:}'.isBlank()")
    public Cloudinary cloudinary(@Value("${app.cloudinary.url:}") String url,
                                 @Value("${app.cloudinary.cloud-name:}") String cloudName,
                                 @Value("${app.cloudinary.api-key:}") String apiKey,
                                 @Value("${app.cloudinary.api-secret:}") String apiSecret) {
        Cloudinary cloudinary;
        if (!url.isBlank()) {
            cloudinary = new Cloudinary(url.trim());
            cloudinary.config.secure = true;
        } else {
            if (apiKey.isBlank() || apiSecret.isBlank()) {
                throw new IllegalStateException(
                        "CLOUDINARY_API_KEY et CLOUDINARY_API_SECRET sont obligatoires avec CLOUDINARY_CLOUD_NAME.");
            }
            cloudinary = new Cloudinary(Map.of(
                    "cloud_name", cloudName.trim(),
                    "api_key", apiKey.trim(),
                    "api_secret", apiSecret.trim(),
                    "secure", true));
        }
        log.info("Stockage d'images Cloudinary activé (cloud : {})", cloudinary.config.cloudName);
        return cloudinary;
    }
}
