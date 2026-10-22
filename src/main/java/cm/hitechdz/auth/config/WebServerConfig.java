package cm.hitechdz.auth.config;

import org.apache.tomcat.util.buf.EncodedSolidusHandling;
import org.springframework.boot.web.embedded.tomcat.TomcatConnectorCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebServerConfig {

    /**
     * Laisse passer les « / » encodés (%2F) sans les rejeter : le publicId Cloudinary
     * d'une image (hitech-dz/products/slug/xxx) est transmis encodé dans l'URL.
     */
    @Bean
    public TomcatConnectorCustomizer encodedSlashPassThrough() {
        return connector -> connector.setEncodedSolidusHandling(EncodedSolidusHandling.PASS_THROUGH.getValue());
    }
}
