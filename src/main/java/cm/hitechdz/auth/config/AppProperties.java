package cm.hitechdz.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Propriétés applicatives (préfixe "app" dans application.yml).
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Jwt jwt,
        Cors cors,
        PasswordReset passwordReset,
        RateLimit rateLimit,
        Mail mail,
        Admin admin
) {
    public record Jwt(
            String secret,
            String issuer,
            Duration accessTokenTtl,
            Duration refreshTokenTtlRemember,
            Duration refreshTokenTtlSession
    ) {}

    public record Cors(List<String> allowedOrigins) {}

    public record PasswordReset(Duration codeTtl, int maxAttempts) {}

    public record RateLimit(Rule login, Rule forgotPassword) {
        public record Rule(int maxRequests, Duration window) {}
    }

    public record Mail(String provider, String from) {}

    public record Admin(boolean seedEnabled, String email, String password) {}
}
