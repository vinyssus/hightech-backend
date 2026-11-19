package cm.hitechdz.auth.config;

import cm.hitechdz.auth.security.RelayClientIpFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** IP réelle des visiteurs passant par le relais du site (avant la sécurité et les limites anti-abus). */
@Configuration
public class RelayConfig {

    private static final Logger log = LoggerFactory.getLogger(RelayConfig.class);

    @Bean
    public FilterRegistrationBean<RelayClientIpFilter> relayClientIpFilter(@Value("${app.relay.secret:}") String secret) {
        RelayClientIpFilter filter = new RelayClientIpFilter(secret == null ? "" : secret.trim());
        if (filter.enabled()) {
            log.info("Relais du site : IP réelle des visiteurs activée (RELAY_SECRET configuré).");
        } else if (secret != null && !secret.isBlank()) {
            log.warn("RELAY_SECRET trop court (minimum {} caractères) : IP du relais ignorée.", RelayClientIpFilter.MIN_SECRET_LENGTH);
        } else {
            log.info("Relais du site : RELAY_SECRET absent, X-Client-IP ignoré.");
        }
        FilterRegistrationBean<RelayClientIpFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.addUrlPatterns("/*");
        return reg;
    }
}
