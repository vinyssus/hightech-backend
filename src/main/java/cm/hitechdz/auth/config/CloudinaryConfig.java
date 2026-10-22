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
    @ConditionalOnExpression("!'${app.cloudinary.cloud-name:}'.isBlank()")
    public Cloudinary cloudinary(@Value("${app.cloudinary.cloud-name}") String cloudName,
                                 @Value("${app.cloudinary.api-key:}") String apiKey,
                                 @Value("${app.cloudinary.api-secret:}") String apiSecret) {
        if (apiKey.isBlank() || apiSecret.isBlank()) {
            throw new IllegalStateException(
                    "CLOUDINARY_API_KEY et CLOUDINARY_API_SECRET sont obligatoires avec CLOUDINARY_CLOUD_NAME.");
        }
        log.info("Stockage d'images Cloudinary activé (cloud : {})", cloudName);
        return new Cloudinary(Map.of(
                "cloud_name", cloudName,
                "api_key", apiKey,
                "api_secret", apiSecret,
                "secure", true));
    }
}
